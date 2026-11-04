package com.bluebridge.app;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Chatbot solver: NVIDIA Nemotron via OpenRouter first (up to 3 tries),
 * then Cerebras gpt-oss-120b (up to 3 tries). Short answers for the HUD.
 * Keys arrive per-request over Bluetooth and are never persisted. Only model
 * names go to the log — never key material.
 */
public class ChatSolver {
    private static final String TAG = "ChatSolver";
    private static final String ONOMEO_URL = "https://onomeo.com/v1/chat/completions";
    private static final String ONOMEO_MODEL = "deepseek-v4-flash";
    private static final String OR_URL = "https://openrouter.ai/api/v1/chat/completions";
    // Primary: Onomeo DeepSeek-V4 Flash -> OpenRouter Nemotron 3 Ultra -> Llama 3.3 70B.
    private static final String OR_MODEL1 = "nvidia/nemotron-3-ultra-550b-a55b:free";
    private static final String OR_MODEL2 = "meta-llama/llama-3.3-70b-instruct:free";

    /** Single in-flight chat: rapid \-spam gets an instant busy answer, never a thread pile-up. */
    private static final java.util.concurrent.atomic.AtomicBoolean BUSY = new java.util.concurrent.atomic.AtomicBoolean(false);
    /** Overall deadline per chat so a dead session can't burn 8+ minutes of retries. */
    private static final long CHAT_DEADLINE_MS = 240000L;

    public static void solveChatAsync(final OutputStream btOut, final String question,
                                      final String orKey, final String onomeoKey) {
        if (!BUSY.compareAndSet(false, true)) {
            sendAnswer(btOut, question, "-", "busy");
            return;
        }
        new Thread(() -> {
            BridgeService.holdCpu();
            try {
            String answer = null;
            String usedModel = null;
            long deadline = android.os.SystemClock.elapsedRealtime() + CHAT_DEADLINE_MS;
            String[][] stages = {
                    {ONOMEO_URL, onomeoKey, ONOMEO_MODEL, "deepseek-v4-flash"},
                    {OR_URL, orKey, OR_MODEL1, "nemotron"},
                    {OR_URL, orKey, OR_MODEL2, "llama-3.3"},
            };
            for (String[] st : stages) {
                if (st[1] == null || st[1].trim().isEmpty()) continue;
                for (int i = 0; i < 2 && answer == null; i++) {
                    if (BatchManager.CANCEL_ALL || android.os.SystemClock.elapsedRealtime() > deadline) break;
                    answer = chatOnce(st[0], st[1], st[2], question, 800);
                    if (answer == null) {
                        Log.w(TAG, st[3] + " attempt " + (i + 1) + "/2 failed, retrying...");
                        sleepQuiet(600);
                    } else {
                        usedModel = st[3];
                    }
                }
                if (answer != null) break;
            }
            if (BatchManager.CANCEL_ALL) answer = null; // cancelled: don't answer a dead session
            sendAnswer(btOut, question, answer != null ? answer : "-",
                    usedModel != null ? usedModel : "none");
            } finally {
                BUSY.set(false);
                BridgeService.releaseCpu();
            }
        }).start();
    }

    private static void sendAnswer(OutputStream btOut, String question, String answer, String model) {
        try {
            JSONObject out = new JSONObject();
            out.put("question", question != null ? question : "");
            out.put("answer", AiSolver.normalizeMath(answer != null ? answer : "-"));
            out.put("model", model != null ? model : "none");
            byte[] b = out.toString().getBytes(StandardCharsets.UTF_8);
            MuxProtocol.writeFrame(btOut, (short) 0, MuxProtocol.CMD_CHAT_ANS, b);
            Log.d(TAG, "Chat answered via " + model + " (" + b.length + " bytes).");
        } catch (Exception e) {
            Log.w(TAG, "Chat reply failed: " + e.getMessage());
        }
    }

    private static String chatOnce(String endpoint, String key, String model,
                                   String question, int maxTokens) {
        if (key == null || key.isEmpty()) return null;
        HttpURLConnection conn = null;
        try {
            JSONObject root = new JSONObject();
            root.put("model", model);
            root.put("max_tokens", maxTokens);
            root.put("temperature", 0.2);
            root.put("top_p", 0.95);
            JSONArray msgs = new JSONArray();
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", "You are an elite, highly precise study assistant. "
                    + "LANGUAGE LOCK: Answer strictly in the SAME language as the question (Indonesian questions -> Bahasa Indonesia; English -> English). "
                    + "DEFAULT LENGTH: Concise and direct — 1 single tight paragraph. No preamble, no introductory remarks, no markdown fences. "
                    + "If user explicitly requests length (e.g., word count or detailed essay), follow instruction strictly without unnecessary filler. "
                    + "MATH FORMATTING: Strictly plain Unicode on a single line — e.g. (a)/(b), √(x), x², ± × ÷ · ≤ ≥ ≠ ≈ π θ. "
                    + "NEVER use $, $$, \\frac, \\sqrt, or any LaTeX backslash commands.");
            JSONObject usr = new JSONObject();
            usr.put("role", "user");
            usr.put("content", question);
            msgs.put(sys);
            msgs.put(usr);
            root.put("messages", msgs);

            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + key);
            conn.setDoOutput(true);
            byte[] body = root.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }
            int code = conn.getResponseCode();
            InputStream in = (code == 200) ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (in != null) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
            }
            if (code != 200) {
                Log.w(TAG, model + " HTTP " + code + ": " + sb.toString().substring(0, Math.min(120, sb.length())));
                return null;
            }
            JSONObject resp = new JSONObject(sb.toString());
            JSONArray choices = resp.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return null;
            // NPE-safe: OpenRouter error shapes can omit message/content entirely.
            JSONObject first = choices.optJSONObject(0);
            JSONObject msg = first != null ? first.optJSONObject("message") : null;
            String text = "";
            if (msg != null) {
                text = msg.optString("content", "").trim();
                if (text.isEmpty()) {
                    text = msg.optString("reasoning_content", "").trim();
                }
            }
            return text.isEmpty() ? null : text;
        } catch (Exception e) {
            Log.w(TAG, model + " error: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
