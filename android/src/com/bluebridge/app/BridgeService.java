package com.bluebridge.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import java.io.DataInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.UUID;
import android.os.PowerManager;
import android.net.wifi.WifiManager;
import android.content.Context;
import android.content.pm.ServiceInfo;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class BridgeService extends Service {
    private static final String TAG = "BridgeService";
    private static final String CHANNEL_ID = "bluebridge_channel";
    // Custom 128-bit UUID (NOT the reserved 0x1101 SPP UUID): Android only publishes
    // an SDP record with our "BlueBridge" name for non-reserved UUIDs. The Mac
    // discovers the channel by name, so the value only needs to match nothing
    // except itself here.
    public static final UUID SPP_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final String RFCOMM_SERVICE_NAME = "BlueBridge";

    private static PowerManager.WakeLock wakeLock;
    private SilentAudioLock audioLock; // freezer shield: lives with the service, not the CPU lock
    private static WifiManager.WifiLock wifiLock;

    /** On-demand CPU: held only while actively solving/sending, not idle for hours. */
    public static void holdCpu() {
        try {
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
        } catch (Exception ignored) {}
        try {
            if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
        } catch (Exception ignored) {}
    }

    public static void releaseCpu() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) {}
    }

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final ConcurrentHashMap<Short, StreamHandler> activeStreams = new ConcurrentHashMap<>();
    private final AtomicLong totalBytes = new AtomicLong(0);

    private BluetoothServerSocket serverSocket;
    private BluetoothSocket activeSocket;
    private Thread serverThread;
    private BatchManager batchManager;

    public interface StatusListener {
        void onStatusChanged(String status, String clientName, int activeSockets, long bytes);
    }

    private static StatusListener listener;

    public static void setStatusListener(StatusListener l) {
        listener = l;
    }

    public static boolean isServiceRunning = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                // Created here, acquired on demand (holdCpu) while solving only.
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Blue:BridgeWakeLock");
                wakeLock.setReferenceCounted(false);
            }
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Blue:WifiLock");
                wifiLock.setReferenceCounted(false);
            }
        } catch (Exception ignored) {}
        try {
            audioLock = new SilentAudioLock();
            audioLock.start();
        } catch (Exception ignored2) {}
        try {
            // Diagnostics for the freezer investigation: exact-alarm availability
            // governs the swipe-kill restart path (recovery, not exemption).
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                android.app.AlarmManager am =
                        (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
                Log.i(TAG, "diag exactAlarmAllowed=" + (am != null && am.canScheduleExactAlarms())
                        + " sdk=" + android.os.Build.VERSION.SDK_INT);
            }
        } catch (Exception ignored2) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isRunning.get()) {
            isRunning.set(true);
            isServiceRunning = true;
            Notification notif = createNotification("Blue active — Bluetooth listener running");
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                } else {
                    startForeground(1, notif);
                }
            } catch (Exception e) {
                // Background FGS-start ban (Android 12+): retry shortly; alarm path re-fires us.
                Log.w(TAG, "startForeground blocked, will retry: " + e.getMessage());
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                        } else {
                            startForeground(1, notif);
                        }
                    } catch (Exception ignored) {}
                }, 30000);
            }
            startServer();
            startHeartbeat();
        }
        // START_STICKY: Android will restart this service if killed, preserving intent
        return START_STICKY;
    }

    /** 60s proof-of-life log, visible in logcat while the screen is dark. */
    private void startHeartbeat() {
        new Thread(() -> {
            while (isRunning.get()) {
                try { Thread.sleep(60000); } catch (InterruptedException ignored) {}
                if (!isRunning.get()) break;
                try {
                    boolean wl = wakeLock != null && wakeLock.isHeld();
                    BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
                    Log.d(TAG, "heartbeat: alive wakeLock=" + wl
                            + " bt=" + (a != null && a.isEnabled()));
                    // Self-test coverage: while a test is armed, record CPU beats.
                    // A freeze gap (no beats across the locked window) = FAIL;
                    // steady beats = PASS. Needs no Mac traffic to judge.
                    try {
                        android.content.SharedPreferences hp =
                                getSharedPreferences("blue_health", MODE_PRIVATE);
                        if (hp.getLong("selftest_start", 0) > 0) {
                            String prev = hp.getString("selftest_beats", "");
                            java.util.ArrayList<String> parts = new java.util.ArrayList<>();
                            if (prev != null && !prev.isEmpty()) {
                                for (String p : prev.split(",")) {
                                    if (!p.trim().isEmpty()) parts.add(p.trim());
                                }
                            }
                            parts.add(String.valueOf(System.currentTimeMillis()));
                            while (parts.size() > 8) parts.remove(0);
                            StringBuilder sbb = new StringBuilder();
                            for (int i = 0; i < parts.size(); i++) {
                                if (i > 0) sbb.append(',');
                                sbb.append(parts.get(i));
                            }
                            hp.edit().putString("selftest_beats", sbb.toString()).apply();
                        }
                    } catch (Exception ignored2) {}
                } catch (Exception ignored) {}
            }
        }).start();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Swipe-kill restart. setAlarmClock (not set()) is EXEMPT from the Android 12+
        // background foreground-service start ban — plain set() + getService crashes
        // with ForegroundServiceStartNotAllowedException and the service never returns.
        try {
            Intent restartServiceIntent = new Intent(getApplicationContext(), BridgeService.class);
            restartServiceIntent.setPackage(getPackageName());
            android.app.PendingIntent restartPI = android.app.PendingIntent.getForegroundService(
                getApplicationContext(), 1, restartServiceIntent,
                android.app.PendingIntent.FLAG_ONE_SHOT | android.app.PendingIntent.FLAG_IMMUTABLE);
            android.app.AlarmManager am =
                (android.app.AlarmManager) getApplicationContext().getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                android.app.AlarmManager.AlarmClockInfo info =
                        new android.app.AlarmManager.AlarmClockInfo(
                                System.currentTimeMillis() + 500, restartPI);
                am.setAlarmClock(info, restartPI);
                Log.d(TAG, "Restart scheduled via setAlarmClock.");
            }
        } catch (Exception e) {
            Log.w(TAG, "Restart schedule failed: " + e.getMessage());
        }
        super.onTaskRemoved(rootIntent);
    }

    /**
     * Primary: fixed channel 25 via hidden API (proven working on Oppo CPH2631;
     * the Mac tries 25 first, so screen-off wakeups reconnect in ~1s with no
     * SDP round-trip). Fallback: public dynamic API ("BlueBridge" SDP record)
     * for ROMs where the hidden API is blocked — the Mac discovers that one.
     */
    private BluetoothServerSocket createServerSocket(BluetoothAdapter adapter) {
        try {
            java.lang.reflect.Method m = adapter.getClass().getMethod(
                    "listenUsingRfcommOn", int.class, boolean.class, boolean.class);
            m.setAccessible(true);
            BluetoothServerSocket s = (BluetoothServerSocket) m.invoke(adapter, 25, false, false);
            if (s != null) {
                Log.d(TAG, "Bound fixed RFCOMM Channel 25 via hidden API");
                return s;
            }
        } catch (Throwable t) {
            Log.w(TAG, "fixed-25 (3-arg) unavailable: " + t.getMessage());
        }
        try {
            java.lang.reflect.Method m = adapter.getClass().getMethod("listenUsingRfcommOn", int.class);
            m.setAccessible(true);
            BluetoothServerSocket s = (BluetoothServerSocket) m.invoke(adapter, 25);
            if (s != null) {
                Log.d(TAG, "Bound fixed RFCOMM Channel 25 via hidden API (1-arg)");
                return s;
            }
        } catch (Throwable t) {
            Log.w(TAG, "fixed-25 (1-arg) unavailable: " + t.getMessage());
        }
        try {
            return adapter.listenUsingRfcommWithServiceRecord(RFCOMM_SERVICE_NAME, SPP_UUID);
        } catch (Exception e) {
            Log.w(TAG, "Secure RFCOMM listener failed; trying insecure SPP", e);
            try {
                return adapter.listenUsingInsecureRfcommWithServiceRecord(RFCOMM_SERVICE_NAME, SPP_UUID);
            } catch (Exception ex) {
                Log.e(TAG, "All server-socket methods failed");
                return null;
            }
        }
    }

    private void startServer() {
        serverThread = new Thread(() -> {
            while (isRunning.get()) {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null || !adapter.isEnabled()) {
                    updateUI("Bluetooth disabled", "None");
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                    continue;
                }

                BluetoothServerSocket bss = null;
                try {
                    bss = createServerSocket(adapter);
                    if (bss == null) {
                        throw new java.io.IOException("Failed to create BluetoothServerSocket");
                    }

                    serverSocket = bss;
                    updateUI("Listening on Bluetooth RFCOMM...", "None");
                    Log.d(TAG, "RFCOMM server socket opened; service published through SDP");

                    while (isRunning.get()) {
                        try {
                            BluetoothSocket socket = serverSocket.accept();
                            if (socket != null) {
                                activeSocket = socket;
                                String clientName = socket.getRemoteDevice().getName();
                                 Log.d(TAG, "Client connected: " + clientName + " [" + socket.getRemoteDevice().getAddress() + "]");
                                 touchContact();
                                 updateUI("Connected to Mac", clientName);
                                try {
                                    handleClientSession(socket);
                                } finally {
                                    try { socket.close(); } catch (Exception ignored) {}
                                    activeSocket = null;
                                }
                                updateUI("Listening on Bluetooth RFCOMM...", "None");
                                // Persistent server socket: the SAME socket accepts the
                                // next client (ping -> batch send back-to-back). Rebinding
                                // here opened a ~1s window where sends failed after pings.
                            }
                        } catch (Exception e) {
                            if (isRunning.get()) {
                                Log.w(TAG, "Socket accept loop interrupted: " + e.getMessage());
                                break; // break inner loop to re-create clean server socket
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Server socket error: " + e.getMessage());
                    updateUI("Rebinding RFCOMM...", "None");
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                } finally {
                    // Screen-off proof heartbeat (visible in logcat while display is dark).
                    try {
                        boolean wl = wakeLock != null && wakeLock.isHeld();
                        Log.d(TAG, "heartbeat: alive, wakeLock=" + wl
                                + ", bt=" + BluetoothAdapter.getDefaultAdapter().isEnabled());
                    } catch (Exception ignored) {}
                    if (bss != null) {
                        try { bss.close(); } catch (Exception ignored) {}
                    }
                }
            }
        });
        serverThread.start();
    }

    private void handleClientSession(BluetoothSocket socket) {
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            DataInputStream dis = new DataInputStream(in);
            batchManager = new BatchManager(this, out);

            while (isRunning.get() && socket.isConnected()) {
                MuxProtocol.Frame frame = MuxProtocol.readFrame(dis);
                if (frame == null) break;

                addBytes(frame.payload.length + 7);
                touchContact();

                switch (frame.cmd) {
                    // Batch Transfer Commands
                    case MuxProtocol.CMD_BATCH_START:
                        batchManager.handleBatchStart(frame.payload);
                        break;
                    case MuxProtocol.CMD_ITEM_START:
                        batchManager.handleItemStart(frame.streamId, frame.payload);
                        break;
                    case MuxProtocol.CMD_CHUNK_DATA:
                        batchManager.handleChunkData(frame.streamId, frame.payload);
                        break;
                    case MuxProtocol.CMD_ITEM_END:
                        batchManager.handleItemEnd(frame.streamId);
                        break;
                    case MuxProtocol.CMD_BATCH_END:
                        batchManager.handleBatchEnd(frame.streamId);
                        break;
                    case MuxProtocol.CMD_FETCH:
                        batchManager.handleFetch(frame.payload);
                        break;
                    case MuxProtocol.CMD_CHAT: {
                        // Chatbot: isolated from batch flow, own solver, own reply frame.
                        // ACK first (Mac verifies the handshake before waiting for the answer).
                        try {
                            MuxProtocol.writeFrame(out, (short) 0, MuxProtocol.CMD_ACK, new byte[]{0});
                        } catch (Exception ignored) {}
                        try {
                            org.json.JSONObject req = new org.json.JSONObject(
                                    new String(frame.payload, "UTF-8"));
                            String q = req.optString("q", "");
                            if (BatchManager.PAUSED) {
                                // Full-stop means full-stop: never solve while paused.
                                org.json.JSONObject pausedAns = new org.json.JSONObject();
                                pausedAns.put("question", q);
                                pausedAns.put("answer", "-");
                                pausedAns.put("model", "paused");
                                byte[] pb = pausedAns.toString().getBytes(StandardCharsets.UTF_8);
                                MuxProtocol.writeFrame(out, (short) 0, MuxProtocol.CMD_CHAT_ANS, pb);
                            } else if (!q.isEmpty()) {
                                ChatSolver.solveChatAsync(out, q,
                                        req.optString("or_key", ""), req.optString("cb_key", ""));
                            } else {
                                // Empty/bad question: answer "-" now so Mac never waits out the timeout.
                                org.json.JSONObject emptyAns = new org.json.JSONObject();
                                emptyAns.put("question", "");
                                emptyAns.put("answer", "-");
                                emptyAns.put("model", "empty");
                                byte[] eb = emptyAns.toString().getBytes(StandardCharsets.UTF_8);
                                MuxProtocol.writeFrame(out, (short) 0, MuxProtocol.CMD_CHAT_ANS, eb);
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Bad CHAT frame: " + e.getMessage());
                        }
                        break;
                    }
                    case MuxProtocol.CMD_CANCEL:
                        batchManager.handleCancel();
                        break;
                    case MuxProtocol.CMD_PAUSE:
                        batchManager.handlePause(frame.payload);
                        break;
                    case MuxProtocol.CMD_RESOLVE_ONE: {
                        // Single-question re-solve ("0" key). ACK first, then re-solve.
                        try {
                            MuxProtocol.writeFrame(out, (short) 0, MuxProtocol.CMD_ACK, new byte[]{0});
                        } catch (Exception ignored) {}
                        try {
                            org.json.JSONObject req = new org.json.JSONObject(new String(frame.payload, "UTF-8"));
                            int qNum = req.optInt("num", -1);
                            String batchId = req.optString("batch_id", "");
                            if (qNum > 0 && !batchId.isEmpty()) {
                                // Match BatchManager's dir: getExternalFilesDir("batches")/<batchId>
                                File baseDir = getExternalFilesDir("batches");
                                if (baseDir == null) baseDir = new File(getFilesDir(), "batches");
                                File batchDir = new File(baseDir, batchId);
                                if (batchDir.exists()) {
                                    final File finalBatchDir = batchDir;
                                    final int finalQNum = qNum;
                                    final String finalBatchId = batchId;
                                    final OutputStream finalOut = out;
                                    AiSolver.solveOneQuestion(this, finalBatchDir, finalBatchId,
                                            finalQNum, finalOut, null);
                                } else {
                                    Log.w(TAG, "CMD_RESOLVE_ONE: batch dir not found for " + batchId);
                                }
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Bad RESOLVE_ONE frame: " + e.getMessage());
                        }
                        break;
                    }
                    case MuxProtocol.CMD_PING: {
                        byte[] pong = "PONG".getBytes(StandardCharsets.UTF_8);
                        byte[] payload = new byte[1 + pong.length];
                        payload[0] = 0; // status 0 = OK
                        System.arraycopy(pong, 0, payload, 1, pong.length);
                        MuxProtocol.writeFrame(out, (short) 0, MuxProtocol.CMD_ACK, payload);
                        break;
                    }

                    // SOCKS Proxy Commands (Legacy Support)
                    case MuxProtocol.CMD_CONNECT: {
                        if (frame.payload.length >= 4) {
                            ByteBuffer bb = ByteBuffer.wrap(frame.payload);
                            int port = bb.getShort() & 0xFFFF;
                            int hostLen = bb.getShort() & 0xFFFF;
                            byte[] hostBytes = new byte[hostLen];
                            bb.get(hostBytes);
                            String host = new String(hostBytes, "UTF-8");

                            StreamHandler handler = new StreamHandler(frame.streamId, host, port, out, this);
                            activeStreams.put(frame.streamId, handler);
                            handler.start();
                            notifyUpdate();
                        }
                        break;
                    }
                    case MuxProtocol.CMD_DATA: {
                        StreamHandler handler = activeStreams.get(frame.streamId);
                        if (handler != null) {
                            handler.writeData(frame.payload);
                        }
                        break;
                    }
                    case MuxProtocol.CMD_CLOSE: {
                        StreamHandler handler = activeStreams.remove(frame.streamId);
                        if (handler != null) {
                            handler.close();
                            notifyUpdate();
                        }
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Client session ended: " + e.getMessage());
        } finally {
            if (batchManager != null) {
                batchManager.cleanup();
            }
            closeAllStreams();
            updateUI("Listening on Bluetooth RFCOMM...", "None");
        }
    }

    public void removeStream(short streamId) {
        activeStreams.remove(streamId);
        notifyUpdate();
    }

    private long lastUiUpdateTime = 0;
    private long lastContactWrite = 0;

    /** Last-Mac-contact heartbeat for the in-app health card (screen-off diagnosis). */
    private void touchContact() {
        long now = System.currentTimeMillis();
        if (now - lastContactWrite > 5000) {
            lastContactWrite = now;
            try {
                getSharedPreferences("blue_health", MODE_PRIVATE)
                        .edit().putLong("last_bt_contact", now).apply();
            } catch (Exception ignored) {}
        }
    }

    public void addBytes(int n) {
        totalBytes.addAndGet(n);
        long now = System.currentTimeMillis();
        // Throttle UI notification to at most once per 250ms to prevent UI looper lockups
        if (now - lastUiUpdateTime > 250) {
            lastUiUpdateTime = now;
            notifyUpdate();
        }
    }

    private void notifyUpdate() {
        if (listener != null) {
            String client = activeSocket != null && activeSocket.isConnected() ? 
                            activeSocket.getRemoteDevice().getName() : "None";
            String status = activeSocket != null && activeSocket.isConnected() ? 
                            "Connected to Mac" : "Listening on Bluetooth RFCOMM...";
            listener.onStatusChanged(status, client, activeStreams.size(), totalBytes.get());
        }
    }

    private void updateUI(String status, String client) {
        if (listener != null) {
            listener.onStatusChanged(status, client, activeStreams.size(), totalBytes.get());
        }
    }

    private void closeAllStreams() {
        for (StreamHandler h : activeStreams.values()) {
            h.close();
        }
        activeStreams.clear();
    }

    private void stopServer() {
        isRunning.set(false);
        isServiceRunning = false;
        closeAllStreams();
        try {
            if (activeSocket != null) activeSocket.close();
        } catch (Exception ignored) {}
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (audioLock != null) audioLock.stop();
        } catch (Exception ignored) {}
        stopServer();
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Exception ignored) {}
        updateUI("Service Stopped", "None");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "BlueBridge Tethering Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Maintains Bluetooth connection");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification(String text) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
            new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);

        return builder
            .setContentTitle("BlueBridge Active")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }
}
