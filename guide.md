# APPLE / blue / Blue — User Guide

Three pieces: **Apple** (this Mac folder: supervisor, extractor, Bluetooth sender, HUDs), **blue** (phone app: listener + AI solver), **Blue** (Chrome extension: page tracker + shortcuts).

## First-time setup

1. **Phone:** install `android/blue.apk`, open **blue** → tap **Start Service**. Open system settings via **Fix Background Running** and allow background activity + battery whitelist (this decides screen-off survival).
2. **Chrome:** `chrome://extensions/` → Developer Mode → Load unpacked → select `chrome_extension/`. (Click the reload icon after any extension update).
3. **Mac:** `./build_mac.sh` once (builds all native binaries).

## Running

```bash
cd ~/Documents/blue
./blue --bg-plus # background daemon with Plus & Alpha features (normal use)
./blue --bg      # background daemon standard mode
./blue           # foreground supervisor (watch the log live)
./blue --alpha   # trigger Alpha DeepSeek Vision solve directly from CLI
./blue --log     # view last 60 daemon log lines
./blue --stop    # stop everything cleanly
```

Then use Chrome normally:
- **Primary Pipeline (Ctrl+S):** Save the exam page (`Ctrl+S`) or drop HTML into `raw_batches/` → extraction → phone solves over Bluetooth → answers pop at the bottom-left corner.
- **Secondary Pipeline (Key 9 - Alpha Feature):** Press `9` anytime → off-screen WebKit captures all 30 questions as screenshots → solves with DeepSeek-V4.1-Flash vision in ~20s → answers pop at bottom-middle and sync to phone.

## Keyboard Shortcuts (on the exam page)

| Key | Action |
|---|---|
| `9` | **ALPHA Vision Solve:** extracts all 30 questions as screenshots and solves in 10 parallel chunks via DeepSeek-V4.1-Flash. |
| `` ` `` | Toggle force-image mark on active question (diagram questions). Works inside iframes. |
| `\` … `\` | Chat: press `\`, type invisibly, `\` again submits. Answer shows in bottom-right HUD. |
| `]` or `Esc` | Cancel chat (capture auto-releases after 20s idle). |
| `Backspace` / `Delete` | Edit chat buffer while capturing. |
| `=` / `+` | Scroll chat HUD down. |
| `-` / `_` | Scroll chat HUD up. |
| `1` .. `5` | **Essay Presets 1–5:** Instantly load Biology Essay outline notes into the bottom-right HUD. |
| `6` .. `8` | **Math Formula Presets 6–8:** Instantly load Transformasi Geometri formulas (6: Translasi/Refleksi, 7: Rotasi, 8: Dilatasi/Domain) into the bottom-right HUD. |
| `0` | Re-solve active question only (fires on-demand single question solve to phone). |
| `[` | Whole reset: stops solving, zeroes state, blanks HUDs. |
| `/` | Instant hide (0ms) — hides overlays without stopping background pipeline. Press again to show. |
| `.` | Sudden total kill (supervisor + HUDs exit immediately). |
| `,` | Wake overlays (works whenever the supervisor is alive). |

*Note: `1`..`8`, `9`, `0`, `[`, `/`, `.`, `,` are top-frame only and ignored while typing inside input fields.*

---

## The ALPHA Feature (Key `9` / DeepSeek Vision)

When text extraction is tricky, questions have complex diagrams, or you want a second opinion from deep thinking vision AI:

1. **How to Trigger:**
   - Press **`9`** while on the CBT exam page in Chrome, OR run `./blue --alpha` from terminal.
2. **Headless Screenshot Capture:**
   - Apple WebKit loads the latest saved HTML in headless RAM (no browser window opens) and captures all 30 questions as high-DPI screenshots (`soal_01.jpg` … `soal_30.jpg`) in ~10 seconds.
3. **10 Chunks × 3 Questions:**
   - The 30 screenshots are divided into 10 parallel groups (`[1..3]`, `[4..6]`, `[7..9]`, etc.).
   - Dispatched simultaneously to **EdgeOne AI Gateway** using `@makers/deepseek-v4.1-flash`.
   - The entire 30-question exam finishes in **~20–25 seconds**.
4. **High-Thinking & Strict JSON:**
   - The model reasons through formulas, geometry, and Indonesian CBT question formats.
   - Handles all 3 types:
     - `pilihan_ganda`: Single uppercase letter (`A`..`E`).
     - `pilihan_ganda_kompleks`: Multi-select letters (`A, C, D`).
     - `benar_salah`: Statement verdicts (`1.B 2.S 3.S`).
5. **Double Number Verification:**
   - Maps both image slot position AND the question number visibly printed in the screenshot (`nomor_tertera`). Scrambled database IDs (`data-nomor-asli`) will never misalign answers.

---

## HUD Layout & Mouse Trigger Zones

- **100% Transparent Stealth:** The HUDs have 0 background color (completely clear) with compact 50% opacity text.
- **Zone 1 — Bottom-Left Corner (Main Answers):**
  - Hover cursor at the **bottom-left corner** (`x <= 45` above dock).
  - Displays the Standard pipeline answer (e.g. `[14] B (95%)` or `1.B 2.S 3.S`).
- **Zone 2 — Bottom-Middle (ALPHA Answers):**
  - Hover cursor at the **bottom-middle edge** (`abs(x - midX) <= 60` above dock).
  - Displays the **ALPHA DeepSeek** answer in the exact same format and location!
- **Zone 3 — Bottom-Right Corner (Chat & Custom Queries):**
  - Hover cursor at the **bottom-right corner** (`x >= maxX - 55` above dock).
  - Displays responses from the `\` chat feature.
- **Fast Auto-Hide:** Moving your mouse outside any trigger zone dismisses the HUD in **0.06 seconds**.
- **Instant Kill Hotkeys:**
  - Press `/` to immediately hide both HUDs with **0ms delay**.
  - Press `.` for emergency instant process exit.

---

## Android Phone App (Dual Tab UI)

The phone app (`blue.apk`) features a top Tab Bar:
1. **`[ STANDARD ]` Tab:**
   - Bluetooth RFCOMM bridge status.
   - Screen-off phone health check & 2-min self-test.
   - Solver mode selector (1-call / 5-call / 10-call).
   - Batch history & storage management.
2. **`[ ALPHA (DEEPSEEK) ]` Tab:**
   - Displays Alpha batch status, solve duration, and timestamp.
   - Full question breakdown with colored badges (`pilihan_ganda`, `kompleks`, `benar_salah`).
   - Big, readable bold answers and concise reasoning.
   - Updates automatically whenever an Alpha solve completes.

---

## Useful Commands

```bash
./blue --bg-plus             # launch background daemons (Plus + Alpha)
./blue --alpha               # trigger Alpha vision solve immediately
./blue --log                 # inspect live daemon logs
./blue <batch_id>            # resend stored batch to phone
./blue fetch <batch_id>      # pull solved answers without resending
./blue report <batch_id>     # view run card (timings, retries, models)
./bluebridge_send --ping        # test 10-second Bluetooth connection
./tests/lock_test.sh 20         # run 20-min screen-off lock test
```

## Troubleshooting

- **Flapping or jumping question numbers:** Fixed in latest `content.js`. Active question tracker now prioritizes `#currentQuestionNumber` and ignores non-CBT frames.
- **Alpha HUD says `(no data)`:** Press `9` in Chrome or run `./blue --alpha` in terminal to trigger the vision solve on the latest saved HTML.
- **Hover shows nothing:** Verify daemons are running (`pgrep -fl blue`). If `/` was pressed, press `/` or `,` to unpause.
- **Phone shows no Alpha answers:** Tap **Refresh Alpha Answers** in the phone app's ALPHA tab, or ensure ADB/Bluetooth is connected.
