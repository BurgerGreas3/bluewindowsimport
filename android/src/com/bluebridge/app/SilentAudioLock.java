package com.bluebridge.app;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

/**
 * Silent audio lock: holds a zero-byte PCM loop so the audio HAL reports an
 * ACTIVE playback session for our UID. Every OEM cgroup freezer (HANS included)
 * excludes UIDs with live audio sessions — freezing one causes audible glitches
 * vendors treat as hardware defects. Zeroes = true silence, 8 kHz mono = ~16 KB/s
 * pump at minimum thread priority. No MediaSession => no user-visible notification.
 * This is the freezer shield; it runs for the whole service lifetime (NOT tied to
 * the on-demand CPU lock, which may release while idle).
 */
public class SilentAudioLock {
    private static final String TAG = "SilentAudio";
    private AudioTrack track;
    private Thread pump;
    private volatile boolean running;

    public synchronized void start() {
        if (running) return;
        try {
            int rate = 8000;
            int ch = AudioFormat.CHANNEL_OUT_MONO;
            int enc = AudioFormat.ENCODING_PCM_16BIT;
            int minBuf = AudioTrack.getMinBufferSize(rate, ch, enc);
            if (minBuf <= 0) minBuf = rate * 2;
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(ch)
                    .setEncoding(enc)
                    .build();
            track = new AudioTrack(attrs, fmt, minBuf * 2,
                    AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.w(TAG, "track init failed, shield OFF");
                try { track.release(); } catch (Exception ignored) {}
                track = null;
                return;
            }
            final byte[] silence = new byte[Math.min(Math.max(minBuf, 1024), 4096)];
            track.play();
            running = true;
            pump = new Thread(() -> {
                try {
                    while (running) {
                        AudioTrack t = track;
                        if (t == null) break;
                        int w = t.write(silence, 0, silence.length);
                        if (w < 0) break;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "pump end: " + e.getMessage());
                }
            }, "SilentAudioPump");
            pump.setPriority(Thread.MIN_PRIORITY);
            pump.start();
            Log.i(TAG, "silent audio lock ON, session=" + track.getAudioSessionId());
        } catch (Exception e) {
            Log.w(TAG, "audio lock failed (shield OFF, rest of service unaffected): " + e.getMessage());
        }
    }

    public synchronized void stop() {
        running = false;
        // Wake the blocked write FIRST (stop), let the pump thread exit, THEN
        // release: releasing while native write() is blocked risks HAL SIGSEGV.
        Thread p = pump;
        try {
            if (track != null) {
                try { track.stop(); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        try {
            if (p != null) p.join(1000);
        } catch (Exception ignored) {}
        try {
            if (track != null) track.release();
        } catch (Exception ignored) {}
        track = null;
        pump = null;
        Log.i(TAG, "silent audio lock OFF");
    }

    public synchronized boolean isRunning() {
        return running && track != null
                && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING;
    }
}
