package com.bluebridge.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements BridgeService.StatusListener, BatchManager.BatchListener {
    private TextView tvStatus;
    private TextView tvConnectedDevice;
    private TextView tvDataTransferred;
    private RadioGroup rgSolverMode;
    private RadioButton rbMode1;
    private RadioButton rbMode10;
    private TextView tvTransparency;
    private RadioButton rbMode2;
    private TextView tvModeDesc;
    private TextView tvBatchInfo;
    private TextView tvItemDetails;
    private ProgressBar pbBatchProgress;
    private TextView tvSolverLog;
    private TextView tvBatchSummary;
    private Button btnToggle;

    // Batch History & Storage Management
    private Button btnRefreshBatches;
    private Button btnClearAllBatches;
    private TextView tvStorageInfo;
    private LinearLayout llBatchHistoryContainer;

    // Phone Health (screen-off diagnosis)
    private TextView tvHealth;
    private Button btnFixBackground;
    private Button btnSelfTest;

    // Tab Bar & Containers
    private Button btnTabStandard;
    private Button btnTabAlpha;
    private LinearLayout layoutStandard;
    private LinearLayout layoutAlpha;

    // Alpha UI
    private TextView tvAlphaStatus;
    private Button btnRefreshAlpha;
    private LinearLayout llAlphaAnswersContainer;

    private final BroadcastReceiver alphaReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshAlphaUi();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(android.content.Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                }
            } catch (Exception ignored) {}
        }

        tvStatus = findViewById(R.id.tvStatus);
        tvConnectedDevice = findViewById(R.id.tvConnectedDevice);
        tvDataTransferred = findViewById(R.id.tvDataTransferred);
        rgSolverMode = findViewById(R.id.rgSolverMode);
        rbMode1 = findViewById(R.id.rbMode1);
        rbMode2 = findViewById(R.id.rbMode2);
        tvModeDesc = findViewById(R.id.tvModeDesc);
        tvBatchInfo = findViewById(R.id.tvBatchInfo);
        tvItemDetails = findViewById(R.id.tvItemDetails);
        pbBatchProgress = findViewById(R.id.pbBatchProgress);
        tvSolverLog = findViewById(R.id.tvSolverLog);
        tvBatchSummary = findViewById(R.id.tvBatchSummary);
        btnToggle = findViewById(R.id.btnToggle);

        btnRefreshBatches = findViewById(R.id.btnRefreshBatches);
        btnClearAllBatches = findViewById(R.id.btnClearAllBatches);
        tvStorageInfo = findViewById(R.id.tvStorageInfo);
        llBatchHistoryContainer = findViewById(R.id.llBatchHistoryContainer);

        tvHealth = findViewById(R.id.tvHealth);
        btnFixBackground = findViewById(R.id.btnFixBackground);
        btnSelfTest = findViewById(R.id.btnSelfTest);
        rbMode10 = findViewById(R.id.rbMode10);
        tvTransparency = findViewById(R.id.tvTransparency);

        btnTabStandard = findViewById(R.id.btnTabStandard);
        btnTabAlpha = findViewById(R.id.btnTabAlpha);
        layoutStandard = findViewById(R.id.layoutStandard);
        layoutAlpha = findViewById(R.id.layoutAlpha);

        tvAlphaStatus = findViewById(R.id.tvAlphaStatus);
        btnRefreshAlpha = findViewById(R.id.btnRefreshAlpha);
        llAlphaAnswersContainer = findViewById(R.id.llAlphaAnswersContainer);

        btnTabStandard.setOnClickListener(v -> switchTab(false));
        btnTabAlpha.setOnClickListener(v -> switchTab(true));
        btnRefreshAlpha.setOnClickListener(v -> refreshAlphaUi());

        // Load saved mode preference (1 / 5 / 10 calls)
        if (getIntent() != null && getIntent().hasExtra("mode")) {
            int m = getIntent().getIntExtra("mode", AiSolver.MODE_5_CALL);
            AiSolver.setSavedMode(this, m);
        }
        int currentMode = AiSolver.getSavedMode(this);
        applyModeUi(currentMode);

        rgSolverMode.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rbMode1) {
                AiSolver.setSavedMode(MainActivity.this, AiSolver.MODE_1_CALL);
            } else if (checkedId == R.id.rbMode10) {
                AiSolver.setSavedMode(MainActivity.this, AiSolver.MODE_10_CALL);
            } else {
                AiSolver.setSavedMode(MainActivity.this, AiSolver.MODE_5_CALL);
            }
            applyModeUi(AiSolver.getSavedMode(MainActivity.this));
        });

        btnToggle.setOnClickListener(v -> toggleService());
        btnRefreshBatches.setOnClickListener(v -> { refreshBatchHistory(); refreshHealth(); refreshTransparency(); });
        btnClearAllBatches.setOnClickListener(v -> confirmClearAllBatches());
        btnFixBackground.setOnClickListener(v -> fixBackgroundRunning());
        btnSelfTest.setOnClickListener(v -> startScreenOffSelfTest());
        tvTransparency.setOnClickListener(v -> showTransparencyDialog());

        if (hasRequiredBluetoothPermissions()) {
            startBridgeService();
        } else {
            checkAndRequestPermissions();
        }

        refreshBatchHistory();
        refreshTransparency();
    }

    /** Syncs radio + description for the 3 chunk-count modes. */
    private void applyModeUi(int mode) {
        if (mode == AiSolver.MODE_1_CALL) {
            rbMode1.setChecked(true);
            tvModeDesc.setText("Mode 1: everything in 1 single call. Fastest, hungriest.");
        } else if (mode == AiSolver.MODE_10_CALL) {
            rbMode10.setChecked(true);
            tvModeDesc.setText("Mode 10: deal into 10 calls (~1-3 Qs each). Most resilient.");
        } else {
            rbMode2.setChecked(true);
            tvModeDesc.setText("Mode 5: deal into 5 calls. Balanced accuracy vs quota.");
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.hasExtra("mode")) {
            int m = intent.getIntExtra("mode", AiSolver.MODE_5_CALL);
            AiSolver.setSavedMode(this, m);
            applyModeUi(m);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        BridgeService.setStatusListener(this);
        BatchManager.setListener(this);
        updateButtonState();
        refreshBatchSummary();
        refreshBatchHistory();
        refreshHealth();
        refreshTransparency();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(alphaReceiver, new IntentFilter("com.bluebridge.app.ALPHA_UPDATED"), Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(alphaReceiver, new IntentFilter("com.bluebridge.app.ALPHA_UPDATED"));
            }
        } catch (Exception ignored) {}
    }

    /** Phone health card: tells the user EXACTLY why screen-off kills the link. */
    private void refreshHealth() {
        StringBuilder sb = new StringBuilder();
        sb.append(BridgeService.isServiceRunning ? "[OK] Service running" : "[DOWN] Service stopped");
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            boolean opt = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
            sb.append(opt ? " | [OK] Battery unrestricted" : " | [!] Battery optimized (tap Fix)");
        } catch (Exception ignored) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                boolean restr = am != null && am.isBackgroundRestricted();
                sb.append(restr ? " | [!] Background RESTRICTED" : " | [OK] Background allowed");
            } catch (Exception ignored) {}
            try {
                // Memory pressure killed a full batch with ~35MB free (19 instant
                // nones). Surface it here so low RAM is visible BEFORE solving.
                ActivityManager am2 = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                if (am2 != null) {
                    am2.getMemoryInfo(mi);
                    long freeMb = mi.availMem / 1048576L;
                    sb.append(mi.lowMemory || freeMb < 150
                            ? " | [!] Mem LOW (" + freeMb + "MB free — close apps!)"
                            : " | Mem OK (" + freeMb + "MB)");
                }
            } catch (Exception ignored) {}
        }
        long last = getSharedPreferences("blue_health", MODE_PRIVATE).getLong("last_bt_contact", 0);
        if (last > 0) {
            long s = (System.currentTimeMillis() - last) / 1000;
            sb.append(" | Mac: ").append(s < 90 ? s + "s ago" : (s / 60) + "m ago");
        } else {
            sb.append(" | Mac: never connected");
        }
        android.content.SharedPreferences hp = getSharedPreferences("blue_health", MODE_PRIVATE);
        long tst = hp.getLong("selftest_start", 0);
        if (tst > 0) {
            long elapsed = System.currentTimeMillis() - tst;
            if (elapsed >= 120000) {
                // Heartbeat coverage: did the service get CPU through the window?
                // Frozen time leaves beat gaps; steady beats mean survived.
                int n = 0;
                long first = 0, lastB = 0;
                String beats = hp.getString("selftest_beats", "");
                if (beats != null && !beats.isEmpty()) {
                    for (String p : beats.split(",")) {
                        try {
                            long t = Long.parseLong(p.trim());
                            if (t >= tst) {
                                n++;
                                if (first == 0) first = t;
                                lastB = t;
                            }
                        } catch (Exception ignored2) {}
                    }
                }
                // 60s beats: an alive phone shows 2+ by ~150s. Before that a
                // missing 2nd beat only means "too early", never FAIL (that race
                // caused the old flip-flop). Definitive FAIL only after 3 min.
                boolean pass = n >= 2;
                // Fresh Mac contact also counts (link provably alive while dark).
                if (!pass && last > tst) pass = true;
                if (pass) {
                    sb.append(" | SELF-TEST: PASS (alive through lock)");
                } else if (elapsed < 180000) {
                    sb.append(" | SELF-TEST: confirming... reopen in 30s");
                } else {
                    sb.append(" | SELF-TEST: FAIL (frozen — fix background)");
                }
            } else {
                sb.append(" | SELF-TEST running... lock the phone");
            }
        }
        tvHealth.setText(sb.toString().trim());
    }

    private String transparencyFull = "";

    /** Transparency card: per-question model/key from latest answers + live attempt log.
     * Tap the card to expand the full log in a dialog (card shows a preview). */
    private void refreshTransparency() {
        StringBuilder sb = new StringBuilder();
        try {
            File baseDir = getExternalFilesDir("batches");
            File latest = null;
            if (baseDir != null && baseDir.exists()) {
                File[] ds = baseDir.listFiles(File::isDirectory);
                if (ds != null) for (File d : ds) {
                    if (latest == null || d.lastModified() > latest.lastModified()) latest = d;
                }
            }
            if (latest != null) {
                File ans = new File(latest, "answers.json");
                if (ans.exists()) {
                    String js = new String(java.nio.file.Files.readAllBytes(ans.toPath()), StandardCharsets.UTF_8);
                    JSONObject obj = new JSONObject(js);
                    List<String> ks = new ArrayList<>();
                    Iterator<String> it = obj.keys();
                    while (it.hasNext()) { String k = it.next(); if (!k.startsWith("_")) ks.add(k); }
                    Collections.sort(ks);
                    sb.append("Batch ").append(latest.getName()).append(":\n");
                    for (String k : ks) {
                        JSONObject q = obj.optJSONObject(k);
                        if (q == null) continue;
                        Object a = q.opt("answer");
                        String aStr = a != null ? a.toString() : "-";
                        if (aStr.length() > 18) aStr = aStr.substring(0, 18) + "..";
                        boolean auto = "auto-filled".equals(q.optString("confidence", ""));
                        sb.append("Q").append(k).append(": ").append(auto ? "? " : "").append(aStr)
                          .append(" [").append(q.optString("model", "?"))
                          .append("/k").append(q.optInt("key", -1)).append("]\n");
                    }
                } else {
                    sb.append("Latest batch has no answers yet.\n");
                }
            } else {
                sb.append("No batches yet.\n");
            }
        } catch (Exception e) {
            sb.append("Transparency read error.\n");
        }
        List<String> log = AiSolver.getAttemptLog();
        if (!log.isEmpty()) {
            sb.append("--- attempts (live) ---\n");
            int from = Math.max(0, log.size() - 8);
            for (int i = from; i < log.size(); i++) sb.append(log.get(i)).append("\n");
        }
        transparencyFull = sb.toString().trim();
        // Card preview: per-question lines + last 3 attempts only
        String[] lines = transparencyFull.split("\n");
        StringBuilder prev = new StringBuilder();
        int shown = 0;
        for (String ln : lines) {
            if (ln.startsWith("Q") || ln.startsWith("Batch") || ln.startsWith("No ") || ln.startsWith("Latest")) {
                prev.append(ln).append("\n");
                shown++;
            }
        }
        int tailFrom = Math.max(0, log.size() - 3);
        for (int i = tailFrom; i < log.size(); i++) prev.append(log.get(i)).append("\n");
        if (shown == 0 && log.isEmpty()) prev.append(transparencyFull);
        tvTransparency.setText(prev.toString().trim() + "\n(Tap to expand)");
    }

    private void showTransparencyDialog() {
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(transparencyFull.isEmpty() ? "No solve yet." : transparencyFull);
        tv.setTextColor(Color.parseColor("#FFFFFF"));
        tv.setTextSize(13);
        tv.setTypeface(Typeface.MONOSPACE);
        int p = dpToPx(16);
        tv.setPadding(p, p, p, p);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("AI Transparency (full)")
                .setView(sv)
                .setPositiveButton("Close", null)
                .show();
    }

    /** One-tap guide to the background whitelist.
     * Verified on CPH2631: no safecenter package exists, so Oppo deep-links are
     * dead — go straight to App info where Battery usage lives. */
    private void fixBackgroundRunning() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(android.net.Uri.parse("package:" + getPackageName()));
                startActivity(i);
                Toast.makeText(this, "Accept: allow app to always run in background",
                        Toast.LENGTH_LONG).show();
                return;
            }
        } catch (Exception ignored) {}
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(i);
            Toast.makeText(this, "Tap Battery usage > Allow background activity ON (+ Auto-launch if shown)",
                    Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {}
        refreshHealth();
    }

    /** 2-minute screen-off self-test: lock the phone, reopen, result is computed. */
    private void startScreenOffSelfTest() {
        getSharedPreferences("blue_health", MODE_PRIVATE)
                .edit().putLong("selftest_start", System.currentTimeMillis())
                .putString("selftest_beats", "").apply();
        Toast.makeText(this, "Now LOCK the phone for 2 minutes, then reopen this screen",
                Toast.LENGTH_LONG).show();
    }

    private void switchTab(boolean isAlpha) {
        if (isAlpha) {
            btnTabAlpha.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#00E5FF")));
            btnTabAlpha.setTextColor(Color.BLACK);
            btnTabStandard.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#2C2C2E")));
            btnTabStandard.setTextColor(Color.parseColor("#888888"));
            layoutStandard.setVisibility(View.GONE);
            layoutAlpha.setVisibility(View.VISIBLE);
            refreshAlphaUi();
        } else {
            btnTabStandard.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#00E5FF")));
            btnTabStandard.setTextColor(Color.BLACK);
            btnTabAlpha.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#2C2C2E")));
            btnTabAlpha.setTextColor(Color.parseColor("#888888"));
            layoutStandard.setVisibility(View.VISIBLE);
            layoutAlpha.setVisibility(View.GONE);
        }
    }

    private static String readFullFile(File f) {
        try {
            long flen = f.length();
            if (flen <= 0 || flen > 8 * 1024 * 1024) return null;
            byte[] b = new byte[(int) flen];
            FileInputStream fis = new FileInputStream(f);
            try {
                int off = 0;
                while (off < b.length) {
                    int r = fis.read(b, off, b.length - off);
                    if (r < 0) break;
                    off += r;
                }
            } finally {
                fis.close();
            }
            return new String(b, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private void refreshAlphaUi() {
        runOnUiThread(() -> {
            File baseDir = getExternalFilesDir("batches");
            File latestAlpha = (baseDir != null) ? new File(baseDir, "latest_alpha.json") : null;
            if (latestAlpha == null || !latestAlpha.exists()) {
                if (baseDir != null && baseDir.exists()) {
                    File[] dirs = baseDir.listFiles(File::isDirectory);
                    if (dirs != null) {
                        Arrays.sort(dirs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                        for (File d : dirs) {
                            File f = new File(d, "alpha_answers.json");
                            if (f.exists() && f.length() > 0) {
                                latestAlpha = f;
                                break;
                            }
                        }
                    }
                }
            }

            if (latestAlpha == null || !latestAlpha.exists()) {
                tvAlphaStatus.setText("No Alpha batch solved yet.\nPress '9' on Mac or run ./blue --alpha to solve.");
                llAlphaAnswersContainer.removeAllViews();
                return;
            }

            try {
                String content = readFullFile(latestAlpha);
                if (content == null) {
                    tvAlphaStatus.setText("Failed reading latest Alpha file.");
                    return;
                }
                JSONObject root = new JSONObject(content);
                String batchId = root.optString("batch_id", "alpha_batch");
                double duration = root.optDouble("solve_duration_sec", 0.0);
                int totalQ = root.optInt("total_questions", 0);
                String solvedAt = root.optString("solved_at", "");

                tvAlphaStatus.setText(String.format(Locale.US,
                        "Batch: %s\nQuestions: %d | Solved in: %.1fs\nTimestamp: %s",
                        batchId, totalQ, duration, solvedAt));

                llAlphaAnswersContainer.removeAllViews();
                JSONObject answers = root.optJSONObject("answers");
                if (answers != null) {
                    Iterator<String> keys = answers.keys();
                    List<String> keyList = new ArrayList<>();
                    while (keys.hasNext()) keyList.add(keys.next());
                    Collections.sort(keyList);

                    for (String k : keyList) {
                        JSONObject qObj = answers.getJSONObject(k);
                        int num = qObj.optInt("nomor", 0);
                        if (num == 0) {
                            try { num = Integer.parseInt(k.replaceAll("\\D", "")); } catch (Exception ignored) {}
                        }
                        String tipe = qObj.optString("tipe", "pilihan_ganda");
                        String jaw = qObj.optString("jawaban", "-");
                        String rsn = qObj.optString("penjelasan", "");

                        LinearLayout card = new LinearLayout(this);
                        card.setOrientation(LinearLayout.VERTICAL);
                        card.setBackgroundColor(Color.parseColor("#1C1C1E"));
                        int p = dpToPx(10);
                        card.setPadding(p, p, p, p);
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                        lp.bottomMargin = dpToPx(8);
                        card.setLayoutParams(lp);

                        LinearLayout topRow = new LinearLayout(this);
                        topRow.setOrientation(LinearLayout.HORIZONTAL);

                        TextView tvQ = new TextView(this);
                        tvQ.setText(String.format(Locale.US, "[Q %02d]", num));
                        tvQ.setTextColor(Color.parseColor("#00E5FF"));
                        tvQ.setTextSize(13);
                        tvQ.setTypeface(null, Typeface.BOLD);
                        topRow.addView(tvQ);

                        TextView tvType = new TextView(this);
                        tvType.setText(String.format(Locale.US, "  •  %s", tipe));
                        tvType.setTextColor(Color.parseColor("#AAAAAA"));
                        tvType.setTextSize(11);
                        topRow.addView(tvType);

                        card.addView(topRow);

                        TextView tvAns = new TextView(this);
                        tvAns.setText(jaw);
                        tvAns.setTextColor(Color.parseColor("#00E676"));
                        tvAns.setTextSize(16);
                        tvAns.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                        tvAns.setPadding(0, dpToPx(4), 0, dpToPx(4));
                        card.addView(tvAns);

                        if (!rsn.isEmpty()) {
                            TextView tvRsn = new TextView(this);
                            tvRsn.setText(rsn);
                            tvRsn.setTextColor(Color.parseColor("#CCCCCC"));
                            tvRsn.setTextSize(11);
                            card.addView(tvRsn);
                        }

                        llAlphaAnswersContainer.addView(card);
                    }
                }
            } catch (Exception e) {
                tvAlphaStatus.setText("Error reading Alpha answers: " + e.getMessage());
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        BridgeService.setStatusListener(null);
        BatchManager.setListener(null);
        try {
            unregisterReceiver(alphaReceiver);
        } catch (Exception ignored) {}
    }

    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            List<String> perms = new ArrayList<>();
            if (checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != PackageManager.PERMISSION_GRANTED) {
                perms.add("android.permission.BLUETOOTH_CONNECT");
            }
            if (checkSelfPermission("android.permission.BLUETOOTH_ADVERTISE") != PackageManager.PERMISSION_GRANTED) {
                perms.add("android.permission.BLUETOOTH_ADVERTISE");
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                    perms.add("android.permission.POST_NOTIFICATIONS");
                }
            }
            if (!perms.isEmpty()) {
                requestPermissions(perms.toArray(new String[0]), 101);
            }
        }
    }

    private boolean hasRequiredBluetoothPermissions() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || (checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission("android.permission.BLUETOOTH_ADVERTISE") == PackageManager.PERMISSION_GRANTED);
    }

    private void startBridgeService() {
        if (BridgeService.isServiceRunning) return;
        Intent serviceIntent = new Intent(this, BridgeService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != 101) return;
        if (hasRequiredBluetoothPermissions()) {
            startBridgeService();
        } else {
            Toast.makeText(this, "Bluetooth Connect and Advertise permissions are required to start the listener.", Toast.LENGTH_LONG).show();
        }
    }

    private void toggleService() {
        Intent serviceIntent = new Intent(this, BridgeService.class);
        if (BridgeService.isServiceRunning) {
            stopService(serviceIntent);
            btnToggle.setText(R.string.btn_start);
            tvStatus.setText("Status: Stopped");
            tvConnectedDevice.setText("Client: None");
        } else {
            if (hasRequiredBluetoothPermissions()) {
                startBridgeService();
                btnToggle.setText(R.string.btn_stop);
                tvStatus.setText("Status: Starting...");
            } else {
                checkAndRequestPermissions();
            }
        }
    }

    private void updateButtonState() {
        btnToggle.setText(BridgeService.isServiceRunning ? R.string.btn_stop : R.string.btn_start);
    }

    private void refreshBatchSummary() {
        File baseDir = getExternalFilesDir("batches");
        if (baseDir != null && baseDir.exists()) {
            File[] batches = baseDir.listFiles(File::isDirectory);
            if (batches != null && batches.length > 0) {
                // Newest by modification time (listFiles order is undefined)
                File latest = batches[0];
                for (File b : batches) {
                    if (b.lastModified() > latest.lastModified()) latest = b;
                }
                updateItemCounts(latest);
                replaySolverLog(latest);
            }
        }
    }

    /** Replays persisted solver progress (survives Activity pause/kill). */
    private void replaySolverLog(File batchDir) {
        try {
            File log = new File(batchDir, "solver.log");
            if (!log.exists()) return;
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(log));
            String line, last1 = "", last2 = "", last3 = "";
            while ((line = br.readLine()) != null) { last1 = last2; last2 = last3; last3 = line; }
            br.close();
            String tail = (last1.isEmpty() ? "" : last1 + "\n")
                        + (last2.isEmpty() ? "" : last2 + "\n") + last3;
            if (!tail.isEmpty()) tvSolverLog.setText(tail.trim());
        } catch (Exception ignored) {}
    }

    private void updateItemCounts(File batchDir) {
        if (batchDir == null || !batchDir.exists()) return;
        File[] f = batchDir.listFiles();
        int txt = 0, img = 0, audio = 0;
        if (f != null) {
            for (File file : f) {
                String name = file.getName().toLowerCase();
                if (name.endsWith(".txt") && !name.contains("answers") && !name.contains("keys")) txt++;
                else if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp")) img++;
                else if (name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".m4a") || name.endsWith(".ogg")) audio++;
            }
        }
        tvItemDetails.setText(String.format(Locale.US, "Items: %d text, %d images, %d audio", txt, img, audio));
    }

    // =========================================================================
    // BATCH HISTORY & STORAGE MANAGEMENT IMPLEMENTATION
    // =========================================================================

    private void refreshBatchHistory() {
        runOnUiThread(() -> {
            File baseDir = getExternalFilesDir("batches");
            if (baseDir == null || !baseDir.exists()) {
                tvStorageInfo.setText("Total Batches: 0 (0 KB)");
                llBatchHistoryContainer.removeAllViews();
                return;
            }

            File[] batchDirs = baseDir.listFiles(File::isDirectory);
            if (batchDirs == null || batchDirs.length == 0) {
                tvStorageInfo.setText("Total Batches: 0 (0 KB)");
                llBatchHistoryContainer.removeAllViews();
                TextView emptyTv = new TextView(this);
                emptyTv.setText("No batches stored on phone.");
                emptyTv.setTextColor(Color.parseColor("#888888"));
                emptyTv.setTextSize(12);
                emptyTv.setPadding(0, dpToPx(8), 0, dpToPx(8));
                llBatchHistoryContainer.addView(emptyTv);
                return;
            }

            // Sort newest first (descending by lastModified)
            Arrays.sort(batchDirs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

            long totalBytes = 0;
            for (File b : batchDirs) {
                totalBytes += getFolderSize(b);
            }

            String totalSizeStr = formatSize(totalBytes);
            tvStorageInfo.setText(String.format(Locale.US, "Total Batches: %d (%s)", batchDirs.length, totalSizeStr));

            llBatchHistoryContainer.removeAllViews();

            for (File dir : batchDirs) {
                View card = createBatchCard(dir);
                llBatchHistoryContainer.addView(card);
            }
        });
    }

    private View createBatchCard(final File batchDir) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.parseColor("#222222"));
        int p = dpToPx(12);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dpToPx(10);
        card.setLayoutParams(lp);

        // Top Row: Batch Name & Date (Left) + Solved Badge (Right)
        RelativeLayout headerRow = new RelativeLayout(this);
        RelativeLayout.LayoutParams rlp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
        headerRow.setLayoutParams(rlp);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        RelativeLayout.LayoutParams tclp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
        tclp.addRule(RelativeLayout.ALIGN_PARENT_START);
        titleCol.setLayoutParams(tclp);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(batchDir.getName());
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(14);
        tvTitle.setTypeface(null, Typeface.BOLD);
        titleCol.addView(tvTitle);

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        TextView tvDate = new TextView(this);
        tvDate.setText(sdf.format(new Date(batchDir.lastModified())));
        tvDate.setTextColor(Color.parseColor("#888888"));
        tvDate.setTextSize(10);
        titleCol.addView(tvDate);

        headerRow.addView(titleCol);

        // Check if solved (answers.json exists)
        File answersFile = new File(batchDir, "answers.json");
        boolean isSolved = answersFile.exists() && answersFile.length() > 0;

        TextView tvBadge = new TextView(this);
        RelativeLayout.LayoutParams blp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
        blp.addRule(RelativeLayout.ALIGN_PARENT_END);
        blp.addRule(RelativeLayout.CENTER_VERTICAL);
        tvBadge.setLayoutParams(blp);
        if (isSolved) {
            tvBadge.setText("[✓ SOLVED]");
            tvBadge.setTextColor(Color.parseColor("#00E676")); // Green
        } else {
            tvBadge.setText("[PENDING]");
            tvBadge.setTextColor(Color.parseColor("#FFD54F")); // Yellow
        }
        tvBadge.setTextSize(11);
        tvBadge.setTypeface(null, Typeface.BOLD);
        headerRow.addView(tvBadge);

        card.addView(headerRow);

        // Breakdown Row (Item counts & size)
        int txt = 0, img = 0, audio = 0;
        File[] files = batchDir.listFiles();
        long bSize = 0;
        if (files != null) {
            for (File f : files) {
                bSize += f.length();
                String fn = f.getName().toLowerCase();
                if (fn.endsWith(".txt") && !fn.contains("answers") && !fn.contains("keys")) txt++;
                else if (fn.endsWith(".jpg") || fn.endsWith(".jpeg") || fn.endsWith(".png") || fn.endsWith(".webp")) img++;
                else if (fn.endsWith(".mp3") || fn.endsWith(".wav") || fn.endsWith(".m4a") || fn.endsWith(".ogg")) audio++;
            }
        }

        TextView tvDetails = new TextView(this);
        tvDetails.setText(String.format(Locale.US, "Items: %d txt, %d img, %d audio • %s", txt, img, audio, formatSize(bSize)));
        tvDetails.setTextColor(Color.parseColor("#CCCCCC"));
        tvDetails.setTextSize(11);
        tvDetails.setPadding(0, dpToPx(6), 0, dpToPx(8));
        card.addView(tvDetails);

        // Expandable Answer Section (initially GONE)
        final TextView tvAnswersView = new TextView(this);
        tvAnswersView.setTextColor(Color.parseColor("#E0E0E0"));
        tvAnswersView.setTextSize(11);
        tvAnswersView.setTypeface(Typeface.MONOSPACE);
        tvAnswersView.setBackgroundColor(Color.parseColor("#161616"));
        int ap = dpToPx(8);
        tvAnswersView.setPadding(ap, ap, ap, ap);
        tvAnswersView.setVisibility(View.GONE);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dpToPx(6);
        alp.bottomMargin = dpToPx(6);
        tvAnswersView.setLayoutParams(alp);

        // Action Buttons Row: View Answers (if solved) + Delete
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        if (isSolved) {
            final Button btnToggleAnswers = new Button(this);
            btnToggleAnswers.setText("View Answers");
            btnToggleAnswers.setTextSize(10);
            btnToggleAnswers.setTextColor(Color.parseColor("#00E5FF"));
            btnToggleAnswers.setBackgroundColor(Color.parseColor("#333333"));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, dpToPx(34), 1.0f);
            tlp.rightMargin = dpToPx(8);
            btnToggleAnswers.setLayoutParams(tlp);

            btnToggleAnswers.setOnClickListener(v -> {
                if (tvAnswersView.getVisibility() == View.VISIBLE) {
                    tvAnswersView.setVisibility(View.GONE);
                    btnToggleAnswers.setText("View Answers");
                } else {
                    String formatted = loadFormattedAnswers(answersFile);
                    tvAnswersView.setText(formatted);
                    tvAnswersView.setVisibility(View.VISIBLE);
                    btnToggleAnswers.setText("Hide Answers");
                }
            });

            btnRow.addView(btnToggleAnswers);
        }

        Button btnDelete = new Button(this);
        btnDelete.setText("Delete Batch");
        btnDelete.setTextSize(10);
        btnDelete.setTextColor(Color.parseColor("#FF5252"));
        btnDelete.setBackgroundColor(Color.parseColor("#3D1F1F"));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                isSolved ? 0 : LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(34), isSolved ? 1.0f : 0f);
        btnDelete.setLayoutParams(dlp);

        btnDelete.setOnClickListener(v -> confirmDeleteBatch(batchDir));
        btnRow.addView(btnDelete);

        card.addView(btnRow);
        card.addView(tvAnswersView);

        return card;
    }

    private void confirmDeleteBatch(final File batchDir) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Batch?")
                .setMessage("Are you sure you want to delete '" + batchDir.getName() + "' and all its files?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    int failed = deleteRecursive(batchDir);
                    Toast.makeText(this, failed == 0 ? "Deleted " + batchDir.getName()
                            : "Deleted with " + failed + " leftover file(s)", Toast.LENGTH_SHORT).show();
                    refreshBatchHistory();
                    refreshBatchSummary();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmClearAllBatches() {
        new AlertDialog.Builder(this)
                .setTitle("Clear All Batches?")
                .setMessage("This will permanently delete all saved batches from phone storage.")
                .setPositiveButton("Clear All", (dialog, which) -> {
                    int failed = 0;
                    File baseDir = getExternalFilesDir("batches");
                    if (baseDir != null && baseDir.exists()) {
                        File[] batches = baseDir.listFiles(File::isDirectory);
                        if (batches != null) {
                            for (File b : batches) {
                                failed += deleteRecursive(b);
                            }
                        }
                    }
                    Toast.makeText(this, failed == 0 ? "All batches cleared!"
                            : "Cleared with " + failed + " leftover file(s)", Toast.LENGTH_SHORT).show();
                    refreshBatchHistory();
                    refreshBatchSummary();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Returns count of files that FAILED to delete (0 = clean). */
    private static int deleteRecursive(File f) {
        if (f == null || !f.exists()) return 0;
        int failed = 0;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) {
                    failed += deleteRecursive(child);
                }
            }
        }
        if (!f.delete() && f.exists()) failed++;
        return failed;
    }

    private static long getFolderSize(File dir) {
        if (dir == null || !dir.exists()) return 0;
        long size = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    size += getFolderSize(f);
                } else {
                    size += f.length();
                }
            }
        }
        return size;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0));
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round((float) dp * density);
    }

    private static String loadFormattedAnswers(File f) {
        try {
            // Critical fix: single read() may return short reads -> truncated JSON.
            // Full read loop with size guard.
            long flen = f.length();
            if (flen <= 0 || flen > 8 * 1024 * 1024) {
                return "Error reading answers: unexpected file size " + flen;
            }
            byte[] b = new byte[(int) flen];
            FileInputStream fis = new FileInputStream(f);
            try {
                int off = 0;
                while (off < b.length) {
                    int r = fis.read(b, off, b.length - off);
                    if (r < 0) break;
                    off += r;
                }
            } finally {
                fis.close();
            }
            String jsonStr = new String(b, StandardCharsets.UTF_8);
            JSONObject obj = new JSONObject(jsonStr);

            List<String> keys = new ArrayList<>();
            Iterator<String> it = obj.keys();
            while (it.hasNext()) keys.add(it.next());
            Collections.sort(keys);

            StringBuilder sb = new StringBuilder();
            for (String k : keys) {
                if (k.startsWith("_")) continue; // _meta sidecar, not a question
                JSONObject item = obj.optJSONObject(k);
                if (item != null) {
                    Object ans = item.opt("answer");
                    if (ans == null && item.has("option")) {
                        String opt = item.optString("option");
                        String optAns = item.optString("option_answer", "");
                        ans = optAns.isEmpty() ? opt : opt + ". " + optAns;
                    } else if (ans == null && item.has("options")) {
                        ans = item.opt("options");
                    }
                    String reason = item.optString("reason", "");
                    if (reason.isEmpty()) reason = item.optString("alasan", "");
                    if (reason.isEmpty()) reason = item.optString("analysis", "");
                    boolean auto = "auto-filled".equals(item.optString("confidence", ""));
                    sb.append("Soal ").append(k).append(" -> ").append(auto ? "? " : "")
                      .append(ans != null ? ans.toString() : "-").append("\n");
                    if (!reason.isEmpty()) {
                        sb.append("   Alasan: ").append(reason).append("\n");
                    }
                } else {
                    sb.append("Soal ").append(k).append(" -> ").append(obj.optString(k, "-")).append("\n");
                }
                sb.append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "Error reading answers: " + e.getMessage();
        }
    }

    // =========================================================================
    // SERVICE & BATCH PROGRESS CALLBACKS
    // =========================================================================

    @Override
    public void onStatusChanged(String status, String clientName, int activeSockets, long bytes) {
        runOnUiThread(() -> {
            tvStatus.setText("Status: " + status);
            tvConnectedDevice.setText("Client: " + clientName);
            if (bytes < 1024) {
                tvDataTransferred.setText("Transferred: " + bytes + " B");
            } else if (bytes < 1024 * 1024) {
                tvDataTransferred.setText(String.format(Locale.US, "Transferred: %.1f KB", bytes / 1024.0));
            } else {
                tvDataTransferred.setText(String.format(Locale.US, "Transferred: %.2f MB", bytes / (1024.0 * 1024.0)));
            }
            updateButtonState();
        });
    }

    @Override
    public void onBatchProgress(String batchId, int itemIndex, int totalItems, String itemName, int percent) {
        runOnUiThread(() -> {
            if (itemIndex == 0) {
                tvBatchInfo.setText(String.format(Locale.US, "Batch [%s]: Active", batchId));
                pbBatchProgress.setProgress(percent);
                tvSolverLog.setText("State: " + itemName);
                // New batch = fresh display: kill all stale state from previous runs
                AiSolver.clearAttemptLog();
                tvBatchSummary.setText("Answers: ...");
                transparencyFull = "";
                tvTransparency.setText("Solving " + batchId + "... (tap to expand)");
            } else {
                tvBatchInfo.setText(String.format(Locale.US, "Batch [%s] Item %d/%d", batchId, itemIndex, totalItems));
                pbBatchProgress.setProgress(percent);
                tvSolverLog.setText(itemName);
            }

            File baseDir = getExternalFilesDir("batches");
            if (baseDir != null) {
                updateItemCounts(new File(baseDir, batchId));
            }
            refreshTransparency();
        });
    }

    @Override
    public void onBatchComplete(String batchId, int totalItems, String path) {
        runOnUiThread(() -> {
            tvBatchInfo.setText(String.format(Locale.US, "Batch [%s] COMPLETE!", batchId));
            pbBatchProgress.setProgress(100);
            tvSolverLog.setText("AI Status: All questions solved & sent to Mac!");

            if (path != null && path.startsWith("SOLVED: ")) {
                try {
                    String jsonStr = path.substring(8);
                    JSONObject obj = new JSONObject(jsonStr);
                    StringBuilder sb = new StringBuilder("Answers:\n");
                    Iterator<String> it = obj.keys();
                    int count = 0;
                    while (it.hasNext() && count < 25) {
                        String k = it.next();
                        if (k.startsWith("_")) continue;
                        JSONObject qObj = obj.optJSONObject(k);
                        String ans = qObj != null ? qObj.optString("answer", "-") : "-";
                        sb.append(k).append(": ").append(ans).append("  |  ");
                        count++;
                        if (count % 5 == 0) sb.append("\n");
                    }
                    tvBatchSummary.setText(sb.toString().trim());
                } catch (Exception e) {
                    tvBatchSummary.setText("Answers: " + path.substring(0, Math.min(path.length(), 200)));
                }
            }

            // Immediately update the batch history list so the new batch appears
            refreshBatchHistory();
            refreshTransparency();
        });
    }
}
