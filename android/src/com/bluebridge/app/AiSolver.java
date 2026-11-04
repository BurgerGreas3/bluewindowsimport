package com.bluebridge.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AiSolver {
    private static final String TAG = "AiSolver";
    // 3 chunk-count modes: 1 call (all at once), 5 calls, 10 calls.
    public static final int MODE_1_CALL = 0;
    public static final int MODE_5_CALL = 1;
    public static final int MODE_10_CALL = 2;
    // Matrix-verified 2026-09-20 on 10 keys: 3.8 8/10 healthy, 3.6 7/7, lite 7/7.
    public static final String[] MODEL_SEQUENCE = new String[]{
        "gemini-3.8-flash", "gemini-3.8-flash",
        "gemini-3.7-flash", "gemini-3.7-flash",
        "gemini-3.6-flash", "gemini-3.6-flash",
        "gemini-3.5-flash", "gemini-3.5-flash",
        "gemini-3.5-flash-lite", "gemini-3.5-flash-lite"
    };
    // Live attempt log for the transparency card (bounded, newest last).
    private static final java.util.concurrent.ConcurrentLinkedQueue<String> attemptLog =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    // Current solver pool for whole-reset ([ key) shutdown.
    public static volatile java.util.concurrent.ExecutorService currentExec = null;
    public static volatile String currentRunningBatchId = null;

    public static synchronized void cancelCurrentExecution(String batchId) {
        if (batchId == null || batchId.equals(currentRunningBatchId)) {
            if (currentExec != null) {
                try { currentExec.shutdownNow(); } catch (Exception ignored) {}
                currentExec = null;
            }
            currentRunningBatchId = null;
        }
    }

    /** OOM retry specs: chunks that died from memory pressure are re-run
     * SEQUENTIALLY (one at a time, low footprint) instead of autofilled.
     * Tagged by batch: a newer batch never wipes an older batch's pending
     * retries, and each run drains only its own. Bounded at 50 (drop oldest). */
    private static class RetrySpec {
        final String batchId;
        final List<Integer> qNums;
        final int keyIdx;
        RetrySpec(String b, List<Integer> q, int k) { batchId = b; qNums = q; keyIdx = k; }
    }
    private static final List<RetrySpec> oomQueue =
            java.util.Collections.synchronizedList(new java.util.ArrayList<RetrySpec>());

    public static java.util.List<String> getAttemptLog() {
        return new java.util.ArrayList<>(attemptLog);
    }

    public static void clearAttemptLog() {
        attemptLog.clear();
    }

    private static void logAttempt(String line) {
        attemptLog.offer(line);
        while (attemptLog.size() > 60) attemptLog.poll();
    }
    private static final String API_BASE = "https://generativelanguage.googleapis.com/v1beta/models";

    // No built-in keys by design (F25): keys always arrive per-batch via keys.json
    // from the Mac .env. An empty pool throws a clear error instead of silently
    // burning shared quota. NOTE: rotate the previously-hardcoded keys in console.

    public interface SolveListener {
        void onBatchProgress(String batchId, int solved, int total, String status);
        void onQuestionSolved(int num, String answer, String reason);
        void onBatchSolved(String batchId, String answersJson);
        void onError(String batchId, String message);
    }

    public static int getSavedMode(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences("blue_prefs", Context.MODE_PRIVATE);
        int m = prefs.getInt("solver_mode", MODE_5_CALL);
        if (m < MODE_1_CALL || m > MODE_10_CALL) m = MODE_5_CALL;
        return m;
    }

    public static String modeName(int mode) {
        if (mode == MODE_1_CALL) return "1-CALL";
        if (mode == MODE_10_CALL) return "10-CALL";
        return "5-CALL";
    }

    public static void setSavedMode(Context ctx, int mode) {
        SharedPreferences prefs = ctx.getSharedPreferences("blue_prefs", Context.MODE_PRIVATE);
        prefs.edit().putInt("solver_mode", mode).apply();
    }

    public static void solveBatchAsync(Context ctx, File batchDir, String batchId, OutputStream btOut, SolveListener listener) {
        new Thread(() -> {
            try {
                final long batchStartMs = System.currentTimeMillis();
                final long BATCH_HARD_DEADLINE_MS = 10 * 60 * 1000L; // 10 min from Ctrl+S
                int mode = getSavedMode(ctx);
                Log.d(TAG, "Starting AI solver for " + batchId + " [Mode: " + modeName(mode) + "]");

                if (listener != null) {
                    listener.onBatchProgress(batchId, 0, 0, "Analyzing batch files...");
                }

                // 1. Load keys (from keys.json or defaults)
                List<String> keyPool = new ArrayList<>();
                File keysFile = new File(batchDir, "keys.json");
                if (!keysFile.exists() || keysFile.length() < 5) {
                    File extKeys = new File(ctx.getExternalFilesDir(null), "keys.json");
                    if (extKeys.exists() && extKeys.length() >= 5) {
                        keysFile = extKeys;
                    }
                }
                if (keysFile.exists()) {
                    try {
                        String kStr = readFileToString(keysFile);
                        JSONObject kObj = new JSONObject(kStr);
                        JSONArray kArr = kObj.optJSONArray("keys");
                        if (kArr != null) {
                            for (int i = 0; i < kArr.length(); i++) {
                                keyPool.add(kArr.getString(i));
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Failed reading keys.json: " + e.getMessage());
                    }
                }
                if (keyPool.isEmpty()) {
                    // Fail fast with a clear message instead of silently burning
                    // shared hardcoded keys (see F25). Master refuses empty .env too.
                    throw new RuntimeException("No API keys: keys.json missing/empty. "
                            + "Add GEMINI_KEY_1=... to Mac .env and resend the batch.");
                }

                // 2. Discover and categorize all question files
                File[] files = batchDir.listFiles();
                if (files == null || files.length == 0) {
                    throw new RuntimeException("No files found in batch directory: " + batchDir.getAbsolutePath());
                }

                Map<Integer, File> txtMap = new HashMap<>();
                Map<Integer, File> imgMap = new HashMap<>();
                Map<Integer, File> audioMap = new HashMap<>();
                Pattern numPattern = Pattern.compile("^(\\d+)");

                for (File f : files) {
                    String name = f.getName().toLowerCase();
                    if (name.contains("keys.json") || name.contains("auth.json") || name.equals("answers.json")) continue;

                    Matcher m = numPattern.matcher(name);
                    if (m.find()) {
                        try {
                            int qNum = Integer.parseInt(m.group(1));
                            if (name.endsWith(".txt")) {
                                txtMap.put(qNum, f);
                            } else if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp")) {
                                imgMap.put(qNum, f);
                            } else if (name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".m4a") || name.endsWith(".ogg") || name.endsWith(".aac") || name.endsWith(".weba") || name.endsWith(".opus") || name.endsWith(".mid") || name.endsWith(".midi")) {
                                audioMap.put(qNum, f);
                            }
                        } catch (Exception ignored) {}
                    }
                }

                List<Integer> allQNums = new ArrayList<>();
                for (int q : txtMap.keySet()) if (!allQNums.contains(q)) allQNums.add(q);
                for (int q : imgMap.keySet()) if (!allQNums.contains(q)) allQNums.add(q);
                for (int q : audioMap.keySet()) if (!allQNums.contains(q)) allQNums.add(q);
                Collections.sort(allQNums);

                int totalQuestions = allQNums.size();
                if (totalQuestions == 0) {
                    throw new RuntimeException("No question files detected in batch.");
                }

                Log.d(TAG, "Batch " + batchId + " contains " + totalQuestions + " questions (" +
                        txtMap.size() + " text, " + imgMap.size() + " images, " + audioMap.size() + " audio).");

                JSONObject masterAnswers = new JSONObject();
                int currentKeyIndex = 0;
                // Prune OTHER batches' stale retries (never this run's — tagged below);
                // bound the queue so overlapping runs can't grow it forever.
                synchronized (oomQueue) {
                    java.util.Iterator<RetrySpec> it = oomQueue.iterator();
                    while (it.hasNext()) {
                        if (!batchId.equals(it.next().batchId)) it.remove();
                    }
                    while (oomQueue.size() > 50) oomQueue.remove(0);
                }

                // RESUME: a previous partial run's answers.json (same batch id) seeds
                // this run — after a process death + resend we continue from the
                // first unsolved question instead of burning quota re-solving all.
                java.util.Set<Integer> resumedSolved = new java.util.HashSet<>();
                try {
                    File partialFile = new File(batchDir, "answers.json");
                    if (partialFile.exists() && partialFile.length() > 2) {
                        JSONObject partial = new JSONObject(readFileToString(partialFile));
                        Iterator<String> pk = partial.keys();
                        while (pk.hasNext()) {
                            String k = pk.next();
                            if (!k.matches("^\\d{2,3}$")) continue;
                            JSONObject qObj = partial.optJSONObject(k);
                            if (qObj == null) continue;
                            if ("auto-filled".equals(qObj.optString("confidence", ""))) continue;
                            String prevAns = buildAnswerString(qObj);
                            if (prevAns == null || prevAns.equals("-") || prevAns.equals("none")) continue;
                            int qn = Integer.parseInt(k);
                            masterAnswers.put(k, qObj);
                            resumedSolved.add(qn);
                        }
                        if (!resumedSolved.isEmpty()) {
                            Log.i(TAG, "Resuming " + batchId + " with " + resumedSolved.size()
                                    + " already-solved questions, skipping re-solve.");
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Resume load failed, solving fresh: " + e.getMessage());
                }

                // Unified chunk-count modes: 1 call, 5 calls, or 10 calls.
                // Media dealt first (round-robin, spread thin), then text.
                // Gemini free tier caps TPM/payload: limit each call to at most 3 images
                // so requests never trigger HTTP 503 capacity overload.
                int mediaCount = 0;
                for (int q : allQNums) {
                    if (imgMap.containsKey(q) || audioMap.containsKey(q)) mediaCount++;
                }
                int minCallsForMedia = (mediaCount > 0) ? (int) Math.ceil((double) mediaCount / 3.0) : 1;
                int targetCalls = (mode == MODE_1_CALL) ? 1 : (mode == MODE_10_CALL ? 10 : 5);
                if (mode == MODE_1_CALL && minCallsForMedia > 1) {
                    Log.i(TAG, "Mode 1-CALL adjusted to " + minCallsForMedia + " calls because batch contains " + mediaCount + " media files (max 3/call to avoid 503 limit).");
                }
                targetCalls = Math.max(targetCalls, minCallsForMedia);
                targetCalls = Math.min(targetCalls, totalQuestions);
                if (listener != null) {
                    String modeMsg = "Mode " + modeName(mode);
                    if (mode == MODE_1_CALL && targetCalls > 1) {
                        modeMsg += " (adjusted to " + targetCalls + " calls for " + mediaCount + " media)";
                    }
                    listener.onBatchProgress(batchId, 0, totalQuestions,
                            modeMsg + ": dealing " + totalQuestions
                            + " questions into " + targetCalls + " call(s) across "
                            + keyPool.size() + " keys...");
                }
                List<List<Integer>> chunks = dealChunks(allQNums, txtMap, imgMap, audioMap, targetCalls);
                // Drop already-solved questions from chunks (resume); drop empties.
                java.util.List<java.util.List<Integer>> filtered = new java.util.ArrayList<>();
                for (java.util.List<Integer> c : chunks) {
                    java.util.List<Integer> keep = new java.util.ArrayList<>();
                    for (int q : c) if (!resumedSolved.contains(q)) keep.add(q);
                    if (!keep.isEmpty()) filtered.add(keep);
                }
                chunks = filtered;
                int totalCalls = chunks.size();
                if (totalCalls == 0 && !resumedSolved.isEmpty()) {
                    Log.i(TAG, "All questions already solved — sending stored answers.");
                }
                // Replay progress for resumed questions so Mac HUD/live-file catch up.
                if (!resumedSolved.isEmpty()) {
                    java.util.List<Integer> rq = new java.util.ArrayList<>(resumedSolved);
                    java.util.Collections.sort(rq);
                    for (int qNum : rq) {
                        String qStr = String.format(Locale.US, "%02d", qNum);
                        JSONObject qObj = masterAnswers.optJSONObject(qStr);
                        if (qObj == null) continue;
                        String rsn = qObj.optString("reason", "");
                        if (rsn == null || rsn.trim().isEmpty()) rsn = qObj.optString("analysis", "");
                        String ansStr = buildAnswerString(qObj);
                        appendSolverLog(batchDir, batchId, "Q" + qStr + " -> " + ansStr + " [RESUMED]");
                        sendProgressFrame(btOut, qNum, qObj, ansStr, rsn);
                        if (listener != null) listener.onQuestionSolved(qNum, ansStr, rsn);
                    }
                }
                Log.d(TAG, "Mode " + modeName(mode) + ": dispatching " + totalCalls
                        + " parallel chunk(s) across " + keyPool.size() + " API keys.");

                if (listener != null) {
                    listener.onBatchProgress(batchId, 0, totalQuestions,
                            "Dispatching " + totalCalls + " parallel AI workers across " + keyPool.size() + " API keys...");
                }

                if (totalCalls > 0) {
                    ExecutorService executor = Executors.newFixedThreadPool(chunks.size());
                    currentExec = executor;
                    currentRunningBatchId = batchId;
                    for (int cIdx = 0; cIdx < chunks.size(); cIdx++) {
                        final int chunkIndex = cIdx;
                        final List<Integer> chunkQNums = chunks.get(chunkIndex);
                        final int assignedKeyIndex = chunkIndex % keyPool.size();

                        executor.submit(() -> {
                            try {
                                // Option A free-tier pacing: stagger chunk starts ~1.2s apart
                                // so shared keys stay under 5 RPM free limit.
                                if (chunkIndex > 0) {
                                    try { Thread.sleep((long) chunkIndex * 1200); }
                                    catch (InterruptedException ignored2) {}
                                }
                                if (BatchManager.CANCEL_ALL) return;
                                try {
                                    solveChunkSync(batchDir, batchId, chunkQNums, assignedKeyIndex, keyPool,
                                            txtMap, imgMap, audioMap, masterAnswers, btOut, listener, totalQuestions, mode);
                                } catch (RuntimeException te) {
                                    // Truncated output -> split once and solve halves (never autofill solvable Qs)
                                    if (te.getMessage() != null && te.getMessage().contains("MAX_TOKENS")
                                            && chunkQNums.size() > 1) {
                                        Log.w(TAG, "Chunk " + chunkIndex + " truncated; splitting in half.");
                                        int mid = chunkQNums.size() / 2;
                                        solveChunkSync(batchDir, batchId, new ArrayList<>(chunkQNums.subList(0, mid)),
                                                assignedKeyIndex, keyPool, txtMap, imgMap, audioMap,
                                                masterAnswers, btOut, listener, totalQuestions, mode);
                                        solveChunkSync(batchDir, batchId, new ArrayList<>(chunkQNums.subList(mid, chunkQNums.size())),
                                                assignedKeyIndex + 1, keyPool, txtMap, imgMap, audioMap,
                                                masterAnswers, btOut, listener, totalQuestions, mode);
                                        // NOTE: split halves re-enter solveChunkSync, which recomputes
                                        // thinking per half (a split-off single row drops to LOW).
                                    } else {
                                        throw te;
                                    }
                                }
                            } catch (OutOfMemoryError oom) {
                                // Memory-pressure death (silent thread kill otherwise):
                                // queue for a sequential low-footprint retry, never autofill.
                                Log.e(TAG, "Chunk " + chunkIndex + " OOM — queued for sequential retry.");
                                synchronized (oomQueue) {
                                    if (oomQueue.size() < 50) {
                                        oomQueue.add(new RetrySpec(batchId, chunkQNums, assignedKeyIndex));
                                    }
                                }
                            } catch (Exception e) {
                                String why = (e.getMessage() != null) ? e.getMessage() : e.toString();
                                Log.e(TAG, "Chunk " + chunkIndex + " failed: " + why);
                                synchronized (masterAnswers) {
                                    try {
                                        JSONObject fc = masterAnswers.optJSONObject("_failed_chunks");
                                        if (fc == null) {
                                            fc = new JSONObject();
                                            masterAnswers.put("_failed_chunks", fc);
                                        }
                                        fc.put("chunk" + chunkIndex, chunkQNums.toString() + " :: " + why);
                                    } catch (Exception ignored3) {}
                                }
                            }
                        });
                    }

                    executor.shutdown();
                    // 300s: stagger + 90s reads + split-retries all need room (was 60s -> fake "none"s)
                    boolean poolKilled = false;
                    try {
                        // Timeout WITHOUT interrupt = workers still alive: kill them
                        // Deadline-aware wait: respect the 10-min global hard deadline
                        // from when the user pressed Ctrl+S. After 10 min, stop waiting
                        // and send whatever partial answers have been collected so far.
                        long elapsed = System.currentTimeMillis() - batchStartMs;
                        long remainMs = BATCH_HARD_DEADLINE_MS - elapsed;
                        long waitSec = Math.max(30, Math.min(remainMs / 1000, 480));
                        if (!executor.awaitTermination(waitSec, TimeUnit.SECONDS)) {
                            boolean hardDeadlineHit = (System.currentTimeMillis() - batchStartMs) >= BATCH_HARD_DEADLINE_MS;
                            Log.w(TAG, "Solver pool timed out" + (hardDeadlineHit ? " [10-min hard deadline]" : "") + " — shutting down, sending partial answers.");
                            try { executor.shutdownNow(); } catch (Exception ignored5) {}
                            // Hard deadline = send partial (don't silently discard work).
                            // Supersede/cancel = stay silent (no fake "none"s).
                            if (hardDeadlineHit && !BatchManager.CANCEL_ALL) {
                                poolKilled = false; // allow autofill + send below
                                if (listener != null) listener.onBatchProgress(batchId, 0, totalQuestions, "10-min deadline reached — sending partial answers...");
                            } else {
                                poolKilled = true;
                            }
                        }
                    } catch (InterruptedException ie) {
                        // Pool was shutdownNow()'d (superseded by a newer batch OR
                        // whole-reset). Fall through to the killed-check below.
                        poolKilled = true;
                        Log.w(TAG, "Solver pool interrupted (superseded/cancel).");
                    } finally {
                        currentExec = null;
                        if (batchId != null && batchId.equals(currentRunningBatchId)) {
                            currentRunningBatchId = null;
                        }
                    }
                    // A killed pool (non-deadline) MUST stay silent: autofilling + sending
                    // "none"s poisons the HUD with fake answers.
                    if (poolKilled || BatchManager.CANCEL_ALL) {
                        Log.w(TAG, "Batch " + batchId + " superseded/cancelled, skipping autofill/save/send.");
                        return;
                    }
                } // totalCalls > 0 (all-solved resume skips the pool entirely)

                // Sequential OOM retry: memory-killed chunks re-run ONE AT A TIME.
                // Only genuine second failure autofills. Drains this batch only.
                List<RetrySpec> oomPending = new ArrayList<>();
                synchronized (oomQueue) {
                    java.util.Iterator<RetrySpec> oomIt = oomQueue.iterator();
                    while (oomIt.hasNext()) {
                        RetrySpec oomRs = oomIt.next();
                        if (batchId.equals(oomRs.batchId)) {
                            oomPending.add(oomRs);
                            oomIt.remove();
                        }
                    }
                }
                if (!oomPending.isEmpty()) {
                    Log.w(TAG, "Retrying " + oomPending.size() + " OOM-killed chunk(s) sequentially.");
                    for (RetrySpec rs : oomPending) {
                        if (BatchManager.CANCEL_ALL) return;
                        try {
                            System.gc();
                            try { Thread.sleep(2000); } catch (InterruptedException ignored3) {}
                            solveChunkSync(batchDir, batchId, rs.qNums, rs.keyIdx, keyPool,
                                    txtMap, imgMap, audioMap, masterAnswers, btOut,
                                    listener, totalQuestions, mode);
                        } catch (Throwable t2) {
                            String why2 = (t2.getMessage() != null) ? t2.getMessage() : t2.toString();
                            Log.e(TAG, "OOM-retry failed for " + rs.qNums + ": " + why2);
                            synchronized (masterAnswers) {
                                try {
                                    JSONObject fc2 = masterAnswers.optJSONObject("_failed_chunks");
                                    if (fc2 == null) {
                                        fc2 = new JSONObject();
                                        masterAnswers.put("_failed_chunks", fc2);
                                    }
                                    fc2.put("oom" + rs.qNums.toString(), why2);
                                } catch (Exception ignored4) {}
                            }
                        }
                    }
                }

                // Auto-fill any missing questions, MARKED with confidence so no UI
                // ever presents them as solved (was silent "none").
                // Pre-read failed_chunks so we can attach a specific reason per question.
                JSONObject failedChunksSnap = masterAnswers.optJSONObject("_failed_chunks");
                List<String> autoFilled = new ArrayList<>();
                for (int qNum : allQNums) {
                    String qStr = String.format(Locale.US, "%02d", qNum);
                    if (!masterAnswers.has(qStr)) {
                        // Find the most specific failure reason available for this question
                        String failReason = "Question unanswered / skipped in AI response";
                        if (failedChunksSnap != null) {
                            Iterator<String> fcKeys = failedChunksSnap.keys();
                            while (fcKeys.hasNext()) {
                                String ck = fcKeys.next();
                                String cv = failedChunksSnap.optString(ck, "");
                                // chunk value format: "[Q1, Q2, Q3] :: error message"
                                if (cv.contains("[" + qNum + ",") || cv.contains(", " + qNum + ",")
                                        || cv.contains(", " + qNum + "]") || cv.contains("[" + qNum + "]")) {
                                    int sep = cv.indexOf(" :: ");
                                    failReason = sep >= 0
                                            ? "Chunk failed: " + cv.substring(sep + 4)
                                            : "Chunk failed: " + ck;
                                    break;
                                }
                            }
                        }
                        JSONObject filler = new JSONObject();
                        filler.put("number", qNum);
                        filler.put("option", "-");
                        filler.put("option_answer", "none");
                        filler.put("answer", "none");
                        filler.put("reason", failReason);
                        filler.put("confidence", "auto-filled");
                        masterAnswers.put(qStr, filler);
                        autoFilled.add(qStr);
                        Log.w(TAG, "Question #" + qStr + " was missing. Auto-filled. Reason: " + failReason);
                        appendSolverLog(batchDir, batchId, "Q" + qStr + " -> none [AUTO-FILLED: " + failReason + "]");

                        sendProgressFrame(btOut, qNum, filler, "none", failReason);
                        if (listener != null) {
                            listener.onQuestionSolved(qNum, "none", failReason);
                        }
                    }
                }
                JSONObject meta = new JSONObject();
                meta.put("schema", 1);
                meta.put("solver", "blue-android-optA");
                meta.put("auto_filled", new JSONArray(autoFilled));
                try {
                    // Promote chunk-failure forensics into _meta so the Mac report
                    // shows WHY questions went missing (no more silent "none").
                    JSONObject fc = masterAnswers.optJSONObject("_failed_chunks");
                    if (fc != null) {
                        meta.put("failed_chunks", fc);
                        masterAnswers.remove("_failed_chunks");
                    }
                } catch (Exception ignored4) {}
                masterAnswers.put("_meta", meta);

                // 3. Save answers.json on phone
                File answersFile = new File(batchDir, "answers.json");
                String answersJsonStr = masterAnswers.toString(2);
                try (FileOutputStream fos = new FileOutputStream(answersFile)) {
                    fos.write(answersJsonStr.getBytes(StandardCharsets.UTF_8));
                }

                Log.d(TAG, "Batch " + batchId + " SOLVED! Total answers: "
                        + (masterAnswers.length() - 1) + " (auto-filled: " + autoFilled.size() + ")");

                // 4. Send answers back to Mac over Bluetooth (chunked: survives >64KB).
                if (btOut != null) {
                    byte[] ansBytes = answersJsonStr.getBytes(StandardCharsets.UTF_8);
                    MuxProtocol.sendChunkedAnswers(btOut, ansBytes);
                    Log.d(TAG, "Sent chunked answers to Mac (" + ansBytes.length + " bytes).");
                }

                if (listener != null) {
                    listener.onBatchSolved(batchId, answersJsonStr);
                }

            } catch (Exception e) {
                Log.e(TAG, "Error solving batch " + batchId + ": " + e.getMessage(), e);
                if (listener != null) {
                    listener.onError(batchId, e.getMessage());
                }
                sendErrorAnswers(btOut, batchId, e.getMessage());
            }
        }).start();
    }

    /**
     * Re-solve a single question ("0" key feature).
     * Loads the question's files from the existing batch dir on the phone,
     * runs a 1-call Gemini solve, and sends the answer back via CMD_PROGRESS.
     * Merges the new answer into answers.json (updates the stored result).
     */
    public static void solveOneQuestion(Context ctx, File batchDir, String batchId,
            int qNum, OutputStream btOut, SolveListener listener) {
        new Thread(() -> {
            try {
                Log.d(TAG, "Re-solving single question Q" + qNum + " in batch " + batchId);

                // Load keys
                List<String> keyPool = new ArrayList<>();
                File keysFile = new File(batchDir, "keys.json");
                if (keysFile.exists()) {
                    try {
                        JSONObject kObj = new JSONObject(readFileToString(keysFile));
                        JSONArray kArr = kObj.optJSONArray("keys");
                        if (kArr != null) for (int i = 0; i < kArr.length(); i++) keyPool.add(kArr.getString(i));
                    } catch (Exception ignored) {}
                }
                if (keyPool.isEmpty()) {
                    throw new RuntimeException("No API keys for single-question re-solve.");
                }

                // Discover files for this question number
                Map<Integer, File> txtMap = new HashMap<>();
                Map<Integer, File> imgMap = new HashMap<>();
                Map<Integer, File> audioMap = new HashMap<>();
                Pattern numPattern = Pattern.compile("^(\\d+)");
                File[] files = batchDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        String name = f.getName().toLowerCase();
                        Matcher mm = numPattern.matcher(name);
                        if (mm.find()) {
                            try {
                                int n = Integer.parseInt(mm.group(1));
                                if (n != qNum) continue; // only load files for this question
                                if (name.endsWith(".txt")) txtMap.put(n, f);
                                else if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp")) imgMap.put(n, f);
                                else if (name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".m4a") || name.endsWith(".ogg") || name.endsWith(".aac")) audioMap.put(n, f);
                            } catch (Exception ignored) {}
                        }
                    }
                }

                // Load existing answers (so merge doesn't wipe other questions)
                JSONObject masterAnswers = new JSONObject();
                File answersFile = new File(batchDir, "answers.json");
                if (answersFile.exists()) {
                    try { masterAnswers = new JSONObject(readFileToString(answersFile)); } catch (Exception ignored) {}
                }

                // Remove old answer for this question so it gets freshly solved
                String qStr = String.format(Locale.US, "%02d", qNum);
                masterAnswers.remove(qStr);

                // Solve — single question, MODE_1_CALL
                List<Integer> singleQ = new ArrayList<>();
                singleQ.add(qNum);
                solveChunkSync(batchDir, batchId, singleQ, 0, keyPool,
                        txtMap, imgMap, audioMap, masterAnswers, btOut, listener, 1, MODE_1_CALL);

                // Persist updated answers.json
                saveIncrementalAnswers(batchDir, masterAnswers);
                Log.d(TAG, "Q" + qNum + " re-solved and saved.");

                // Send CMD_ANSWERS so bluebridge_send finishes cleanly in ~2s
                try {
                    byte[] b = masterAnswers.toString().getBytes(StandardCharsets.UTF_8);
                    MuxProtocol.writeFrame(btOut, (short) 0, MuxProtocol.CMD_ANSWERS, b);
                } catch (Exception ignored) {}

            } catch (Exception e) {
                Log.e(TAG, "solveOneQuestion failed for Q" + qNum + ": " + e.getMessage());
                // Send a visible error progress frame so the HUD doesn't silently hang
                try {
                    JSONObject err = new JSONObject();
                    err.put("number", qNum);
                    err.put("answer", "error");
                    err.put("reason", "Re-solve failed: " + e.getMessage());
                    sendProgressFrame(btOut, qNum, err, "error", "Re-solve failed: " + e.getMessage());
                    byte[] b = err.toString().getBytes(StandardCharsets.UTF_8);
                    MuxProtocol.writeFrame(btOut, (short) 0, MuxProtocol.CMD_ANSWERS, b);
                } catch (Exception ignored) {}
            }
        }).start();
    }

    /**
     * Deals questions into exactly n chunks (capped by caller).
     *
     * KEY RULE — Shared audio grouping: questions that reference the SAME audio
     * file must land in the SAME chunk so Gemini hears the clip once and answers
     * all sub-questions in a single context window. Spreading them across chunks
     * forces repeated audio embedding and produces inconsistent / "off" answers.
     *
     * Algorithm:
     *  1. Build audio-group buckets: all qNums sharing the same audio path go
     *     into one bucket (each bucket = one "super-media" item).
     *  2. Image-only questions stay as individual items.
     *  3. Round-robin buckets + individual images across n slots (spread heavy
     *     payloads thin). A whole audio-group goes to the SAME slot.
     *  4. Text questions fill remaining slots round-robin.
     */
    private static List<List<Integer>> dealChunks(List<Integer> allQNums,
            Map<Integer, File> txtMap, Map<Integer, File> imgMap, Map<Integer, File> audioMap, int n) {
        // --- Step 1: build audio groups keyed by canonical audio file path ---
        Map<String, List<Integer>> audioGroups = new java.util.LinkedHashMap<>();
        List<Integer> imgOnly = new ArrayList<>();
        List<Integer> text   = new ArrayList<>();

        for (int q : allQNums) {
            File af = audioMap.get(q);
            if (af != null) {
                // Canonical key: absolute path so the same physical file groups together.
                String key = af.getAbsolutePath();
                audioGroups.computeIfAbsent(key, x -> new ArrayList<>()).add(q);
            } else if (imgMap.containsKey(q)) {
                imgOnly.add(q);
            } else {
                text.add(q);
            }
        }

        List<List<Integer>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new ArrayList<>());

        // --- Step 2: assign audio groups round-robin (whole group → same slot) ---
        int k = 0;
        for (List<Integer> grp : audioGroups.values()) {
            int slot = (k++) % n;
            out.get(slot).addAll(grp);
        }
        // --- Step 3: assign image-only questions round-robin ---
        for (int q : imgOnly) out.get((k++) % n).add(q);
        // --- Step 4: assign text questions round-robin ---
        for (int q : text) out.get((k++) % n).add(q);

        List<List<Integer>> chunks = new ArrayList<>();
        for (List<Integer> c : out) {
            if (!c.isEmpty()) { Collections.sort(c); chunks.add(c); }
        }
        return chunks;
    }

    /** Checkpoint writer for resume-after-death (best effort, never throws). */
    private static void saveIncrementalAnswers(File batchDir, JSONObject masterAnswers) {
        if (batchDir == null) return;
        try {
            JSONObject snap;
            synchronized (masterAnswers) {
                snap = new JSONObject(masterAnswers.toString());
            }
            try (FileOutputStream fos = new FileOutputStream(new File(batchDir, "answers.json"))) {
                fos.write(snap.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    /**
     * LaTeX → plain Unicode for HUD display (same size as all other text, no images,
     * no network, no errors). Safety net behind the prompt rule: any residual
     * $…$, \(…\), \frac, \sqrt etc. becomes readable one-line Unicode.
     */
    public static String normalizeMath(String s) {
        if (s == null) return null;
        String t = s;
        t = t.replaceAll("\\$\\$+", "");
        t = t.replaceAll("\\\\\\(", "");
        t = t.replaceAll("\\\\\\)", "");
        t = t.replaceAll("\\\\\\[", "");
        t = t.replaceAll("\\\\\\]", "");
        t = t.replaceAll("\\$", "");
        t = t.replaceAll("\\\\\\\\", " ");
        t = t.replaceAll("\\*\\*(.+?)\\*\\*", "$1"); // **bold** markers (HUD has no markdown)
        // Balanced-brace commands (handles NESTING like \frac{-b \pm \sqrt{b^2-4ac}}{2a},
        // which flat regex cannot match). Fixpoint: outer flattens, inner resolves next round.
        for (int i = 0; i < 5; i++) {
            String n = expandBalancedCmds(t);
            if (n.equals(t)) break;
            t = n;
        }
        t = t.replaceAll("\\\\sqrt(?![a-zA-Z])", "\u221A");
        String[][] sym = {
                {"pm", "\u00B1"}, {"times", "\u00D7"}, {"cdot", "\u00B7"}, {"div", "\u00F7"},
                {"leq", "\u2264"}, {"geq", "\u2265"}, {"neq", "\u2260"}, {"approx", "\u2248"},
                {"infty", "\u221E"}, {"to", "\u2192"}, {"ldots", "\u2026"},
                {"pi", "\u03C0"}, {"theta", "\u03B8"}, {"alpha", "\u03B1"}, {"beta", "\u03B2"},
                {"gamma", "\u03B3"}, {"delta", "\u03B4"}, {"lambda", "\u03BB"}, {"mu", "\u03BC"},
                {"sigma", "\u03C3"}, {"omega", "\u03C9"},
        };
        for (String[] p : sym) {
            t = t.replaceAll("\\\\" + p[0] + "(?![a-zA-Z])", java.util.regex.Matcher.quoteReplacement(p[1]));
        }
        t = mapSuperscripts(t);
        t = t.replaceAll("_\\{([^{}]*)\\}", "_$1");
        t = t.replaceAll("\\\\([a-zA-Z]+)", "$1");
        t = t.replaceAll("[ \\t\\x0B\\f\\r]+", " ").trim();
        return t;
    }

    /** One round of balanced \cmd{..} expansion. Returns input unchanged if none. */
    private static String expandBalancedCmds(String t) {
        StringBuilder out = new StringBuilder();
        int i = 0, n = t.length();
        boolean changed = false;
        while (i < n) {
            if (t.charAt(i) == '\\' && i + 1 < n && Character.isLetter(t.charAt(i + 1))) {
                int j = i + 1;
                while (j < n && Character.isLetter(t.charAt(j))) j++;
                String cmd = t.substring(i + 1, j);
                int k = j;
                while (k < n && Character.isWhitespace(t.charAt(k))) k++;
                java.util.List<String> groups = new java.util.ArrayList<>();
                // \frac takes 2 groups, \sqrt 1, other wrappers 1 (unwrap to content)
                int want = cmd.equals("frac") ? 2 : 1;
                int p = k;
                boolean ok = true;
                for (int g = 0; g < want; g++) {
                    while (p < n && Character.isWhitespace(t.charAt(p))) p++;
                    if (p >= n || t.charAt(p) != '{') { ok = false; break; }
                    int depth = 0, q = p;
                    while (q < n) {
                        if (t.charAt(q) == '{') depth++;
                        else if (t.charAt(q) == '}') {
                            depth--;
                            if (depth == 0) break;
                        }
                        q++;
                    }
                    if (q >= n) { ok = false; break; }
                    groups.add(t.substring(p + 1, q));
                    p = q + 1;
                }
                if (ok && !groups.isEmpty()) {
                    changed = true;
                    if (cmd.equals("frac") && groups.size() == 2) {
                        out.append('(').append(groups.get(0)).append(")/(").append(groups.get(1)).append(')');
                    } else if (cmd.equals("sqrt")) {
                        out.append('\u221A').append('(').append(groups.get(0)).append(')');
                    } else {
                        out.append(groups.get(0)); // \mathbf, \text, ... → inner content
                    }
                    i = p;
                    continue;
                }
                // \sqrt without braces, or malformed: copy command through for later passes
                if (ok && groups.isEmpty()) { /* fallthrough copy */ }
                out.append(t.charAt(i));
                i++;
            } else {
                out.append(t.charAt(i));
                i++;
            }
        }
        return changed ? out.toString() : t;
    }

    private static String mapSuperscripts(String t) {
        java.util.Map<Character, String> sup = new java.util.HashMap<>();
        sup.put('0', "\u2070"); sup.put('1', "\u00B9"); sup.put('2', "\u00B2"); sup.put('3', "\u00B3");
        sup.put('4', "\u2074"); sup.put('5', "\u2075"); sup.put('6', "\u2076"); sup.put('7', "\u2077");
        sup.put('8', "\u2078"); sup.put('9', "\u2079"); sup.put('+', "\u207A"); sup.put('-', "\u207B");
        sup.put('n', "\u207F"); sup.put('i', "\u2071");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\^\\{([^{}]*)\\}|\\^([0-9+\\-ni])").matcher(t);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String inner = m.group(1) != null ? m.group(1) : m.group(2);
            StringBuilder o = new StringBuilder();
            boolean ok = inner.length() > 0;
            for (int i = 0; i < inner.length(); i++) {
                String r = sup.get(inner.charAt(i));
                if (r == null) { ok = false; break; }
                o.append(r);
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                    ok ? o.toString() : "^(" + inner + ")"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Normalizes display text fields of one answer object in place (data-safe:
     * only reason/analysis/answer/option_answer strings; structure untouched). */
    private static void normalizeQObjText(JSONObject qObj) {
        if (qObj == null) return;
        // Dummy option fields ("option": null/""/"-") must NOT veto matrix
        // canonicalization: only a REAL letter or non-empty array counts.
        if (hasRealOption(qObj)) return;
        canonicalizeMatrix(qObj); // any matrix shape -> one canonical form first
        try {
            Object ro = qObj.opt("statements");
            if (ro instanceof JSONArray) {
                JSONArray arr = (JSONArray) ro;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject st = arr.optJSONObject(i);
                    if (st != null && st.has("status")) {
                        try { st.put("status", normalizeStatus(st.optString("status", "?"))); }
                        catch (Exception ignored2) {}
                    }
                }
            }
        } catch (Exception ignored) {}
        try {
            for (String k : new String[]{"reason", "analysis"}) {
                String v = qObj.optString(k, null);
                if (v != null && !v.isEmpty()) {
                    try { qObj.put(k, normalizeMath(v)); } catch (Exception ignored) {}
                }
            }
            for (String k : new String[]{"answer", "option_answer"}) {
                Object o = qObj.opt(k);
                if (o instanceof String) {
                    String v = (String) o;
                    if (!v.isEmpty()) {
                        try { qObj.put(k, normalizeMath(v)); } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    /** Solves one chunk synchronously: build parts, call, merge, stream progress. */
    private static final Object LOG_LOCK = new Object();

    /** Matrix/complex detector: Benar/Salah + multi-select + exception words get
     * MEDIUM thinking (multi-hop entailment); plain single-choice stays LOW. */
    private static boolean needsDeepThinking(File batchDir, List<Integer> qNums,
            Map<Integer, File> txtMap) {
        java.util.regex.Pattern deep = java.util.regex.Pattern.compile(
                "(?i)benar|salah|pilih semua|checkbox|kecuali|tidak benar|jodohkan|pernyataan");
        for (int qNum : qNums) {
            try {
                File t = txtMap != null ? txtMap.get(qNum) : null;
                if (t == null || !t.exists() || t.length() > 65536) continue;
                String s = readFileToString(t);
                if (s != null && deep.matcher(s).find()) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    /** Persistent solver log (survives Activity pause; replayed on resume). */
    private static void appendSolverLog(File batchDir, String batchId, String line) {
        if (batchDir == null) return;
        synchronized (LOG_LOCK) {
            try (java.io.FileWriter fw = new java.io.FileWriter(new File(batchDir, "solver.log"), true)) {
                fw.write("[" + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                        .format(new java.util.Date()) + "][" + batchId + "] " + line + "\n");
            } catch (Exception ignored) {}
        }
    }

    /** Mode-aware system instruction: enforces rigorous reasoning, strict question-type protocols,
     * distractor elimination, exact JSON schema, and Unicode math formatting. */
    private static String buildPreamble(int mode, int qCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an elite academic examination solver with 100% precision and zero error tolerance.\n");
        sb.append("LANGUAGE LOCK: Strictly answer in the SAME language as the question. English questions -> English explanation; Indonesian questions -> Bahasa Indonesia. Never mix languages.\n\n");

        if (mode == MODE_1_CALL) {
            sb.append("TOKEN BUDGET: ").append(qCount).append(" questions in ONE single call. Be concise but rigorous: 'analysis' <= 2 sentences per question, 'reason' 1 sentence. Finish ALL questions completely without truncation.\n\n");
        } else if (mode == MODE_10_CALL) {
            sb.append("TOKEN BUDGET: Few questions per call. Completeness and deep verification prioritized. Full step-by-step analysis, distractor disqualification, and exact passage quotes mandatory.\n\n");
        } else {
            sb.append("TOKEN BUDGET: Balanced mode. Concise analysis (2-3 sentences per question), reason 1-2 sentences. Verify all steps before outputting.\n\n");
        }

        sb.append("QUESTION TYPE PROTOCOLS:\n");
        sb.append("1. SINGLE CHOICE (Pilihan Ganda):\n");
        sb.append("   - Solve independently first. Identify why the correct choice is uniquely valid and why distractors are wrong.\n");
        sb.append("   - Required fields: 'analysis', 'number' (integer), 'option' (single letter A-E), 'option_answer' (text of choice), 'answer' (e.g. \"A. Option text\"), 'reason'.\n");
        sb.append("2. COMPLEX MULTIPLE CHOICE (Pilihan Ganda Kompleks / Multi-Select):\n");
        sb.append("   - Scrutinize negative trigger words: 'kecuali', 'bukan', 'tidak benar', 'salah', 'except', 'not'. If inverted, select the FALSE statement(s).\n");
        sb.append("   - Evaluate EACH option independently with strict Boolean validity (TRUE/FALSE) based on text/facts.\n");
        sb.append("   - 'options' array must contain ALL and ONLY the verified options (e.g. [\"A\", \"C\"]).\n");
        sb.append("   - 'answer' array must contain the matching full text strings: [\"A. Option text\", \"C. Option text\"].\n");
        sb.append("3. STATEMENT MATRIX / TRUE-FALSE (Pernyataan Benar-Salah / Pasti Benar / Pasti Salah / Tidak Dapat Ditentukan):\n");
        sb.append("   - High decidability burden: The exam author intended every statement to be decidable as Benar or Salah from the passage or standard domain knowledge. Choose 'Tidak' ONLY if completely unmentioned and logically undecidable.\n");
        sb.append("   - Statements unmentioned in reading comprehension are 'Salah' (false claim regarding passage content), NOT 'Tidak'.\n");
        sb.append("   - Status values must strictly be one of: 'Benar', 'Salah', 'Tidak'. Map choices: A/Pasti Benar -> Benar; B/Pasti Salah -> Salah; C/Tidak Dapat Ditentukan -> Tidak.\n");
        sb.append("   - 'statements' must be an array of objects: [{\"index\": 1, \"status\": \"Benar\", \"text\": \"statement text\", \"quote\": \"supporting quote <= 20 words\"}].\n");
        sb.append("   - 'answer' must be an array of formatted strings: [\"1. Benar - statement text\", \"2. Salah - statement text\"].\n\n");

        sb.append("MATHEMATICAL & SCIENTIFIC FORMATTING:\n");
        sb.append("   - Pure Unicode on a single line. NEVER use LaTeX tags ($ or $$ or \\frac or \\sqrt or backslashes).\n");
        sb.append("   - Use Unicode symbols: x² x³ x⁴ ₁ ₂ ± × ÷ · ≤ ≥ ≠ ≈ ∞ π θ Δ ° √. Fractions format as (numerator)/(denominator), roots format as √(expression).\n");
        sb.append("   - Numeric rounding: If exact calculation has small rounding difference with options, select the nearest option.\n\n");

        sb.append("OUTPUT DISCIPLINE:\n");
        sb.append("   - Return ONLY a valid JSON object whose keys are 2-digit zero-padded numbers (\"01\", \"02\", ...).\n");
        sb.append("   - REASON-FIRST ordering inside each question object: 'analysis' must be the FIRST key to enforce reasoning before conclusion.\n");
        sb.append("   - No markdown fences (no ```json), no intro text, no trailing comments.\n");
        sb.append("   - SHARED MEDIA: When a group of questions is labelled '[AUDIO LISTENING — Soal X, Y, Z]' or '[IMAGE — Soal X, Y, Z]', those questions share ONE media source. Listen/look ONCE, then answer each sub-question independently under its own numbered key. Every sub-question number MUST appear in the output JSON — do NOT skip any number in the group.\n");

        return sb.toString();
    }

    private static void solveChunkSync(File batchDir, String batchId, List<Integer> qNums, int keyIdx,
            List<String> keyPool, Map<Integer, File> txtMap, Map<Integer, File> imgMap,
            Map<Integer, File> audioMap, JSONObject masterAnswers, OutputStream btOut,
            SolveListener listener, int totalQuestions, int mode) throws Exception {
        // --- Build parts with shared-audio deduplication ---
        // Group qNums by audio file path so identical audio clips are emitted
        // ONCE (not repeated per question), then each sub-question follows.
        JSONArray parts = new JSONArray();
        Map<String, List<Integer>> audioGroupsInChunk = new java.util.LinkedHashMap<>();
        List<Integer> nonAudioInChunk = new ArrayList<>();
        for (int qNum : qNums) {
            File af = audioMap.get(qNum);
            if (af != null && af.exists() && af.length() > 0) {
                audioGroupsInChunk.computeIfAbsent(af.getAbsolutePath(), x -> new ArrayList<>()).add(qNum);
            } else {
                nonAudioInChunk.add(qNum);
            }
        }
        // Emit each shared-audio group: audio once + all sub-questions
        for (Map.Entry<String, List<Integer>> entry : audioGroupsInChunk.entrySet()) {
            List<Integer> grpNums = entry.getValue();
            // Build the list of question numbers for the label
            StringBuilder qLabel = new StringBuilder();
            for (int i = 0; i < grpNums.size(); i++) {
                if (i > 0) qLabel.append(", ");
                qLabel.append(String.format(Locale.US, "%02d", grpNums.get(i)));
            }
            // Emit audio header label
            JSONObject hdrPart = new JSONObject();
            hdrPart.put("text", "\n=== [AUDIO LISTENING — Soal " + qLabel + "] ===\n"
                    + "(Audio berikut digunakan untuk semua soal dalam grup ini. "
                    + "Dengarkan SEKALI lalu jawab setiap sub-pertanyaan secara mandiri.)");
            parts.put(hdrPart);
            // Emit the audio inline_data ONCE
            File sharedAudio = audioMap.get(grpNums.get(0));
            if (sharedAudio != null && sharedAudio.exists()) {
                byte[] audioBytes = readFileToBytes(sharedAudio);
                String b64 = android.util.Base64.encodeToString(audioBytes, android.util.Base64.NO_WRAP);
                String mime = "audio/mp3";
                String aName = sharedAudio.getName().toLowerCase();
                if (aName.endsWith(".wav")) mime = "audio/wav";
                else if (aName.endsWith(".m4a")) mime = "audio/mp4";
                else if (aName.endsWith(".aac")) mime = "audio/aac";
                else if (aName.endsWith(".ogg") || aName.endsWith(".opus")) mime = "audio/ogg";
                else if (aName.endsWith(".weba")) mime = "audio/webm";
                JSONObject audioPart = new JSONObject();
                JSONObject inlineData = new JSONObject();
                inlineData.put("mime_type", mime);
                inlineData.put("data", b64);
                audioPart.put("inline_data", inlineData);
                parts.put(audioPart);
            }
            // Emit each sub-question's text (no per-question audio repeat)
            for (int qNum : grpNums) {
                String qStr = String.format(Locale.US, "%02d", qNum);
                File tf = txtMap.get(qNum);
                JSONObject qPart = new JSONObject();
                String qText = "\n=== [SOAL " + qStr + " (sub-pertanyaan audio)] ===";
                if (tf != null && tf.exists()) qText += "\n" + readFileToString(tf);
                qPart.put("text", qText);
                parts.put(qPart);
            }
        }
        // Emit non-audio questions normally
        for (int qNum : nonAudioInChunk) {
            String qStr = String.format(Locale.US, "%02d", qNum);
            appendQuestionToParts(parts, qNum, qStr, txtMap.get(qNum), imgMap.get(qNum), null);
        }

        String sysPrompt = buildPreamble(mode, qNums.size());
        int budget = Math.min(32768, Math.max(8192, qNums.size() * 6000));
        String thinking = needsDeepThinking(batchDir, qNums, txtMap) ? "MEDIUM" : "LOW";
        Log.d(TAG, "Chunk [" + qNums.size() + " Qs] thinking=" + thinking);
        JSONObject chunkRes = callGeminiWithRetry(keyPool, keyIdx, sysPrompt, parts, batchId, listener, budget, thinking);

        synchronized (masterAnswers) {
            mergeAnswers(masterAnswers, chunkRes);
            // Stamp per-question attribution (model + key) for the transparency UI.
            // Survives in answers.json; renderers ignore unknown fields.
            String sm = chunkRes.optString("_model", "?");
            int sk = chunkRes.optInt("_key", -1);
            for (int qNum : qNums) {
                JSONObject qObj = masterAnswers.optJSONObject(
                        String.format(Locale.US, "%02d", qNum));
                if (qObj != null) {
                    try {
                        if (!qObj.has("model")) qObj.put("model", sm);
                        if (!qObj.has("key")) qObj.put("key", sk);
                    } catch (Exception ignored) {}
                }
            }
        }
        // Checkpoint: each finished chunk persists answers.json, so a process
        // death loses at most one chunk — resend resumes from here.
        saveIncrementalAnswers(batchDir, masterAnswers);

        for (int qNum : qNums) {
            String qStr = String.format(Locale.US, "%02d", qNum);
            JSONObject qObj = masterAnswers.optJSONObject(qStr);
            if (qObj != null) {
                normalizeQObjText(qObj); // LaTeX → Unicode before save/stream/display
                String rsn = qObj.optString("reason", "");
                if (rsn == null || rsn.trim().isEmpty()) {
                    rsn = qObj.optString("analysis", "");
                }
                String ansStr = buildAnswerString(qObj);
                appendSolverLog(batchDir, batchId, "Q" + qStr + " -> " + ansStr
                        + " [" + lastSuccessModel + "/k" + lastSuccessKey + "]");
                sendProgressFrame(btOut, qNum, qObj, ansStr, rsn);
                if (listener != null) {
                    listener.onQuestionSolved(qNum, ansStr, rsn);
                }
            }
        }

        if (listener != null) {
            int solvedCount;
            synchronized (masterAnswers) {
                solvedCount = masterAnswers.length() - (masterAnswers.has("_meta") ? 1 : 0);
            }
            listener.onBatchProgress(batchId, solvedCount, totalQuestions,
                    "Solved " + solvedCount + "/" + totalQuestions + " questions...");
        }
    }

    private static void appendQuestionToParts(JSONArray parts, int qNum, String qStr, File txtFile, File imgFile, File audioFile) throws Exception {
        // Skip empty/oversize media loudly instead of 400-crashing the whole chunk.
        if (imgFile != null && (!imgFile.exists() || imgFile.length() == 0)) {
            Log.w(TAG, "Q" + qStr + " image empty/missing, sending as text.");
            imgFile = null;
        }
        if (audioFile != null && (!audioFile.exists() || audioFile.length() == 0)) {
            Log.w(TAG, "Q" + qStr + " audio empty/missing, sending as text.");
            audioFile = null;
        }
        if (imgFile != null && imgFile.length() > 8 * 1024 * 1024) {
            Log.w(TAG, "Q" + qStr + " image >8MB, skipping media (re-extract smaller).");
            imgFile = null;
        }
        if (audioFile != null && audioFile.length() > 8 * 1024 * 1024) {
            Log.w(TAG, "Q" + qStr + " audio >8MB, skipping media.");
            audioFile = null;
        }
        if (imgFile != null && imgFile.exists()) {
            if (txtFile != null && txtFile.exists()) {
                JSONObject txtPart = new JSONObject();
                txtPart.put("text", "\n=== [SOAL " + qStr + "] ===\n" + readFileToString(txtFile) +
                        "\n(Perhatikan gambar/tabel/diagram berikut untuk soal " + qStr + "):");
                parts.put(txtPart);
            } else {
                JSONObject labelPart = new JSONObject();
                labelPart.put("text", "\n=== [SOAL " + qStr + "] (Lihat Gambar Berikut) ===");
                parts.put(labelPart);
            }

            // Bad-image resilience: if the file is corrupted/unreadable, fall back to
            // text-only instead of crashing the entire chunk (which would make all
            // questions in the chunk become "known"/auto-filled).
            try {
                byte[] imgBytes = readFileToBytes(imgFile);
                String b64 = Base64.encodeToString(imgBytes, Base64.NO_WRAP);
                String mime = imgFile.getName().toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
                JSONObject imgPart = new JSONObject();
                JSONObject inlineData = new JSONObject();
                inlineData.put("mime_type", mime);
                inlineData.put("data", b64);
                imgPart.put("inline_data", inlineData);
                parts.put(imgPart);
            } catch (Exception imgErr) {
                Log.w(TAG, "Q" + qStr + " image unreadable (corrupted?), falling back to text-only: " + imgErr.getMessage());
                // Already emitted the text label above — question still gets answered.
            }

        } else if (audioFile != null && audioFile.exists()) {
            JSONObject labelPart = new JSONObject();
            labelPart.put("text", "\n=== [SOAL " + qStr + " (AUDIO LISTENING)] ===");
            parts.put(labelPart);

            byte[] audioBytes = readFileToBytes(audioFile);
            String b64 = Base64.encodeToString(audioBytes, Base64.NO_WRAP);
            String mime = "audio/mp3";
            String aName = audioFile.getName().toLowerCase();
            if (aName.endsWith(".wav")) mime = "audio/wav";
            else if (aName.endsWith(".m4a")) mime = "audio/mp4";
            else if (aName.endsWith(".aac")) mime = "audio/aac";
            else if (aName.endsWith(".ogg") || aName.endsWith(".opus")) mime = "audio/ogg";
            else if (aName.endsWith(".weba")) mime = "audio/webm";
            else if (aName.endsWith(".mid") || aName.endsWith(".midi")) mime = "audio/midi";

            JSONObject audioPart = new JSONObject();
            JSONObject inlineData = new JSONObject();
            inlineData.put("mime_type", mime);
            inlineData.put("data", b64);
            audioPart.put("inline_data", inlineData);
            parts.put(audioPart);

            if (txtFile != null && txtFile.exists()) {
                JSONObject txtPart = new JSONObject();
                txtPart.put("text", "Teks / Pertanyaan Audio " + qStr + ":\n" + readFileToString(txtFile));
                parts.put(txtPart);
            }

        } else if (txtFile != null && txtFile.exists()) {
            JSONObject txtPart = new JSONObject();
            txtPart.put("text", "\n=== [SOAL " + qStr + "] ===\n" + readFileToString(txtFile));
            parts.put(txtPart);
        }
    }

    // P3 quota scheduler: token bucket per key (4 call-starts/min). Chunks and
    // retries wait for a token instead of firing into 429 walls + 65s cooldowns.
    private static final java.util.concurrent.ConcurrentHashMap<Integer, java.util.ArrayDeque<Long>> keySlotMap =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static void acquireKeySlot(int keyIdx) throws Exception {
        java.util.ArrayDeque<Long> dq = keySlotMap.get(keyIdx);
        if (dq == null) {
            java.util.ArrayDeque<Long> fresh = new java.util.ArrayDeque<>();
            java.util.ArrayDeque<Long> prev = keySlotMap.putIfAbsent(keyIdx, fresh);
            dq = (prev != null) ? prev : fresh;
        }
        while (true) {
            if (BatchManager.CANCEL_ALL) throw new RuntimeException("CANCELLED");
            long waitMs;
            synchronized (dq) {
                long now = System.currentTimeMillis();
                while (!dq.isEmpty() && now - dq.peekFirst() > 60000) dq.pollFirst();
                if (dq.size() < 4) {
                    dq.addLast(now);
                    return;
                }
                waitMs = 60000 - (now - dq.peekFirst()) + 50;
            }
            if (waitMs < 250) waitMs = 250;
            if (waitMs > 5000) waitMs = 5000;
            Thread.sleep(waitMs);
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, Long> keyCooldownMap = new java.util.concurrent.ConcurrentHashMap<>();
    // Model health: consecutive-503 streak per model + cooldown; lite exempt.
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> modelCooldownMap = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> modelFailStreak = new java.util.concurrent.ConcurrentHashMap<>();
    // Last success attribution (shown in solver.log per question).
    public static volatile String lastSuccessModel = "-";
    public static volatile int lastSuccessKey = -1;

    private static boolean hasMediaParts(JSONArray parts) {
        if (parts == null) return false;
        for (int i = 0; i < parts.length(); i++) {
            JSONObject p = parts.optJSONObject(i);
            if (p != null && p.has("inline_data")) {
                return true;
            }
        }
        return false;
    }

    private static JSONArray stripMediaParts(JSONArray parts) {
        JSONArray textOnly = new JSONArray();
        if (parts == null) return textOnly;
        for (int i = 0; i < parts.length(); i++) {
            JSONObject p = parts.optJSONObject(i);
            if (p != null && p.has("text")) {
                try {
                    JSONObject newP = new JSONObject();
                    newP.put("text", p.getString("text"));
                    textOnly.put(newP);
                } catch (Exception ignored) {}
            }
        }
        return textOnly;
    }

    private static JSONObject callGeminiWithRetry(List<String> keyPool, int startKeyIdx, String systemInstruction, JSONArray parts, String batchId, SolveListener listener, int maxTokens, String thinking) throws Exception {
        // Sticky retry: 2 full rounds over key x model combos — a chunk that keeps
        // failing loops back onto the same keys instead of giving up after one pass.
        int maxRetries = Math.max(keyPool.size() * MODEL_SEQUENCE.length * 2, 25);
        int currentKeyIdx = startKeyIdx;
        int currentModelIdx = 0;
        JSONArray activeParts = parts;
        int attempt = 0;
        int consecutiveCooldownSkips = 0;

        while (attempt < maxRetries) {
            if (BatchManager.CANCEL_ALL) throw new RuntimeException("CANCELLED");
            String key = keyPool.get(currentKeyIdx % keyPool.size());
            int keyDisplayNum = (currentKeyIdx % keyPool.size()) + 1;
            String model = MODEL_SEQUENCE[currentModelIdx % MODEL_SEQUENCE.length];

            // Circuit Breaker: check if key is in cooldown (prune expired first)
            long now = System.currentTimeMillis();
            for (String k : new ArrayList<>(keyCooldownMap.keySet())) {
                Long v = keyCooldownMap.get(k);
                if (v == null || v <= now) keyCooldownMap.remove(k);
            }
            Long cooldownUntil = keyCooldownMap.get(key);
            if (cooldownUntil != null && now < cooldownUntil) {
                long remainingSec = (cooldownUntil - now) / 1000;
                Log.d(TAG, "Key " + keyDisplayNum + " is in rate-limit cooldown (" + remainingSec + "s left). Skipping to next key...");
                currentKeyIdx++;
                consecutiveCooldownSkips++;
                if (consecutiveCooldownSkips >= keyPool.size()) {
                    // All keys are currently in cooldown; sleep 1.5s to allow quota reset instead of burning retries
                    Thread.sleep(1500);
                    consecutiveCooldownSkips = 0;
                }
                continue;
            }
            consecutiveCooldownSkips = 0;

            // Model circuit-breaker: a model that 503s 3x in a row sleeps 120s.
            // lite is exempt (always the last-resort safety net, never skipped).
            if (!model.endsWith("-lite")) {
                Long mcd = modelCooldownMap.get(model);
                if (mcd != null) {
                    if (now < mcd) {
                        currentModelIdx++;
                        continue;
                    }
                    modelCooldownMap.remove(model);
                    modelFailStreak.remove(model);
                }
            }

            attempt++;

            // Option A: NEVER auto-strip media. 503/overload is capacity, not payload.
            // Media is stripped ONLY on HTTP 400 below. Keeps image/audio accuracy.
            String urlStr = API_BASE + "/" + model + ":generateContent?key=" + key;

            JSONObject rootPayload = new JSONObject();

            // Official Gemini API system_instruction separation
            if (systemInstruction != null && !systemInstruction.trim().isEmpty()) {
                JSONObject sysObj = new JSONObject();
                JSONArray sysParts = new JSONArray();
                JSONObject sysText = new JSONObject();
                sysText.put("text", systemInstruction.trim());
                sysParts.put(sysText);
                sysObj.put("parts", sysParts);
                rootPayload.put("system_instruction", sysObj);
            }

            JSONArray contents = new JSONArray();
            JSONObject contentObj = new JSONObject();
            contentObj.put("parts", activeParts);
            contents.put(contentObj);
            rootPayload.put("contents", contents);

            JSONObject genConfig = new JSONObject();
            genConfig.put("responseMimeType", "application/json");
            // Strict deterministic decoding
            genConfig.put("temperature", 0.1);
            genConfig.put("topP", 0.95);
            // Scaled budget: ~6k per question (fits analysis-first), 8k min, 32768 cap.
            // Thinking routed per chunk: MEDIUM for matrix/complex, LOW otherwise
            // (never HIGH on free tier). 3.8 supports low|medium|high (no minimal).
            genConfig.put("maxOutputTokens", maxTokens);
            JSONObject thinkingConfig = new JSONObject();
            thinkingConfig.put("thinkingLevel", (thinking != null) ? thinking : "LOW");
            genConfig.put("thinkingConfig", thinkingConfig);
            rootPayload.put("generationConfig", genConfig);

            // P3: wait for this key's quota token before spending an HTTP call.
            // Normalized index: raw currentKeyIdx grows unbounded (must not leak map keys).
            acquireKeySlot(currentKeyIdx % keyPool.size());

            HttpURLConnection conn = null;
            try {
                URL url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(90000);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                byte[] bodyBytes = rootPayload.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bodyBytes);
                    os.flush();
                }

                int code = conn.getResponseCode();
                if (code == 200) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);

                        JSONObject resp = new JSONObject(sb.toString());
                        JSONArray candidates = resp.optJSONArray("candidates");
                        if (candidates != null && candidates.length() > 0) {
                            JSONObject cand = candidates.getJSONObject(0);
                            // Truncated output (budget too small) -> loud signal for split-retry,
                            // never silently parsed into partial answers.
                            if ("MAX_TOKENS".equalsIgnoreCase(cand.optString("finishReason", ""))) {
                                throw new RuntimeException("MAX_TOKENS_TRUNCATED");
                            }
                            JSONObject content = cand.optJSONObject("content");
                            if (content != null) {
                                JSONArray respParts = content.optJSONArray("parts");
                                if (respParts != null && respParts.length() > 0) {
                                    String text = respParts.getJSONObject(0).optString("text", "").trim();
                                    JSONObject parsed = parseGeminiJson(text);
                                    // Success: clear model health + record attribution
                                    modelFailStreak.remove(model);
                                    modelCooldownMap.remove(model);
                                    lastSuccessModel = model;
                                    lastSuccessKey = keyDisplayNum;
                                    try {
                                        parsed.put("_model", model);
                                        parsed.put("_key", keyDisplayNum);
                                    } catch (Exception ignored) {}
                                    logAttempt("k" + keyDisplayNum + "/" + model + " -> 200");
                                    return parsed;
                                }
                            }
                        }
                    }
                } else {
                    // Read error stream for exact diagnosis
                    String errBody = "";
                    InputStream errStream = conn.getErrorStream();
                    if (errStream != null) {
                        try (BufferedReader ebr = new BufferedReader(new InputStreamReader(errStream, StandardCharsets.UTF_8))) {
                            StringBuilder esb = new StringBuilder();
                            String eline;
                            while ((eline = ebr.readLine()) != null) esb.append(eline);
                            errBody = esb.toString();
                        }
                    }

                    Log.w(TAG, "Key " + keyDisplayNum + " Model " + model + " returned HTTP " + code + ": " + errBody);

                    if (code == 429) {
                        // Rate limit / Quota exceeded -> trip circuit breaker for this key
                        logAttempt("k" + keyDisplayNum + "/" + model + " -> 429 cooldown");
                        Log.w(TAG, "Key " + keyDisplayNum + " hit 429 rate limit. Cooldown 65s.");
                        keyCooldownMap.put(key, System.currentTimeMillis() + 65000);
                        currentKeyIdx++;
                        if (listener != null) {
                            listener.onBatchProgress(batchId, 0, 0, "Key " + keyDisplayNum + " 429 rate-limited. Rotating key...");
                        }
                    } else if (code == 503 || code == 500 || code == 502 || code == 504 || code == 404) {
                        // Option A: keep media, just cascade key+model (503 = capacity, not payload).
                        // Model streak: 3 consecutive overloads -> model sleeps 120s (lite exempt).
                        if (code == 503 && !model.endsWith("-lite")) {
                            int streak = modelFailStreak.getOrDefault(model, 0) + 1;
                            modelFailStreak.put(model, streak);
                            if (streak >= 3) {
                                modelCooldownMap.put(model, System.currentTimeMillis() + 120000);
                                Log.w(TAG, "Model " + model + " 503 x3 — cooling down 120s, lite takes over.");
                            }
                        }
                        currentKeyIdx++;
                        currentModelIdx++;
                        String nextModel = MODEL_SEQUENCE[currentModelIdx % MODEL_SEQUENCE.length];
                        logAttempt("k" + keyDisplayNum + "/" + model + " -> HTTP " + code);
                        Log.w(TAG, "Model " + model + " returned HTTP " + code + ". Cascading to next model: " + nextModel);
                        if (listener != null) {
                            listener.onBatchProgress(batchId, 0, 0, "Model " + model + " unavailable (" + code + "). Switching to " + nextModel + "...");
                        }
                    } else if (code == 400) {
                        // Bad Request: payload too large or invalid image MIME
                        if (hasMediaParts(activeParts)) {
                            Log.w(TAG, "HTTP 400 Bad Request with multimodal payload. Emergency fallback: stripping media parts to pure text...");
                            activeParts = stripMediaParts(activeParts);
                            if (listener != null) {
                                listener.onBatchProgress(batchId, 0, 0, "Payload error (400). Stripping media, retrying as text...");
                            }
                        } else {
                            currentKeyIdx++;
                            currentModelIdx++;
                        }
                    } else {
                        currentKeyIdx++;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Key " + keyDisplayNum + " Model " + model + " error: " + e.getMessage() + ". Rotating key & checking next model...");
                // Option A: keep media on timeout/network error (small chunks retry fine).
                currentKeyIdx++;
                currentModelIdx++;
            } finally {
                if (conn != null) conn.disconnect();
            }

            // Fast throttled 0.4s interval between retries
            Thread.sleep(400);
        }

        throw new RuntimeException("All keys and models in cascade pool failed after retries.");
    }

    private static JSONObject parseGeminiJson(String text) throws Exception {
        if (text == null || text.trim().isEmpty()) {
            throw new RuntimeException("Empty response from Gemini");
        }
        String clean = text.trim();
        // Remove markdown code fences if present (```json ... ```)
        if (clean.startsWith("```")) {
            int firstNewline = clean.indexOf('\n');
            int lastFence = clean.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                clean = clean.substring(firstNewline + 1, lastFence).trim();
            }
        }

        // 1. Try direct JSON parse
        try {
            return new JSONObject(clean);
        } catch (Exception e1) {
            // 2. Try extracting substring between outermost { and }
            int s = clean.indexOf('{');
            int e = clean.lastIndexOf('}');
            if (s >= 0 && e > s) {
                try {
                    return new JSONObject(clean.substring(s, e + 1));
                } catch (Exception ignored) {}
            }
        }

        // 3. Fallback: REGEX RESCUE PARSER
        Log.w(TAG, "Standard JSON parse failed. Initiating Regex Rescue parser...");
        JSONObject rescued = new JSONObject();
        // Match question blocks: "01": { ... } or Soal 01: ...
        Pattern qPattern = Pattern.compile("(?i)(?:\"?0?(\\d{1,2})\"?|Soal\\s*0?(\\d{1,2}))[^:]*:[\\s\n]*\\{([^}]+)\\}", Pattern.DOTALL);
        Matcher m = qPattern.matcher(text);
        while (m.find()) {
            String numStr = m.group(1) != null ? m.group(1) : m.group(2);
            String block = m.group(3);
            if (numStr != null && block != null) {
                try {
                    int num = Integer.parseInt(numStr);
                    String formattedKey = String.format(Locale.US, "%02d", num);
                    JSONObject itemObj = new JSONObject();
                    itemObj.put("number", num);

                    // Extract option
                    Matcher optM = Pattern.compile("(?i)\"option\"\\s*:\\s*\"([A-E])\"").matcher(block);
                    if (optM.find()) {
                        itemObj.put("option", optM.group(1).toUpperCase());
                    }

                    // Extract option_answer
                    Matcher optAnsM = Pattern.compile("(?i)\"option_answer\"\\s*:\\s*\"([^\"]+)\"").matcher(block);
                    if (optAnsM.find()) {
                        itemObj.put("option_answer", optAnsM.group(1));
                    }

                    // Extract answer
                    Matcher ansM = Pattern.compile("(?i)\"answer\"\\s*:\\s*(\"[^\"]+\"|\\[[^\\]]+\\])").matcher(block);
                    if (ansM.find()) {
                        String rawAns = ansM.group(1).replaceAll("^\"|\"$", "").trim();
                        itemObj.put("answer", rawAns);
                    } else if (itemObj.has("option") && itemObj.has("option_answer")) {
                        itemObj.put("answer", itemObj.getString("option") + ". " + itemObj.getString("option_answer"));
                    } else if (itemObj.has("option")) {
                        itemObj.put("answer", itemObj.getString("option"));
                    }

                    // Extract reason or analysis
                    Matcher rsnM = Pattern.compile("(?i)\"(?:reason|analysis|alasan)\"\\s*:\\s*\"([^\"]+)\"").matcher(block);
                    if (rsnM.find()) {
                        itemObj.put("reason", rsnM.group(1));
                    } else {
                        itemObj.put("reason", "Recovered via Regex Rescue");
                    }

                    rescued.put(formattedKey, itemObj);
                } catch (Exception ignored) {}
            }
        }

        // Secondary regex rescue if blocks weren't found
        if (rescued.length() == 0) {
            Pattern simplePattern = Pattern.compile("(?i)(?:\"?0?(\\d{1,2})\"?|Soal\\s*0?(\\d{1,2}))[^:]*:[\\s\n]*\"?(?:answer|jawaban)\"?\\s*:\\s*(\"[^\"]+\"|\\[[^\\]]+\\]|[A-E])", Pattern.DOTALL);
            Matcher sm = simplePattern.matcher(text);
            while (sm.find()) {
                String numStr = sm.group(1) != null ? sm.group(1) : sm.group(2);
                String ansRaw = sm.group(3).replaceAll("^\"|\"$", "").trim();
                if (numStr != null) {
                    try {
                        int num = Integer.parseInt(numStr);
                        String formattedKey = String.format(Locale.US, "%02d", num);
                        JSONObject itemObj = new JSONObject();
                        itemObj.put("number", num);
                        itemObj.put("answer", ansRaw);
                        itemObj.put("reason", "Extracted via Simple Regex Rescue");
                        rescued.put(formattedKey, itemObj);
                    } catch (Exception ignored) {}
                }
            }
        }

        if (rescued.length() > 0) {
            Log.i(TAG, "Regex Rescue successfully recovered " + rescued.length() + " answers!");
            return rescued;
        }

        // 4. Top-level JSON ARRAY (models often return [{...},{...}] for batches).
        // Convert each element with number/nomor into {"01":{...}} map form.
        try {
            int a0 = clean.indexOf('[');
            int a1 = clean.lastIndexOf(']');
            if (a0 >= 0 && a1 > a0) {
                org.json.JSONArray arr = new org.json.JSONArray(clean.substring(a0, a1 + 1));
                JSONObject mapped = new JSONObject();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject el = arr.optJSONObject(i);
                    if (el == null) continue;
                    int num = el.optInt("number", el.optInt("nomor", el.optInt("num", -1)));
                    if (num > 0) {
                        mapped.put(String.format(java.util.Locale.US, "%02d", num), el);
                    }
                }
                if (mapped.length() > 0) {
                    Log.i(TAG, "Parsed top-level array into " + mapped.length() + " answers.");
                    return mapped;
                }
            }
        } catch (Exception ignored) {}

        throw new RuntimeException("Could not parse JSON or rescue answers from model output.");
    }

    /** Canonical matrix form: WHATEVER shape the model emits (CSV string,
     * numbered lines, bare arrays, objects), rewrite to statements[{index,
     * status,text}] + answer["i. Status - text"]. Single-choice objects
     * (option/options present) are never touched. */
    private static void canonicalizeMatrix(JSONObject qObj) {
        if (qObj == null) return;
        try {
            if (hasRealOption(qObj)) return; // single/multi: not matrix
            Object a = qObj.opt("answer");
            java.util.List<String> rows = new java.util.ArrayList<>();
            if (a instanceof JSONArray) {
                JSONArray arr = (JSONArray) a;
                boolean needsFix = false;
                for (int i = 0; i < arr.length(); i++) {
                    String e = arr.optString(i, "");
                    if (!e.matches("^\\s*\\d+\\s*[.\\)\\-].*")) needsFix = true;
                    rows.add(e);
                }
                if (!needsFix && qObj.has("statements")) return; // already canonical
                if (!needsFix && !hasStatusWord(String.join(" ", rows))) return;
            } else if (a instanceof String) {
                String s = ((String) a).trim();
                if (s.isEmpty() || s.equals("-") || s.equals("none")) return;
                if (qObj.has("statements")) return;
                String[] parts = s.split("[\\n;,]+");
                for (String p : parts) {
                    if (!p.trim().isEmpty()) rows.add(p.trim());
                }
                if (rows.size() < 2 && !hasStatusWord(s)) return; // not a matrix: leave alone
            } else {
                return;
            }
            JSONArray stmts = new JSONArray();
            JSONArray ans = new JSONArray();
            int idx = 0;
            for (String r : rows) {
                String st = rowStatus(r);
                if (st == null) continue;
                idx++;
                java.util.regex.Matcher nm =
                        java.util.regex.Pattern.compile("^\\s*(\\d+)\\s*[.\\)\\-]").matcher(r);
                int useIdx = idx;
                if (nm.find()) {
                    try { useIdx = Integer.parseInt(nm.group(1)); } catch (Exception ignored) {}
                }
                String clean = r.replaceAll("^\\s*\\d+\\s*[.\\)\\-]\\s*", "").trim();
                JSONObject o = new JSONObject();
                o.put("index", useIdx);
                o.put("status", st);
                o.put("text", clean);
                stmts.put(o);
                ans.put(useIdx + ". " + st + (clean.isEmpty() ? "" : " - " + clean));
            }
            if (stmts.length() > 0) {
                qObj.put("statements", stmts);
                qObj.put("answer", ans);
            }
        } catch (Exception ignored) {}
    }

    /** True only for MEANINGFUL choice fields: single letter A-E, or a
     * non-empty options array. Dummy "option": null/""/"-" never vetoes. */
    private static boolean hasRealOption(JSONObject qObj) {
        try {
            Object o = qObj.opt("option");
            if (o instanceof String) {
                String s = ((String) o).trim().toUpperCase(java.util.Locale.US);
                if (s.matches("^[A-E]$")) return true;
            }
            Object os = qObj.opt("options");
            if (os instanceof JSONArray) {
                if (((JSONArray) os).length() > 0) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static boolean hasStatusWord(String s) {
        if (s == null) return false;
        String t = s.toLowerCase(java.util.Locale.US);
        return t.contains("benar") || t.contains("salah") || t.contains("tidak") || t.contains("true") || t.contains("false");
    }

    /** Verdict-priority status match.
     * Evaluates leading verdict token before any separator ('-', ':', etc.)
     * so that words like "tidak dapat" inside explanations like "1. Benar - Pernyataan tidak dapat dibantah"
     * are never misclassified as "Tidak". */
    private static String rowStatus(String r) {
        if (r == null) return null;
        String t = r.trim().toLowerCase(java.util.Locale.US);

        // Check if there is a leading verdict token before any separator ('-', ':')
        int sepIdx = t.indexOf('-');
        int colonIdx = t.indexOf(':');
        int splitAt = -1;
        if (sepIdx > 0 && colonIdx > 0) splitAt = Math.min(sepIdx, colonIdx);
        else if (sepIdx > 0) splitAt = sepIdx;
        else if (colonIdx > 0) splitAt = colonIdx;

        if (splitAt > 0) {
            String prefix = t.substring(0, splitAt).trim();
            if (prefix.contains("tidak benar") || prefix.contains("tidak tepat") || prefix.contains("tidak sesuai")
                    || prefix.contains("tidak valid") || prefix.contains("bukan") || prefix.contains("salah") || prefix.contains("false")
                    || prefix.endsWith(" b") || prefix.equals("b")) return "Salah";
            if (prefix.contains("tidak dapat") || prefix.contains("undetermined") || prefix.contains("tidak pasti")
                    || prefix.endsWith(" c") || prefix.equals("c")) return "Tidak";
            if (prefix.contains("benar") || prefix.contains("true")
                    || prefix.endsWith(" a") || prefix.equals("a")) return "Benar";
        }

        // Full line fallback
        if (t.contains("tidak benar") || t.contains("tidak tepat") || t.contains("tidak sesuai")
                || t.contains("tidak valid") || t.contains("bukan")) return "Salah";
        if (t.contains("salah") || t.contains("false")) return "Salah";
        if (t.contains("tidak dapat") || t.contains("cannot") || t.contains("undetermined")
                || t.contains("tidak diketahui") || t.contains("tidak jelas")
                || t.contains("tidak pasti")) return "Tidak";
        if (t.contains("benar") || t.contains("true")) return "Benar";
        return null;
    }

    /** Normalizes a row status to canonical form: "Pasti Benar"/A->Benar,
     * "Pasti Salah"/B->Salah, "Tidak Dapat Ditentukan"/C->Tidak. Unknown stays raw. */
    private static String normalizeStatus(String s) {
        if (s == null) return "?";
        String t = s.trim().toLowerCase(java.util.Locale.US);
        if (t.contains("tidak benar") || t.contains("tidak tepat") || t.contains("bukan")) return "Salah";
        if (t.startsWith("pasti salah") || t.equals("salah") || t.equals("b")
                || t.startsWith("salah")) return "Salah";
        if (t.contains("tidak dapat") || t.contains("cannot") || t.contains("undetermined")
                || t.contains("tidak diketahui") || t.equals("c")) return "Tidak";
        if (t.startsWith("pasti benar") || t.equals("benar") || t.equals("a")
                || t.startsWith("benar")) return "Benar";
        // Bare "tidak ..." (negation without verdict) inside a status field:
        // route through the same verdict-priority matcher as free rows.
        String fallback = rowStatus(s);
        return fallback != null ? fallback : s;
    }

    /** Normalizes a model-emitted key to 2-digit form: "1"->"01", "question_1"/"Q1"/
     * "soal_01"/"#5"->"01". Returns null when the key carries no question number
     * (digitless junk like "analysis" must never pollute the master map). */
    private static String normalizeQKey(String k) {
        if (k == null) return null;
        if (k.matches("^\\d{1,3}$")) {
            try {
                int n = Integer.parseInt(k);
                if (n > 0 && n <= 999) return String.format(java.util.Locale.US, "%02d", n);
            } catch (Exception ignored) {}
            return null;
        }
        java.util.regex.Matcher dm = java.util.regex.Pattern.compile("(\\d{1,3})\\D*$").matcher(k);
        if (dm.find()) {
            try {
                int n = Integer.parseInt(dm.group(1));
                if (n > 0 && n <= 999) return String.format(java.util.Locale.US, "%02d", n);
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static void mergeAnswers(JSONObject master, JSONObject incoming) {
        if (incoming == null) return;
        // Flat single-answer object (e.g. {"number":20,"option":"B",...} with NO
        // numeric keys) -> file it under its own number instead of spreading
        // fields like "analysis"/"answer" across the master map. Observed live
        // on single-question chunks (20Q test, 2026-09-20).
        boolean hasQKey = false;
        Iterator<String> probe = incoming.keys();
        while (probe.hasNext()) {
            if (normalizeQKey(probe.next()) != null) { hasQKey = true; break; }
        }
        if (!hasQKey) {
            int num = incoming.optInt("number", incoming.optInt("nomor", incoming.optInt("num", -1)));
            if (num > 0) {
                try {
                    master.put(String.format(java.util.Locale.US, "%02d", num), incoming);
                } catch (Exception ignored) {}
                return;
            }
        }
        Iterator<String> keys = incoming.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            if (k.startsWith("_")) continue; // _model/_key sidecars, stamped per-question below
            try {
                String formattedKey = normalizeQKey(k);
                if (formattedKey == null) continue; // digitless junk: drop, never autofill-bait
                master.put(formattedKey, incoming.get(k));
            } catch (Exception ignored) {}
        }
    }

    private static String buildAnswerString(JSONObject qObj) {
        if (qObj == null) return "-";

        // 1. Try "answer" / "jawaban" / "pilihan" / "opsi" / "result"
        Object rawAns = qObj.opt("answer");
        if (rawAns == null || rawAns.equals("") || rawAns.equals("-") || rawAns.equals("none")) {
            rawAns = qObj.opt("jawaban");
        }
        if (rawAns == null || rawAns.equals("") || rawAns.equals("-") || rawAns.equals("none")) {
            rawAns = qObj.opt("pilihan");
        }
        if (rawAns == null || rawAns.equals("") || rawAns.equals("-") || rawAns.equals("none")) {
            rawAns = qObj.opt("opsi");
        }
        if (rawAns == null || rawAns.equals("") || rawAns.equals("-") || rawAns.equals("none")) {
            rawAns = qObj.opt("result");
        }

        if (rawAns instanceof String) {
            String s = cleanAnswerString((String) rawAns);
            if (!s.isEmpty() && !s.equals("none") && !s.equals("-")) return s;
        } else if (rawAns instanceof JSONArray) {
            JSONArray arr = (JSONArray) rawAns;
            if (arr.length() > 0) {
                String first = arr.optString(0, "").trim();
                if (first.matches("^\\d+\\..*") || hasStatusWord(first)) {
                    // Benar/Salah style — join with newline for HUD
                    List<String> rows = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) rows.add(arr.optString(i));
                    return String.join("\n", rows);
                }
                // Multi-select (Pilihan Ganda Kompleks): extract just the letters
                List<String> letters = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    String entry = arr.optString(i, "").replaceAll("[^A-Ea-e]", "").trim();
                    if (!entry.isEmpty()) {
                        letters.add(entry.substring(0, 1).toUpperCase(Locale.US));
                    }
                }
                if (!letters.isEmpty()) return String.join(", ", letters);
            }
        }

        // 2. Try "statements" field (Benar/Salah matrix) -> compact "1T 2F 3T" (any count).
        Object rawStmts = qObj.opt("statements");
        if (rawStmts instanceof JSONArray) {
            JSONArray arr = (JSONArray) rawStmts;
            List<String> rows = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject stmt = arr.optJSONObject(i);
                if (stmt != null) {
                    int idx = stmt.optInt("index", i + 1);
                    String status = normalizeStatus(stmt.optString("status", "?"));
                    String tf = status.equals("Benar") ? "T" : (status.equals("Salah") ? "F" : "?");
                    rows.add(idx + tf);
                }
            }
            if (!rows.isEmpty()) return String.join(" ", rows);
        }

        // 3. Try "options" (array or comma-separated string like ["A","C","D"] or "A, C, D")
        Object rawOpts = qObj.opt("options");
        if (rawOpts instanceof JSONArray) {
            JSONArray arr = (JSONArray) rawOpts;
            List<String> letters = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                String entry = arr.optString(i, "").replaceAll("[^A-Ea-e]", "").trim();
                if (!entry.isEmpty()) letters.add(entry.toUpperCase(Locale.US).substring(0, 1));
            }
            if (!letters.isEmpty()) return String.join(", ", letters);
        } else if (rawOpts instanceof String) {
            String s = (String) rawOpts;
            Matcher m = Pattern.compile("(?i)\\b([A-E])\\b").matcher(s);
            List<String> letters = new ArrayList<>();
            while (m.find()) letters.add(m.group(1).toUpperCase(Locale.US));
            if (!letters.isEmpty()) return String.join(", ", letters);
        }

        // 4. Try "option" / "opsi" / "pilihan" + "option_answer" (single choice)
        String opt = qObj.optString("option", qObj.optString("opsi", qObj.optString("pilihan", "")));
        opt = opt.replaceAll("[^A-Ea-e]", "").trim().toUpperCase(Locale.US);
        if (!opt.isEmpty()) {
            String optAns = qObj.optString("option_answer", qObj.optString("teks_opsi", ""));
            return optAns.isEmpty() ? opt : opt + ". " + optAns;
        }

        // 5. RESCUE: Extract answer from "analysis" or "reason" if model solved it in reasoning
        String analysis = qObj.optString("analysis", "") + " " + qObj.optString("reason", "") + " " + qObj.optString("alasan", "");
        if (!analysis.trim().isEmpty()) {
            Matcher m = Pattern.compile("(?i)(?:jawaban(?:\\s+yang\\s+(?:benar|tepat))?|pilihan(?:\\s+yang\\s+(?:benar|tepat))?|kunci(?:\\s+jawaban)?|option|opsi)\\s*(?:adalah|merupakan|:)?\\s*\\**([A-E])\\**\\b").matcher(analysis);
            if (m.find()) {
                return m.group(1).toUpperCase(Locale.US);
            }
        }

        return "-";
    }

    private static String cleanAnswerString(String s) {
        if (s == null) return "";
        String trimmed = s.trim();
        // Remove markdown bold/code backticks e.g. **A** or `A`
        trimmed = trimmed.replaceAll("^\\*+|\\*+$", "").replaceAll("^`+|`+$", "").trim();
        // If it's single letter with parentheses like "(A)" or "A."
        Matcher m = Pattern.compile("^\\(?([A-Ea-e])\\)?[.:]?$").matcher(trimmed);
        if (m.find()) return m.group(1).toUpperCase(Locale.US);
        return trimmed;
    }

    private static void sendProgressFrame(OutputStream btOut, int qNum, JSONObject qObj, String ans, String reason) {
        if (btOut == null) return;
        try {
            JSONObject progObj = new JSONObject();
            progObj.put("num", qNum);
            progObj.put("number", qNum);
            progObj.put("nomor", qNum);
            if (qObj != null) {
                if (qObj.has("option")) progObj.put("option", qObj.opt("option"));
                if (qObj.has("option_answer")) progObj.put("option_answer", qObj.opt("option_answer"));
                if (qObj.has("options")) progObj.put("options", qObj.opt("options"));
                if (qObj.has("option_answers")) progObj.put("option_answers", qObj.opt("option_answers"));
                if (qObj.has("statements")) progObj.put("statements", qObj.opt("statements"));
            }
            progObj.put("jawaban", ans);
            progObj.put("answer", ans);
            progObj.put("alasan", reason);
            progObj.put("reason", reason);
            byte[] b = progObj.toString().getBytes(StandardCharsets.UTF_8);
            MuxProtocol.writeFrame(btOut, (short) qNum, MuxProtocol.CMD_PROGRESS, b);
        } catch (Exception ignored) {}
    }

    private static void sendErrorAnswers(OutputStream btOut, String batchId, String err) {
        if (btOut == null) return;
        try {
            JSONObject errObj = new JSONObject();
            errObj.put("error", err);
            errObj.put("batchId", batchId);
            byte[] b = errObj.toString().getBytes(StandardCharsets.UTF_8);
            MuxProtocol.writeFrame(btOut, (short) 0, MuxProtocol.CMD_ANSWERS, b);
        } catch (Exception ignored) {}
    }

    private static String readFileToString(File f) throws Exception {
        byte[] b = readFileToBytes(f);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static byte[] readFileToBytes(File f) throws Exception {
        byte[] b = new byte[(int) f.length()];
        try (FileInputStream fis = new FileInputStream(f)) {
            int offset = 0;
            while (offset < b.length) {
                int read = fis.read(b, offset, b.length - offset);
                if (read < 0) break;
                offset += read;
            }
        }
        return b;
    }
}

