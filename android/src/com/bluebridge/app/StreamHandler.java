package com.bluebridge.app;

import android.util.Log;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

public class StreamHandler {
    private static final String TAG = "StreamHandler";
    private final short streamId;
    private final String host;
    private final int port;
    private final OutputStream btOut;
    private final BridgeService service;
    private Socket socket;
    private final AtomicBoolean isClosed = new AtomicBoolean(false);

    public StreamHandler(short streamId, String host, int port, OutputStream btOut, BridgeService service) {
        this.streamId = streamId;
        this.host = host;
        this.port = port;
        this.btOut = btOut;
        this.service = service;
    }

    public void start() {
        new Thread(() -> {
            try {
                Log.d(TAG, "[" + streamId + "] Connecting to " + host + ":" + port);
                socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), 10000);
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(120000); // dead peer must fail the read, not hang the slot forever

                // Notify Mac that connection succeeded
                MuxProtocol.writeFrame(btOut, streamId, MuxProtocol.CMD_CONNECTED, null);
                Log.d(TAG, "[" + streamId + "] Connected successfully to " + host + ":" + port);

                // Loop reading from target socket and forwarding to Bluetooth
                InputStream in = socket.getInputStream();
                byte[] buf = new byte[8192];
                int n;
                while (!isClosed.get() && (n = in.read(buf)) != -1) {
                    byte[] payload = new byte[n];
                    System.arraycopy(buf, 0, payload, 0, n);
                    MuxProtocol.writeFrame(btOut, streamId, MuxProtocol.CMD_DATA, payload);
                    service.addBytes(n);
                }
            } catch (Exception e) {
                Log.e(TAG, "[" + streamId + "] Error: " + e.getMessage());
                try {
                    byte[] errBytes = (e.getMessage() != null ? e.getMessage() : "Error").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    MuxProtocol.writeFrame(btOut, streamId, MuxProtocol.CMD_ERROR, errBytes);
                } catch (Exception ignored) {}
            } finally {
                close();
            }
        }).start();
    }

    public void writeData(byte[] data) {
        if (isClosed.get() || socket == null || socket.isClosed()) return;
        try {
            socket.getOutputStream().write(data);
            socket.getOutputStream().flush();
            service.addBytes(data.length);
        } catch (IOException e) {
            Log.e(TAG, "[" + streamId + "] Write error: " + e.getMessage());
            close();
        }
    }

    public void close() {
        if (isClosed.compareAndSet(false, true)) {
            Log.d(TAG, "[" + streamId + "] Closing stream");
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException ignored) {}
            try {
                MuxProtocol.writeFrame(btOut, streamId, MuxProtocol.CMD_CLOSE, null);
            } catch (IOException ignored) {}
            service.removeStream(streamId);
        }
    }
}
