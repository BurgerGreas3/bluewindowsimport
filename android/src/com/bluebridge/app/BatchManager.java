package com.bluebridge.app;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class BatchManager {
    private static final String TAG = "BatchManager";
    private final Context context;
    private final OutputStream btOut;

    private String currentBatchId;
    private File currentBatchDir;
    private int totalItems;
    private int completedItems;

    private short currentItemIndex;
    private byte currentItemType;
    private String currentItemName;
    private int currentItemExpectedSize;
    private int currentItemBytesWritten;
    private File currentItemFile;
    private FileOutputStream currentItemFos;

    /** Whole-reset + pause controls (Chrome [ and / keys via Mac). */
    public static volatile boolean CANCEL_ALL = false;
    public static volatile boolean PAUSED = false;

    public interface BatchListener {
        void onBatchProgress(String batchId, int itemIndex, int totalItems, String itemName, int percent);
        void onBatchComplete(String batchId, int totalItems, String path);
    }

    private static BatchListener listener;

    public static void setListener(BatchListener l) {
        listener = l;
    }

    public BatchManager(Context context, OutputStream btOut) {
        this.context = context;
        this.btOut = btOut;
    }

    /** Whole reset: stop thinking, wipe current batch folder to 0, ready for next. */
    public synchronized void handleCancel() throws IOException {
        CANCEL_ALL = true;
        try {
            AiSolver.cancelCurrentExecution(currentBatchId);
        } catch (Exception ignored) {}
        BridgeService.releaseCpu(); // idle again: drop CPU+WiFi locks
        closeCurrentItem();
        if (currentBatchDir != null && currentBatchDir.exists()) {
            File[] fs = currentBatchDir.listFiles();
            if (fs != null) {
                for (File f : fs) deleteQuiet(f);
            }
        } else {
            // Fresh-connection cancel (its own BT session, no batch bound):
            // zero ALL batch folders so no stale state survives a reset.
            File baseDir = context.getExternalFilesDir("batches");
            if (baseDir == null) baseDir = new File(context.getFilesDir(), "batches");
            File[] dirs = baseDir != null ? baseDir.listFiles() : null;
            if (dirs != null) {
                for (File d : dirs) deleteQuiet(d);
            }
        }
        completedItems = 0;
        Log.d(TAG, "CANCELLED: solver stopped, batch folder reset to 0.");
        sendAck((short) 0, (byte) 0, "CANCELLED");
    }

    /** Pause toggle from Mac (Chrome / key). While paused, new batches are refused. */
    public synchronized void handlePause(byte[] payload) throws IOException {
        String s = "";
        try { s = new String(payload, StandardCharsets.UTF_8).trim(); } catch (Exception ignored) {}
        if ("1".equals(s)) {
            PAUSED = true;
            handleCancel(); // stop anything in flight, then stay paused
            Log.d(TAG, "PAUSED by Mac.");
        } else {
            PAUSED = false;
            CANCEL_ALL = false;
            Log.d(TAG, "RESUMED by Mac.");
            sendAck((short) 0, (byte) 0, "RESUMED");
        }
    }

    private static void deleteQuiet(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteQuiet(k);
        }
        f.delete();
    }

    public synchronized void handleBatchStart(byte[] payload) throws IOException {
        if (PAUSED) {
            sendAck((short) 0, (byte) 1, "PAUSED");
            return;
        }
        CANCEL_ALL = false; // fresh batch = fresh run
        BridgeService.holdCpu(); // solving starts soon; CPU+WiFi held until solved/cancelled
        // A new batch supersedes any still-solving previous one (e.g. Mac resent
        // after a BT drop): kill the orphaned pool or two solvers race on btOut + quota.
        try {
            if (AiSolver.currentExec != null) AiSolver.currentExec.shutdownNow();
        } catch (Exception ignored) {}
        AiSolver.clearAttemptLog();
        closeCurrentItem();
        if (payload == null || payload.length < 4 || payload.length > 512) {
            throw new IOException("Malformed BATCH_START (len=" + (payload == null ? -1 : payload.length) + ")");
        }
        ByteBuffer bb = ByteBuffer.wrap(payload);
        totalItems = bb.getShort() & 0xFFFF;
        int idLen = bb.getShort() & 0xFFFF;
        if (idLen < 0 || idLen > 128 || 4 + idLen > payload.length) {
            throw new IOException("Malformed BATCH_START idLen=" + idLen);
        }
        byte[] idBytes = new byte[idLen];
        bb.get(idBytes);
        currentBatchId = new String(idBytes, StandardCharsets.UTF_8);

        if (currentBatchId.isEmpty()) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
            currentBatchId = "batch_" + sdf.format(new Date());
        }

        File baseDir = context.getExternalFilesDir("batches");
        if (baseDir == null) {
            baseDir = new File(context.getFilesDir(), "batches");
        }
        currentBatchDir = new File(baseDir, currentBatchId);
        currentBatchDir.mkdirs();

        // Clean any leftover .tmp files from aborted transfers
        File[] oldFiles = currentBatchDir.listFiles();
        if (oldFiles != null) {
            for (File f : oldFiles) {
                if (f.getName().endsWith(".tmp")) {
                    f.delete();
                }
            }
        }

        completedItems = 0;
        Log.d(TAG, "Batch START: " + currentBatchId + " (" + totalItems + " items expected) -> " + currentBatchDir.getAbsolutePath());
        notifyProgress(0, "Starting...", 0);

        // Send ACK for batch start
        sendAck((short) 0, (byte) 0, "BATCH_STARTED");
    }

    public synchronized void handleItemStart(short streamId, byte[] payload) throws IOException {
        closeCurrentItem();
        if (payload == null || payload.length < 7 || payload.length > 1024) {
            throw new IOException("Malformed ITEM_START");
        }
        currentItemIndex = streamId; // 1..30
        ByteBuffer bb = ByteBuffer.wrap(payload);
        currentItemType = bb.get(); // 1 = text, 2 = image
        currentItemExpectedSize = bb.getInt();
        if (currentItemExpectedSize < 0 || currentItemExpectedSize > 50 * 1024 * 1024) {
            throw new IOException("Reject ITEM_START size=" + currentItemExpectedSize);
        }
        int nameLen = bb.getShort() & 0xFFFF;
        if (nameLen < 0 || nameLen > 512 || 7 + nameLen > payload.length) {
            throw new IOException("Malformed ITEM_START nameLen=" + nameLen);
        }
        byte[] nameBytes = new byte[nameLen];
        bb.get(nameBytes);
        currentItemName = new String(nameBytes, StandardCharsets.UTF_8);

        if (currentBatchDir == null) {
            File baseDir = context.getExternalFilesDir("batches");
            currentBatchDir = new File(baseDir, "batch_default");
            currentBatchDir.mkdirs();
        }

        String finalName;
        if (currentItemName.equalsIgnoreCase("auth.json") || currentItemName.equalsIgnoreCase("keys.json") || currentItemName.matches("^\\d+_.*")) {
            finalName = currentItemName;
        } else {
            String prefix = String.format(Locale.US, "%02d_", currentItemIndex);
            finalName = prefix + currentItemName;
        }

        // Critical fix: sanitize network-supplied filename (path traversal guard).
        // Keeps only the last path segment so "../" or absolute paths from Bluetooth
        // can never escape the batch directory.
        finalName = new File(finalName).getName().replace("..", "").trim();
        if (finalName.isEmpty()) {
            finalName = String.format(Locale.US, "%02d_item.bin", currentItemIndex);
        }

        currentItemFile = new File(currentBatchDir, finalName);
        // Atomic staging: write to .tmp first to prevent corrupted reads
        File tempFile = new File(currentBatchDir, finalName + ".tmp");
        currentItemFos = new FileOutputStream(tempFile);
        currentItemBytesWritten = 0;

        Log.d(TAG, String.format(Locale.US, "Item %d START: %s (Type %d, %d bytes)",
                currentItemIndex, currentItemName, currentItemType, currentItemExpectedSize));

        notifyProgress(currentItemIndex, currentItemName, 0);
    }

    public synchronized void handleChunkData(short streamId, byte[] payload) throws IOException {
        if (currentItemFos != null && streamId == currentItemIndex) {
            currentItemFos.write(payload);
            currentItemBytesWritten += payload.length;

            int percent = currentItemExpectedSize > 0 ?
                    (int) ((currentItemBytesWritten * 100L) / currentItemExpectedSize) : 0;
            notifyProgress(currentItemIndex, currentItemName, percent);
        }
    }

    public synchronized void handleItemEnd(short streamId) throws IOException {
        if (streamId == currentItemIndex) {
            closeCurrentItem();

            // Verify byte count BEFORE accepting (catches truncation/corruption).
            if (currentItemBytesWritten != currentItemExpectedSize) {
                Log.w(TAG, "Item " + currentItemIndex + " size mismatch: expected "
                        + currentItemExpectedSize + " got " + currentItemBytesWritten + ". Discarding.");
                if (currentItemFile != null) {
                    new File(currentBatchDir, currentItemFile.getName() + ".tmp").delete();
                }
                sendAck(currentItemIndex, (byte) 1, "SIZE_MISMATCH");
                return;
            }

            // Atomic rename from .tmp to final filename
            boolean ok = false;
            if (currentItemFile != null) {
                File tempFile = new File(currentBatchDir, currentItemFile.getName() + ".tmp");
                if (tempFile.exists()) {
                    if (currentItemFile.exists()) {
                        currentItemFile.delete();
                    }
                    ok = tempFile.renameTo(currentItemFile)
                            && currentItemFile.exists()
                            && currentItemFile.length() == currentItemExpectedSize;
                    if (!ok) {
                        Log.w(TAG, "Failed verifying " + currentItemFile.getName());
                        tempFile.delete();
                    }
                }
            }
            if (!ok) {
                sendAck(currentItemIndex, (byte) 1, "SAVE_FAILED");
                return;
            }

            completedItems++;
            Log.d(TAG, String.format(Locale.US, "Item %d END: %s (Saved %d bytes)",
                    currentItemIndex, currentItemName, currentItemBytesWritten));

            // Send ACK for item
            sendAck(currentItemIndex, (byte) 0, "ITEM_OK");
            notifyProgress(currentItemIndex, currentItemName, 100);
        }
    }

    /** Fetch mode: Mac requests stored answers.json for a batch ID (recovery path). */
    public synchronized void handleFetch(byte[] payload) throws IOException {
        String bid = "";
        try {
            bid = new String(payload, StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {}
        // Sanitize: batch dirs only, no traversal.
        bid = new File(bid).getName().replace("..", "").trim();
        File baseDir = context.getExternalFilesDir("batches");
        if (baseDir == null) baseDir = new File(context.getFilesDir(), "batches");
        File ansFile = new File(new File(baseDir, bid), "answers.json");
        if (!ansFile.exists() || ansFile.length() == 0) {
            sendAck((short) 0, (byte) 1, "NO_ANSWERS:" + bid);
            return;
        }
        sendAck((short) 0, (byte) 0, "FETCH_OK");
        try {
            byte[] ansBytes = new byte[(int) ansFile.length()];
            try (java.io.FileInputStream fis = new java.io.FileInputStream(ansFile)) {
                int off = 0;
                while (off < ansBytes.length) {
                    int r = fis.read(ansBytes, off, ansBytes.length - off);
                    if (r < 0) break;
                    off += r;
                }
            }
            MuxProtocol.sendChunkedAnswers(btOut, ansBytes);
            Log.d(TAG, "Fetched " + bid + " answers to Mac (" + ansBytes.length + " bytes).");
        } catch (Exception e) {
            Log.e(TAG, "Fetch failed for " + bid + ": " + e.getMessage());
        }
    }

    public synchronized void handleBatchEnd(short streamId) throws IOException {
        closeCurrentItem();
        if (CANCEL_ALL) {
            Log.d(TAG, "Batch END ignored: cancel flag set.");
            sendAck((short) 0, (byte) 1, "CANCELLED");
            return;
        }
        Log.d(TAG, "Batch END: " + currentBatchId + " (" + completedItems + " items saved)");
        sendAck((short) 0, (byte) 0, "BATCH_COMPLETE");

        if (listener != null && currentBatchDir != null) {
            listener.onBatchComplete(currentBatchId, completedItems, currentBatchDir.getAbsolutePath());
        }

        // Trigger on-phone AI solver directly
        if (currentBatchDir != null) {
            final String bId = currentBatchId;
            final File bDir = currentBatchDir;
            AiSolver.solveBatchAsync(context, bDir, bId, btOut, new AiSolver.SolveListener() {
                @Override
                public void onBatchProgress(String batchId, int solved, int total, String status) {
                    Log.d(TAG, "AI Progress: " + status);
                    if (listener != null) {
                        int pct = total > 0 ? (solved * 100 / total) : 0;
                        listener.onBatchProgress(batchId, solved, total, status, pct);
                    }
                }

                @Override
                public void onQuestionSolved(int num, String answer, String reason) {
                    Log.d(TAG, "Phone solved Q" + num + ": " + answer);
                    if (listener != null) {
                        listener.onBatchProgress(bId, num, totalItems, "Q" + num + " -> " + answer + " (" + reason + ")", 100);
                    }
                }

                @Override
                public void onBatchSolved(String solvedId, String answersJson) {
                    Log.d(TAG, "Phone batch " + solvedId + " complete. Transmitted answers to Mac.");
                    BridgeService.releaseCpu();
                    if (listener != null) {
                        listener.onBatchComplete(solvedId, completedItems, "SOLVED: " + answersJson);
                    }
                }

                @Override
                public void onError(String errId, String message) {
                    Log.e(TAG, "Phone solve error for batch " + errId + ": " + message);
                    BridgeService.releaseCpu();
                    if (listener != null) {
                        listener.onBatchProgress(errId, 0, totalItems, "ERROR: " + message, 0);
                    }
                }
            });
        }
    }

    private void closeCurrentItem() {
        if (currentItemFos != null) {
            try {
                currentItemFos.flush();
                currentItemFos.close();
            } catch (IOException ignored) {}
            currentItemFos = null;
        }
    }

    private void sendAck(short id, byte status, String msg) {
        try {
            byte[] msgBytes = msg.getBytes(StandardCharsets.UTF_8);
            byte[] ackPayload = new byte[1 + msgBytes.length];
            ackPayload[0] = status; // 0 = OK, 1 = Error
            System.arraycopy(msgBytes, 0, ackPayload, 1, msgBytes.length);
            MuxProtocol.writeFrame(btOut, id, MuxProtocol.CMD_ACK, ackPayload);
        } catch (IOException e) {
            Log.e(TAG, "Failed to send ACK: " + e.getMessage());
        }
    }

    private void notifyProgress(int index, String name, int percent) {
        if (listener != null && currentBatchId != null) {
            listener.onBatchProgress(currentBatchId, index, totalItems, name, percent);
        }
    }

    public void cleanup() {
        closeCurrentItem();
    }
}
