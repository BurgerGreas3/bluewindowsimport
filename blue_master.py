#!/usr/bin/env python3
"""
================================================================================
                    BLUE MASTER TERMINAL APPLICATION                         
================================================================================
All-in-One Automated CBT Pipeline & Companion Supervisor:
1. Native macOS Overlay: Launches & manages Blue Notch (left-edge slide-out HUD)
2. HTTP Bridge Server (127.0.0.1:20261): Receives active question & backtick marks from Chrome
3. Bluetooth Controller: Keeps BT ON, checks connection to phone 'yoo'
4. Raw Batch & Downloads Watcher: Listens to raw_batches/ and ~/Downloads for saved CBT exams
5. Smart Sorter: Moves & renames to raw_sort_batch/batch_XX (unpacks MHTML if needed)
6. Hybrid Extractor: Extracts text, image (with MathJax & forced overrides), and audio
7. Bluetooth Dispatcher: Sends batch to Android phone ('blue.apk' / BlueBridge)
8. Answer Sync: Automatically updates latest_answers.json for Blue Notch HUD
================================================================================
"""

import email
from email import policy
import glob
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import mimetypes
import os
import re
import shutil
import signal
import subprocess
import sys
import tempfile  # <-- ADD THIS LINE
import threading
import time
import urllib.parse
from datetime import datetime
from pathlib import Path

# Paths
BASE_DIR = Path(__file__).resolve().parent
RAW_DIR = BASE_DIR / "raw_batches"
SORT_DIR = BASE_DIR / "raw_sort_batch"
BATCHES_DIR = BASE_DIR / "batches"
ANSWERS_DIR = BASE_DIR / "answers"
DOWNLOADS_DIR = Path(os.environ.get("DOWNLOADS_DIR", str(Path.home() / "Downloads")))
APK_PATH = DOWNLOADS_DIR / "blue.apk"

BT_CTRL_BIN = BASE_DIR / ("bt_ctrl.bat" if os.name == "nt" else "bt_ctrl")
CBT_EXTRACT_BIN = BASE_DIR / ("cbt_extract.bat" if os.name == "nt" else "cbt_extract")
BLUEBRIDGE_SEND_BIN = BASE_DIR / ("bluebridge_send.bat" if os.name == "nt" else "bluebridge_send")
NOTCH_BIN = BASE_DIR / ("blue_notch.bat" if os.name == "nt" else "blue_notch")
CBT_SCREENS_BIN = BASE_DIR / "cbt_screens"
PLUS_MODE = False

# config.json / TARGET_PHONE_MAC overrides (phone MAC without editing code): {"target_phone_mac": "..."}
TARGET_PHONE_MAC = os.environ.get("TARGET_PHONE_MAC", "00-00-00-00-00-00")
try:
    cfg_file = BASE_DIR / "config.json"
    if cfg_file.exists():
        _cfg = json.loads(cfg_file.read_text(encoding="utf-8"))
        if isinstance(_cfg, dict) and _cfg.get("target_phone_mac"):
            TARGET_PHONE_MAC = str(_cfg["target_phone_mac"]).strip()
            print(f"[*] config.json: target phone = {TARGET_PHONE_MAC}")
except Exception as e:
    print(f"[!] config.json unreadable ({e}), using default target phone.")

# Last Chrome-extension heartbeat (epoch). Warns when tracker is silent.
LAST_CHROME_HB = {"t": 0.0}

def preflight_bins():
    """Fail fast when native binaries are missing (fresh clone without ./build_mac.sh)."""
    missing = [str(p) for p in (BT_CTRL_BIN, CBT_EXTRACT_BIN, BLUEBRIDGE_SEND_BIN, NOTCH_BIN)
               if not (p.exists() and os.access(p, os.X_OK))]
    if missing:
        print("[!] Missing native binaries:")
        for m in missing:
            print(f"    - {m}")
        print("[!] Run ./build_mac.sh first, then ./blue")
        sys.exit(1)

# ==============================================================================
# 1. STATE BRIDGE & LOCAL HTTP SERVER (127.0.0.1:20261)
# ==============================================================================

class BridgeState:
    def __init__(self):
        self.active_q = 1
        self.forced_imgs = set()
        self._pending_q = None
        self._pending_n = 0
        self.lock = threading.Lock()
        self.active_q_file = Path(tempfile.gettempdir()) / Path("blue_active_q.txt")
        self.forced_imgs_file = Path(tempfile.gettempdir()) / Path("blue_forced_imgs.json")
        self._write_files()

    def _write_files(self):
        try:
            self.active_q_file.write_text(str(self.active_q), encoding="utf-8")
            self.forced_imgs_file.write_text(json.dumps(sorted(list(self.forced_imgs))), encoding="utf-8")
        except Exception:
            pass

    def set_active(self, q: int, confident: bool = False):
        with self.lock:
            LAST_CHROME_HB["t"] = time.time()
            if q <= 0:
                return
            if q == self.active_q:
                self._pending_q, self._pending_n = None, 0
                return
            if confident:
                # Explicit active marker (.active container / nav button / URL hash):
                # switch instantly, no lag while reading fast.
                self.active_q = q
                self._pending_q, self._pending_n = None, 0
                self._write_files()
                print(f"  [⚡ CHROME TRACKER] Switched to Soal #{q:02d}")
                return
            # Hysteresis for guesses (visible-scan): switch only after the same
            # number reports twice running (heartbeat every 800ms confirms real
            # moves in ~1.6s; one-frame misdetects die unconfirmed).
            if self._pending_q == q:
                self._pending_n += 1
            else:
                self._pending_q, self._pending_n = q, 1
            if self._pending_n >= 2:
                self.active_q = q
                self._pending_q, self._pending_n = None, 0
                self._write_files()
                print(f"  [⚡ CHROME TRACKER] Switched to Soal #{q:02d}")

    def toggle_mark(self, q: int, mode: str = "toggle"):
        with self.lock:
            LAST_CHROME_HB["t"] = time.time()
            if mode == "add":
                self.forced_imgs.add(q)
                status_str = "📸 FORCED AS IMAGE"
            elif mode == "remove":
                self.forced_imgs.discard(q)
                status_str = "📝 RESTORED TO AUTO / TEXT"
            else:
                if q in self.forced_imgs:
                    self.forced_imgs.remove(q)
                    status_str = "📝 RESTORED TO AUTO / TEXT"
                else:
                    self.forced_imgs.add(q)
                    status_str = "📸 FORCED AS IMAGE"
            self._write_files()
            sorted_marks = sorted(list(self.forced_imgs))
            print(f"  [⚡ BACKTICK KEY `] Soal #{q:02d} -> {status_str} (Marked set: {sorted_marks})")

    def reset_notes(self):
        with self.lock:
            if self.forced_imgs:
                print(f"  [⚡ BROWSER EVENT] Webpage reloaded/navigated -> Notes reset to 0")
            self.forced_imgs.clear()
            self._write_files()

    def peek_forced(self) -> list:
        """Non-destructive read: marks survive aborts, cleared only on success."""
        with self.lock:
            return sorted(list(self.forced_imgs))

    def get_and_consume_forced(self) -> list:
        """Returns forced images list and resets it for sustainable loop."""
        with self.lock:
            res = sorted(list(self.forced_imgs))
            self.forced_imgs.clear()
            self._write_files()
            return res

BRIDGE_STATE = BridgeState()

class BridgeHTTPHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass # suppress standard access log to keep terminal clean

    def setup(self):
        super().setup()
        try:
            # P1 socket deadline: a half-open browser connection can never hold
            # a handler thread forever (applies to socket I/O only, not jobs).
            self.request.settimeout(30)
        except Exception:
            pass

    def _send_cors_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "*")

    def do_OPTIONS(self):
        self.send_response(200)
        self._send_cors_headers()
        self.end_headers()

    def do_GET(self):
        # A browser-abandoned connection (pre-connect, tab close) must never
        # crash-log a handler thread: swallow dead-socket errors silently.
        try:
            self._do_GET_inner()
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as e:
            try:
                print(f"[!] bridge handler error: {e}")
            except Exception:
                pass

    def _do_GET_inner(self):
        host = self.headers.get("Host", "")
        if host and not (host.startswith("127.0.0.1") or host.startswith("localhost")):
            self.send_response(403)
            self.end_headers()
            self.wfile.write(b'{"error": "Forbidden Host"}')
            return

        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        qs = urllib.parse.parse_qs(parsed.query)

        # Block malicious cross-origin img tags from triggering destructive actions
        if path in ("/reset_all", "/kill_all") and self.headers.get("Sec-Fetch-Dest") == "image":
            self.send_response(403)
            self.end_headers()
            self.wfile.write(b'{"error": "Image trigger forbidden"}')
            return

        if path == "/active":
            q_val = qs.get("q", ["1"])[0]
            conf = qs.get("c", ["0"])[0] == "1"
            try:
                q_num = int(q_val)
                BRIDGE_STATE.set_active(q_num, conf)
            except ValueError:
                pass
            self.send_response(200)
            self._send_cors_headers()
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"status": "ok", "active": BRIDGE_STATE.active_q}).encode())

        elif path == "/mark":
            q_val = qs.get("q", ["1"])[0]
            mode = qs.get("mode", ["toggle"])[0]
            try:
                q_num = int(q_val)
                BRIDGE_STATE.toggle_mark(q_num, mode)
            except ValueError:
                pass
            self.send_response(200)
            self._send_cors_headers()
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"status": "ok", "forced": sorted(list(BRIDGE_STATE.forced_imgs))}).encode())

        elif path == "/reset_notes":
            BRIDGE_STATE.reset_notes()
            self.send_response(200)
            self._send_cors_headers()
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"status": "ok", "forced": []}).encode())

        elif path == "/status":
            self.send_response(200)
            self._send_cors_headers()
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            data = {
                "active_question": BRIDGE_STATE.active_q,
                "forced_images": sorted(list(BRIDGE_STATE.forced_imgs)),
                "paused": IS_PAUSED["v"],
                "chat": CHAT_STATE,
            }
            self.wfile.write(json.dumps(data).encode())

        elif path == "/chatask":
            q = qs.get("q", [""])[0]
            ok = start_chat(q)
            self.send_response(200)
            self._send_cors_headers()
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"status": "ok" if ok else "busy", "chat": CHAT_STATE}).encode())

        elif path == "/chatcancel":
            cancel_chat()
            self.send_response(200)
            self._send_cors_headers()
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"status": "ok"}).encode())

        elif path == "/chatreset":
            reset_chat()
            self.send_response(200)
            self._send_cors_headers()
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"status": "ok"}).encode())

        elif path == "/chat_scroll":
            d = qs.get("dir", ["down"])[0]
            if d not in ("up", "down"):
                self.send_response(400)
                self.end_headers()
                self.wfile.write(b'{"error": "Invalid scroll dir"}')
                return
            try:
                Path(tempfile.gettempdir()) / Path("blue_chat_scroll").write_text(d, encoding="utf-8")
            except Exception:
                pass
            self.send_response(200)
            self._send_cors_headers()
            self.send_header("Content-Type", "application/json")
            self.end_headers()
        elif path == "/preset":
            num = qs.get("num", ["1"])[0]
            if num in PRESET_TEXTS:
                text = PRESET_TEXTS[num]
                try:
                    CHAT_FILE.parent.mkdir(parents=True, exist_ok=True)
                    CHAT_FILE.write_text(json.dumps({"answer": text, "preset": num}), encoding="utf-8")
                    print(f"  [⚡ PRESET KEY {num}] Replaced chat HUD with Essay Preset #{num}")
                except Exception as e:
                    print(f"  [!] Failed writing preset {num}: {e}")
            self.send_response(200)
            self._send_cors_headers()
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"status": "ok", "preset": num}).encode())
        elif path == "/alpha_solve":
            print("  [⚡ 9-KEY] Alpha Solve triggered via Chrome extension / HTTP!")
            def _run_alpha():
                try:
                    import alpha_solver
                    alpha_solver.run_alpha_solve()
                except Exception as e:
                    print(f"  [!] Alpha solve error: {e}")
            threading.Thread(target=_run_alpha, daemon=True).start()
            self.send_response(200)
            self._send_cors_headers()
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b'{"status":"alpha_solve_started"}')

        elif path == "/solve_one":
            # "0" key feature: re-solve a single question number using the phone's
            # stored batch files. Fires CMD_RESOLVE_ONE to the phone via bluebridge_send.
            num_str = qs.get("num", [None])[0]
            bid = CURRENT_BATCH["id"] or LAST_SOLVED_BATCH["id"]
            if not bid:
                cand = [p for p in sorted(ANSWERS_DIR.glob("batch_*_answers.json"), key=lambda p: p.stat().st_mtime, reverse=True)]
                if cand:
                    bid = cand[0].name.replace("_answers.json", "")
            if not num_str or not num_str.isdigit():
                self.send_response(400)
                self._send_cors_headers()
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"error": "missing or invalid num"}).encode())
            elif not bid:
                self.send_response(400)
                self._send_cors_headers()
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"error": "no active batch"}).encode())
            else:
                q_num = int(num_str)
                print(f"[0-KEY] Re-solve Q{q_num} in batch {bid}")
                def _do_resolve():
                    try:
                        cmd = [
                            str(BLUEBRIDGE_SEND_BIN),
                            "--resolve-one", str(q_num),
                            "--batch-id", bid,
                            "--device", TARGET_PHONE_MAC,
                        ]
                        r = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
                        src = ANSWERS_DIR / f"{bid}_answers.json"
                        if src.exists():
                            shutil.copy2(str(src), str(ANSWERS_DIR / "latest_answers.json"))
                            print(f"[0-KEY] Q{q_num} updated in latest_answers.json -> HUD refreshed!")
                    except Exception as e:
                        print(f"  [!] solve_one failed: {e}")
                threading.Thread(target=_do_resolve, daemon=True).start()
                self.send_response(200)
                self._send_cors_headers()
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"status": "ok", "num": q_num, "batch": bid}).encode())

        elif path == "/reset_all":
            # P0: slow reset (30s phone call + kill waits) runs off-thread;
            # handler replies immediately so keys never queue behind it.
            threading.Thread(target=reset_all, daemon=True).start()
            self.send_response(200)
            self._send_cors_headers()
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"status": "ok"}).encode())

        elif path == "/health":
            # P0: side-effect-free liveness (threads, backlog) for diagnosis.
            self.send_response(200)
            self._send_cors_headers()
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({
                "ok": True,
                "threads": threading.active_count(),
                "backlog": BridgeServer.request_queue_size,
                "paused": IS_PAUSED["v"],
                "batch": CURRENT_BATCH["id"],
            }).encode())

        elif path == "/pause_toggle":
            paused = toggle_pause()
            self.send_response(200)
            self._send_cors_headers()
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"status": "ok", "paused": paused}).encode())

        elif path == "/wake":
            wake_overlays()
            self.send_response(200)
            self._send_cors_headers()
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"status": "ok", "paused": False}).encode())

        elif path == "/kill_all":
            self.send_response(200)
            self._send_cors_headers()
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            try:
                self.wfile.write(json.dumps({"status": "killed"}).encode())
            except Exception:
                pass
            threading.Timer(0.5, sudden_kill).start()

        else:
            self.send_response(404)
            self.end_headers()

class BridgeServer(ThreadingHTTPServer):
    # P0 hardening: threads die with the process, 128-deep backlog absorbs
    # heartbeat bursts (default 5 caused the SYN pileup wedge).
    daemon_threads = True
    allow_reuse_address = True
    request_queue_size = 128

def start_http_bridge(port: int = 20261):
    try:
        # Threaded: one slow handler must never wedge keys/heartbeat.
        server = BridgeServer(("127.0.0.1", port), BridgeHTTPHandler)
        t = threading.Thread(target=server.serve_forever, daemon=True)
        t.start()
        return server
    except Exception as e:
        print(f"[!] FATAL: local HTTP bridge on port {port} unavailable: {e}")
        print("[!] Extension sync (active Q + backtick) would be silently dead. Aborting.")
        sys.exit(1)

# ==============================================================================
# 1b. CHATBOT + WHOLE-RESET + PAUSE STATE (separate from batch pipeline)
# ==============================================================================

IS_PAUSED = {"v": False}
CHAT_STATE = {"active": False, "gen": 0}   # gen invalidates stale chat answers
CURRENT_BATCH = {"id": None}
LAST_SOLVED_BATCH = {"id": None}
# P1 dead-phone circuit breaker: consecutive fully-dead runs (no ping + send fail).
# At 2, the supervisor parks: saves queue up unprocessed, no futile retries burn
# 10 min each. A single successful ping auto-resumes. Manual ./blue <batch>
# always bypasses the park (explicit user override).
DEAD_STREAK = {"n": 0}
PARKED = {"on": False, "last_probe": 0.0}
ACTIVE_PROCS = []  # tracked Popen handles (send/chat) so [ can terminate them
ACTIVE_PROCS_LOCK = threading.Lock()
PAUSED_FILE = Path(tempfile.gettempdir()) / Path("blue_paused")
CHAT_FILE = ANSWERS_DIR / "chat_latest.json"
CHAT_HIST_FILE = ANSWERS_DIR / "chat_history.json"

PRESET_TEXTS = {
    "1": """[ESSAY 1: Replication & Transcription]
• Replication:
  - Helicase (unzips/breaks H-bonds), Primase (RNA primer), DNA Polymerase (5'->3', complementary base pairing), Ligase (joins Okazaki fragments).
  - Leading strand (continuous) vs Lagging strand (discontinuous, Okazaki fragments).
  - Semi-conservative (1 parent strand + 1 newly synthesised strand).
• Transcription:
  - Occurs in nucleus. RNA Polymerase binds promoter, reads template (3'->5'), synthesises mRNA (5'->3').
  - Uracil (U) replaces Thymine (T).
  - Splicing: removes non-coding introns, joins coding exons.""",

    "2": """[ESSAY 2: Translation]
• Occurs at ribosome in cytoplasm.
• Start codon: AUG (codes for Methionine).
• Mechanism: tRNA anticodon binds complementary mRNA codon.
• Bond: Peptide bonds between adjacent amino acids (peptidyl transferase).
• Ribosome translocation down mRNA until stop codon (UAA, UAG, UGA) signals release.""",

    "3": """[ESSAY 3: Codominant Alleles (Blood Type)]
• Definition: Both alleles are equally expressed in heterozygote phenotype without blending/masking.
• Allele notation: I^A, I^B, and I^O (or i).
• I^A and I^B are codominant to each other; both completely dominant over I^O.
• Antigens: I^A -> antigen A, I^B -> antigen B, I^O -> no antigen.
• 5-step genetics layout:
  1. Parental phenotypes
  2. Parental genotypes
  3. Gametes (circled/listed)
  4. Punnett square
  5. Offspring phenotypes with ratios/percentages.""",

    "4": """[ESSAY 4: Sex-Linked Traits (Hemophilia / Colorblindness)]
• Sex Chromosomes: Gene carried on non-homologous region of X chromosome.
• Males vulnerable: Hemizygous (XY, only 1 copy of X). Cannot mask recessive allele.
• Notation: X^H / X^h (or X^B / X^b).
  - Female carrier: X^H X^h (phenotypically normal).
  - Affected male: X^h Y.
  - Normal male: X^H Y.
• Always run full Punnett square and state separate probabilities for sons vs daughters.""",

    "5": """[ESSAY 5: Solving the Chi-Squared (χ²) Test]
1. Null Hypothesis (H₀):
   "There is no significant difference between observed and expected results; any difference is due to chance alone."
2. Formula: χ² = Σ ((O - E)² / E).
3. Table columns: Category | O | E | (O - E) | (O - E)² | (O - E)² / E.
4. Degrees of Freedom: df = n - 1 (n = number of phenotypic classes).
5. Final 3-Part Verdict Sentence:
   - State whether calculated χ² > or < critical value at p = 0.05.
   - State whether you accept or reject H₀.
   - State whether difference is due to chance or statistically significant.""",

    "6": """[RUMUS 6: Translasi & Refleksi]
1. TRANSLASI: T = (a, b)
   • Titik: x' = x + a => x = x' - a | y' = y + b => y = y' - b
   • Fungsi y = f(x):
     - Geser kanan a: ganti x dengan (x - a)
     - Geser kiri a: ganti x dengan (x + a)
     - Geser atas b: y = f(x) + b (tambah b di luar)
     - Geser bawah b: y = f(x) - b (kurang b di luar)

2. REFLEKSI (Pencerminan):
   • Sumbu X: x = x' | y = -y' => y = -f(x)
   • Sumbu Y: x = -x' | y = y' => y = f(-x)
   • Titik Asal (0, 0): x = -x' | y = -y' => y = -f(-x)
   • Garis y = x: x = y' | y = x' => x = f(y) (tukar posisi x & y)
   • Garis y = -x: x = -y' | y = -x' => -x = f(-y)
   • Garis Vertikal x = h: x = 2h - x' | y = y' => y = f(2h - x)
   • Garis Horizontal y = k: x = x' | y = 2k - y' => y = 2k - f(x)
   • Titik Sembarang (h, k): x = 2h - x' | y = 2k - y' => y = 2k - f(2h - x)""",

    "7": """[RUMUS 7: Rotasi / Perputaran]
• Aturan Tanda Sudut:
  - Berlawanan jarum jam: sudut positif (+θ)
  - Searah jarum jam: sudut negatif (-θ)
  - Identitas: -90° (searah) = +270° (berlawanan) | +90° = -270° | 360° = tetap

1. RUMUS UMUM SUDUT SEMBARANG θ:
   • Pusat (0, 0):
     x' = x cos θ - y sin θ  |  y' = x sin θ + y cos θ
   • Pusat (a, b):
     x' - a = (x - a) cos θ - (y - b) sin θ
     y' - b = (x - a) sin θ + (y - b) cos θ

2. SUDUT ISTIMEWA PUSAT (0, 0):
   • Rotasi 180° (arah bebas):
     x = -x' | y = -y' => y = -f(-x)
   • Rotasi +90° / -270° (berlawanan 90°):
     x = y' | y = -x' => -x = f(y)
   • Rotasi -90° / +270° (searah 90°):
     x = -y' | y = x' => x = f(-y)

3. SUDUT ISTIMEWA PUSAT (a, b):
   • Rotasi 180°:
     x = 2a - x' | y = 2b - y'
   • Rotasi +90° (berlawanan):
     x = (y' - b) + a | y = -(x' - a) + b
   • Rotasi -90° (searah):
     x = -(y' - b) + a | y = (x' - a) + b""",

    "8": """[RUMUS 8: Dilatasi & Transformasi Domain/Fungsi]
1. DILATASI (Faktor Skala k):
   • Pusat (0, 0):
     x' = k·x => x = x'/k  |  y' = k·y => y = y'/k
     => Substitusi ke y = f(x): y = k · f(x/k)
   • Pusat (a, b):
     x - a = (x' - a)/k => x = a + (x' - a)/k
     y - b = (y' - b)/k => y = b + (y' - b)/k
     => Substitusikan x dan y ke fungsi awal y = f(x)

2. TRANSFORMASI DOMAIN & PECAHAN:
   • Syarat Domain Awal (misal x > c):
     Substitusikan bentuk x(x') ke syarat:
     (x' - h)/k > c => x' > k·c + h
   • Sifat Nilai Mutlak: |-ax| = a|x| (untuk a > 0)
   • Aljabar Fungsi Pecahan L(x) = (ax + b)/(cx + d):
     - Substitusi x = (x' - a_val)/k ke pembilang & penyebut
     - Samakan penyebut pembilang & penyebut pecahan
     - Kalikan kedua ruas dengan k
     - Pindahkan konstanta y', samakan penyebut, dan isolasi y'"""
}

def track_proc(p):
    with ACTIVE_PROCS_LOCK:
        ACTIVE_PROCS.append(p)

def untrack_proc(p):
    with ACTIVE_PROCS_LOCK:
        try:
            ACTIVE_PROCS.remove(p)
        except ValueError:
            pass

def kill_tracked_procs():
    with ACTIVE_PROCS_LOCK:
        procs = list(ACTIVE_PROCS)
    for p in procs:
        try:
            if p.poll() is None:
                p.terminate()
                try:
                    p.wait(timeout=2.0)
                except Exception:
                    p.kill()
        except Exception:
            pass
        untrack_proc(p)

def load_chat_keys():
    """OPENROUTER_KEY + ONOMEO_KEY from .env (never logged, never committed)."""
    or_k, onomeo_k = "", ""
    env_file = BASE_DIR / ".env"
    if env_file.exists():
        try:
            with open(env_file, "r", encoding="utf-8") as ef:
                for line in ef:
                    line = line.strip()
                    if line.startswith("OPENROUTER_KEY="):
                        or_k = line.split("=", 1)[1].strip().strip('"').strip("'")
                    elif line.startswith("ONOMEO_KEY="):
                        onomeo_k = line.split("=", 1)[1].strip().strip('"').strip("'")
                    elif line.startswith("CEREBRAS_KEY=") and not onomeo_k:
                        onomeo_k = line.split("=", 1)[1].strip().strip('"').strip("'")
        except Exception:
            pass
    return or_k, onomeo_k

def chat_worker(question: str, gen: int):
    # If a batch is actively being sent over Bluetooth, wait for it to finish
    # so we don't collide on the single RFCOMM Bluetooth channel.
    wait_start = time.time()
    while CURRENT_BATCH["id"] is not None and time.time() - wait_start < 25.0:
        if gen != CHAT_STATE["gen"]:
            return
        time.sleep(0.5)

    or_k, onomeo_k = load_chat_keys()
    cmd = [str(BLUEBRIDGE_SEND_BIN), "--chat", question,
           "--device", TARGET_PHONE_MAC]
    sub_env = {**os.environ, "OPENROUTER_KEY": or_k, "ONOMEO_KEY": onomeo_k, "TARGET_PHONE_MAC": TARGET_PHONE_MAC}
    try:
        p = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=sub_env)
    except Exception as e:
        print(f"  [CHAT] spawn failed: {e}")
        return
    track_proc(p)
    try:
        rc = p.wait(timeout=180)
    except subprocess.TimeoutExpired:
        try:
            p.kill()
        except Exception:
            pass
        rc = -1
    finally:
        untrack_proc(p)
    if gen != CHAT_STATE["gen"]:
        print(f"  [CHAT] answer discarded (cancelled/reset superseded).")
        return
    if rc == 0 and CHAT_FILE.exists():
        print(f"  [CHAT] answer ready -> right-edge HUD.")
    else:
        print(f"  [CHAT] failed (exit {rc}).")
    CHAT_STATE["active"] = False

def start_chat(question: str) -> bool:
    question = (question or "").strip()
    if not question:
        return False
    if CHAT_STATE["active"]:
        return False
    try:
        if CHAT_FILE.exists():
            CHAT_FILE.unlink()
    except Exception:
        pass
    CHAT_STATE["active"] = True
    t = threading.Thread(target=chat_worker, args=(question, CHAT_STATE["gen"]), daemon=True)
    t.start()
    print(f"  [CHAT] asking ({len(question)} chars) via phone Onomeo DeepSeek-v4...")
    return True

def cancel_chat():
    CHAT_STATE["gen"] += 1
    CHAT_STATE["active"] = False
    kill_tracked_procs()
    try:
        if CHAT_FILE.exists():
            CHAT_FILE.unlink()
    except Exception:
        pass
    print(f"  [CHAT] cancelled.")

def reset_chat():
    """Chrome ] : full chat reset — in-flight killed, buffer dead, memory file
    wiped, HUD cleared on next sync. Chat scope only: batches/answers untouched."""
    CHAT_STATE["gen"] += 1
    CHAT_STATE["active"] = False
    kill_tracked_procs()
    for f in [CHAT_FILE, CHAT_HIST_FILE]:
        try:
            if f.exists():
                f.unlink()
        except Exception:
            pass
    print(f"  [CHAT] memory reset to 0 (fresh).")

def phone_control(args, timeout=25):
    """Best-effort one-shot control frame (cancel/pause) on a fresh connection."""
    try:
        cmd = [str(BLUEBRIDGE_SEND_BIN)] + args + ["--device", TARGET_PHONE_MAC]
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return r.returncode == 0
    except Exception as e:
        print(f"  [!] phone control failed: {e}")
        return False

def reset_all():
    """Chrome [ : stop thinking + zero folders + stop links on both ends, ready next."""
    print(f"\n[!] [WHOLE RESET] stopping everything, zeroing state...")
    CHAT_STATE["gen"] += 1
    CHAT_STATE["active"] = False
    kill_tracked_procs()
    phone_control(["--cancel-all"], timeout=30)
    bid = CURRENT_BATCH["id"]
    if bid:
        for d in [BATCHES_DIR / bid]:
            try:
                if d.exists():
                    shutil.rmtree(str(d), ignore_errors=True)
            except Exception:
                pass
        for f in [ANSWERS_DIR / f"{bid}_answers.json", ANSWERS_DIR / f"{bid}_live.json"]:
            try:
                if f.exists():
                    f.unlink()
            except Exception:
                pass
    for f in [ANSWERS_DIR / "latest_answers.json", CHAT_FILE]:
        try:
            if f.exists():
                f.unlink()
        except Exception:
            pass
    try:
        for sf in Path(tempfile.gettempdir()).glob("blue_sent_*.json"):
            sf.unlink()  # send-resume state dies with a whole-reset (phone was wiped)
    except Exception:
        pass
    _STABLE.clear()  # stale stability ticks must not instantly retrigger leftovers
    BRIDGE_STATE.get_and_consume_forced()
    CURRENT_BATCH["id"] = None
    print(f"[✓] Reset done. HUD blank, folders zeroed, ready for next commands.\n")

def wake_overlays():
    """Chrome , : show overlays again (clears / hide). No-op if already shown."""
    IS_PAUSED["v"] = False
    try:
        if PAUSED_FILE.exists():
            PAUSED_FILE.unlink()
    except Exception:
        pass
    print(f"\n[▶] OVERLAYS SHOWN (wake key).")

def sudden_kill():
    """Chrome . : sudden total kill. Stops solver/links/HUDs, wipes derived data
    per the fresh-start rule, terminal returns at once. Nothing listens after."""
    print(f"\n[!] [SUDDEN KILL] shutting everything down...")
    try:
        kill_tracked_procs()
    except Exception:
        pass
    try:
        stop_notch_app()
    except Exception:
        pass
    os._exit(0)

def quick_phone_ping(timeout=25) -> bool:
    """One fast liveness probe (circuit-breaker use). Never raises."""
    try:
        r = subprocess.run([str(BLUEBRIDGE_SEND_BIN), "--ping", "--device", TARGET_PHONE_MAC],
                           capture_output=True, text=True, timeout=timeout)
        return r.returncode == 0
    except Exception:
        return False

PHONE_LIVE = {"ok": None, "t": 0.0, "fails": 0}  # None = unknown yet

def idle_pinger():
    """Every 60s: one fast phone ping while idle. Silent on steady-state;
    LOUD on second consecutive failure; recovery notice + park release on return.
    Skipped while a batch is in flight (the transfer itself proves liveness —
    a concurrent ping would false-alarm against the busy server socket)."""
    while True:
        time.sleep(60)
        try:
            if CURRENT_BATCH["id"] is not None:
                continue
            ok = quick_phone_ping()
            PHONE_LIVE["t"] = time.time()
            if ok:
                if PHONE_LIVE["fails"] > 0:
                    print(f"\n[✓] Phone reachable again (idle pinger).")
                    if PARKED["on"]:
                        PARKED["on"] = False
                        DEAD_STREAK["n"] = 0
                        print(f"[✓] Auto-resumed from park.\n")
                PHONE_LIVE["ok"] = True
                PHONE_LIVE["fails"] = 0
            else:
                PHONE_LIVE["fails"] += 1
                PHONE_LIVE["ok"] = False
                if PHONE_LIVE["fails"] == 2:
                    print(f"\n[!!!] IDLE PINGER: phone not answering 2 min running.")
                    print(f"[!!!] New saves will queue; batch runs confirm before burning time.\n")
        except Exception:
            pass

def toggle_pause() -> bool:
    """Chrome / : overlays-only toggle. Hides Mac HUDs; watchers, pings, sends,
    extraction, phone solving all CONTINUE (never killed, never frozen)."""
    IS_PAUSED["v"] = not IS_PAUSED["v"]
    paused = IS_PAUSED["v"]
    try:
        if paused:
            PAUSED_FILE.write_text("1", encoding="utf-8")
        elif PAUSED_FILE.exists():
            PAUSED_FILE.unlink()
    except Exception:
        pass
    if paused:
        print(f"\n[II] OVERLAYS HIDDEN — everything keeps working underneath. Press / again to show.\n")
    else:
        print(f"\n[▶] OVERLAYS SHOWN.\n")
    return paused

# ==============================================================================
# 2. NOTCH OVERLAY PROCESS LIFECYCLE
# ==============================================================================

notch_proc = None

def start_notch_app():
    global notch_proc
    if NOTCH_BIN.exists():
        try:
            # Kill any leftover instances
            subprocess.run((["taskkill", "/F", "/IM", "pythonw.exe"] if os.name == "nt" else ["pkill", "-f", "blue_notch"]), capture_output=True)
            time.sleep(0.2)
            notch_proc = subprocess.Popen([str(NOTCH_BIN)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            print(f"  [✓] Blue Notch Native macOS Overlay launched (PID: {notch_proc.pid})")
        except Exception as e:
            print(f"  [!] Could not launch Blue Notch overlay: {e}")

def wipe_runtime_data():
    """Terminal exiting = clean slate for DERIVED data only (batches, answers).
    User ORIGINALS in raw_sort_batch/ are NEVER touched. Nothing is hard-deleted:
    everything goes to .trash/ (pruned after 7 days). .gitkeep markers survive."""
    for d in [BATCHES_DIR, ANSWERS_DIR]:
        try:
            if d.exists():
                for child in d.iterdir():
                    try:
                        if child.name == ".gitkeep":
                            continue
                        if child.is_dir() and not child.name.startswith("."):
                            trash_file(child)
                        elif child.is_file():
                            trash_file(child)
                    except Exception:
                        pass
        except Exception:
            pass
    print(f"  [*] Derived runtime data trashed (batches, answers). Originals untouched.")

def stop_notch_app():
    global notch_proc
    wipe_runtime_data()
    if notch_proc and notch_proc.poll() is None:
        try:
            notch_proc.terminate()
            notch_proc.wait(timeout=1.0)
        except Exception:
            try:
                notch_proc.kill()
            except Exception:
                pass
    subprocess.run((["taskkill", "/F", "/IM", "pythonw.exe"] if os.name == "nt" else ["pkill", "-f", "blue_notch"]), capture_output=True)
    # On exit keep last-good answers (deleting them stranded the HUD with stale state).
    # Only volatile bridge files are cleared; fresh answers state happens at startup.
    for p in [Path(tempfile.gettempdir()) / Path("blue_active_q.txt"), Path(tempfile.gettempdir()) / Path("blue_forced_imgs.json")]:
        try:
            if p.exists(): p.unlink()
        except Exception:
            pass

# ==============================================================================
# 3. UTILITIES & DIRECTORIES
# ==============================================================================

def clear_screen():
    print("\033[2J\033[H", end="")

def trash_file(p: Path):
    """Move to .trash/<timestamp>/ instead of hard delete. Pruned after 7 days."""
    try:
        trash_root = BASE_DIR / ".trash"
        day_dir = trash_root / time.strftime("%Y%m%d")
        day_dir.mkdir(parents=True, exist_ok=True)
        dest = day_dir / f"{time.strftime('%H%M%S')}_{p.name}"
        if p.is_dir():
            shutil.move(str(p), str(dest))
        elif p.exists():
            shutil.move(str(p), str(dest))
        # Prune trash older than 7 days
        now = time.time()
        for d in trash_root.iterdir():
            try:
                if d.is_dir() and now - d.stat().st_mtime > 7 * 86400:
                    shutil.rmtree(str(d), ignore_errors=True)
            except Exception:
                pass
    except Exception as e:
        print(f"  [!] Trash move failed for {p.name} ({e}); leaving in place.")

def ensure_dirs():
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    SORT_DIR.mkdir(parents=True, exist_ok=True)
    BATCHES_DIR.mkdir(parents=True, exist_ok=True)
    ANSWERS_DIR.mkdir(parents=True, exist_ok=True)
    # Clean up old session files on startup (preserve latest_answers unless --fresh)
    clean_paths = [Path(tempfile.gettempdir()) / Path("blue_active_q.txt"), Path(tempfile.gettempdir()) / Path("blue_forced_imgs.json")]
    if "--fresh" in sys.argv:
        clean_paths.append(ANSWERS_DIR / "latest_answers.json")
    for p in clean_paths:
        try:
            if p.exists(): p.unlink()
        except Exception:
            pass

def check_and_ensure_bluetooth() -> dict:
    """Check BT power and turn ON if OFF. Unknown state is reported, never faked."""
    if not BT_CTRL_BIN.exists():
        return {"power": None, "name": "Unknown", "connected": False}
    try:
        res = subprocess.run([str(BT_CTRL_BIN), "status", TARGET_PHONE_MAC], capture_output=True, text=True, timeout=5)
        info = json.loads(res.stdout.strip())
        if not isinstance(info, dict) or "power" not in info:
            raise ValueError("bad bt_ctrl JSON")
        if not info.get("power"):
            print("[*] Bluetooth is OFF. Turning Bluetooth ON...")
            subprocess.run([str(BT_CTRL_BIN), "power-on"], capture_output=True, timeout=5)
            time.sleep(1)
            res2 = subprocess.run([str(BT_CTRL_BIN), "status", TARGET_PHONE_MAC], capture_output=True, text=True, timeout=5)
            info = json.loads(res2.stdout.strip())
        return info
    except Exception as e:
        return {"power": None, "name": "Error", "error": str(e), "connected": False}

_batch_seq_counter = 0

def get_next_batch_id() -> str:
    """Determine collision-free timestamp unique batch ID: batch_YYYYMMDD_HHMMSS_mmm."""
    global _batch_seq_counter
    _batch_seq_counter += 1
    now = datetime.now()
    base = f"batch_{now.strftime('%Y%m%d_%H%M%S')}_{now.microsecond // 1000:03d}"
    candidate = base
    while (SORT_DIR / candidate).exists() or (BATCHES_DIR / candidate).exists():
        candidate = f"{base}_{_batch_seq_counter}"
        _batch_seq_counter += 1
    return candidate

def unpack_mhtml(mhtml_file: Path, target_dir: Path) -> Path:
    """Unpack MHTML file into target_dir with index.html and assets."""
    target_dir.mkdir(parents=True, exist_ok=True)
    with open(mhtml_file, "rb") as f:
        msg = email.message_from_binary_file(f, policy=policy.default)
        
    html_content = None
    assets_dir = target_dir / "assets"
    assets_dir.mkdir(exist_ok=True)
    
    part_mapping = {}
    parts = list(msg.iter_parts()) if msg.is_multipart() else [msg]
    
    for idx, part in enumerate(parts):
        content_type = part.get_content_type()
        content_loc = part.get("Content-Location", f"part_{idx}")
        # cid: references must resolve to the same local file
        content_id = (part.get("Content-ID", "") or "").strip().strip("<>")
        payload = part.get_payload(decode=True)
        if payload is None:
            continue

        if content_type == "text/html" and html_content is None:
            html_content = (payload, part.get_content_charset() or "utf-8")
        else:
            filename = Path(content_loc).name.split("?")[0]
            if not filename or len(filename) > 50:
                ext = mimetypes.guess_extension(content_type) or ".bin"
                filename = f"asset_{idx}{ext}"
            # Unique-ify: same name from different URLs must not overwrite
            asset_file = assets_dir / filename
            dup = 1
            while asset_file.exists():
                asset_file = assets_dir / f"{asset_file.stem}_{dup}{asset_file.suffix}"
                dup += 1
            with open(asset_file, "wb") as af:
                af.write(payload)
            part_mapping[content_loc] = f"./assets/{asset_file.name}"
            if content_id:
                part_mapping[f"cid:{content_id}"] = f"./assets/{asset_file.name}"

    if html_content:
        raw_html, charset = html_content
        try:
            html_str = raw_html.decode(charset, errors="strict")
        except (UnicodeDecodeError, LookupError):
            try:
                html_str = raw_html.decode("utf-8", errors="strict")
            except UnicodeDecodeError:
                html_str = raw_html.decode("latin-1", errors="replace")
        for orig_url, local_rel in sorted(part_mapping.items(), key=lambda x: len(x[0]), reverse=True):
            html_str = html_str.replace(orig_url, local_rel)
            
        index_file = target_dir / "index.html"
        with open(index_file, "w", encoding="utf-8") as out_h:
            out_h.write(html_str)
        return index_file
    else:
        dest = target_dir / mhtml_file.name
        shutil.copy2(mhtml_file, dest)
        return dest

def dir_size(p: Path) -> int:
    try:
        if p.is_file():
            return p.stat().st_size
        if p.is_dir():
            return sum(f.stat().st_size for f in p.rglob('*') if f.is_file())
    except Exception:
        pass
    return -1

# path -> [last_size, stable_ticks]. Non-blocking: needs 3 consecutive stable 0.8s ticks (~2.4s).
_STABLE = {}

def is_file_writing_complete(filepath: Path) -> bool:
    """Non-blocking stability check: True after 3 consecutive equal-size ticks (~2.4s).
    The companion _files folder counts toward stability AND is scanned for
    in-flight Chrome part-files (.crdownload/.tmp/.download): HTML often lands
    long before its assets finish downloading."""
    key = str(filepath)
    if not filepath.exists():
        _STABLE.pop(key, None)  # vanished mid-write: drop the entry, don't leak it
        return False
    if len(_STABLE) > 500:  # months-long supervisor: prune oldest entries without wiping active ones
        oldest_keys = list(_STABLE.keys())[:250]
        for ok in oldest_keys:
            _STABLE.pop(ok, None)
    cur = dir_size(filepath)
    if cur <= 0:
        _STABLE.pop(key, None)
        return False
    comp_candidates = [
        filepath.parent / f"{filepath.stem}_files",
        filepath.parent / f"{filepath.stem}_berkas",
        filepath.parent / f"{filepath.stem}_file"
    ]
    for comp in comp_candidates:
        if comp.exists() and comp.is_dir():
            cur += dir_size(comp)
            try:
                for part in comp.rglob("*"):
                    if part.is_file() and part.suffix.lower() in (".crdownload", ".tmp", ".download", ".part"):
                        _STABLE[key] = [cur, 0]  # assets still landing: reset stability
                        return False
            except Exception:
                pass
            break
    prev = _STABLE.get(key)
    if prev is not None and prev[0] == cur and prev[1] + 1 >= 3:
        _STABLE.pop(key, None)
        return True
    _STABLE[key] = [cur, (prev[1] + 1) if prev and prev[0] == cur else 1]
    return False

def print_banner(bt_info: dict):
    print("=" * 80)
    print("                                   APPLE                                       ")
    print("=" * 80)
    _pw = bt_info.get("power")
    # bt_ctrl reports power as int 1/0; failures surface as None
    bt_status = "ON" if _pw in (True, 1) else ("OFF" if _pw in (False, 0) else "? UNKNOWN")
    phone_name = bt_info.get("name", "yoo")
    connected_str = "CONNECTED" if bt_info.get("connected") else "PAIRED (STANDBY)"
    
    print(f"  [Bluetooth]    : Power {bt_status} | Target: {phone_name} [{TARGET_PHONE_MAC}] ({connected_str})")
    print(f"  [HTTP Bridge]  : Active on -> http://127.0.0.1:20261 (Chrome Extension Sync)")
    print(f"  [Native Overlay]: Blue Notch (1cm x 1cm Left Edge Slide-Out HUD)")
    print(f"  [Watchers]     : raw_batches/ AND ~/Downloads (Ctrl+S Auto-Detection)")
    plus_str = "PLUS (Audio Waiting & Auto-Sweep Active)" if PLUS_MODE else "STANDARD"
    print(f"  [Operating Mode]: {plus_str}")
    print(f"  [AI Engine]    : Gemini Rotation Pool on Phone (Adaptive English/Indo Prompt)")
    print("=" * 80)
    print("[STATUS] System fully active. Save page in Chrome (Ctrl+S) or drop into raw_batches/\n")

# ==============================================================================
# 4. FULL BATCH WORKFLOW
# ==============================================================================

def mark_solving(batch_id: str):
    """Instant stale-killer: the moment a new batch starts, the HUD file becomes
    a solving-state — the previous exam's answers can never display under new
    question numbers again, even if this run dies halfway."""
    CURRENT_BATCH["id"] = batch_id
    try:
        (ANSWERS_DIR / "latest_answers.json").write_text(
            json.dumps({"_status": "solving", "_batch": batch_id}), encoding="utf-8")
    except Exception:
        pass

def _process_incoming_batch_inner(item_path: Path):
    """Full workflow: Sort -> Extract -> Send to Phone -> AI Solve -> Notch Sync."""
    batch_id = get_next_batch_id()
    mark_solving(batch_id)
    # P5 report card: every fact this run learns, one JSON file.
    rep = {"batch": batch_id, "mode": "auto",
           "t_start": time.strftime("%Y-%m-%d %H:%M:%S"),
           "source": str(item_path)}
    t_batch0 = time.time()
    print(f"\n" + ("#" * 80))
    print(f"[*] [★ NEW EXAM FILE DETECTED]: {item_path.name}")
    print(f"[*] Assigned Smart Batch ID: {batch_id}")
    print("#" * 80)
    
    # Check user-marked forced images (peek: cleared only after successful send)
    forced_list = BRIDGE_STATE.peek_forced()
    if forced_list:
        print(f"  [*] Applied Forced Image Overrides (`): {forced_list}")
    
    # 1. SORT & MOVE
    print(f"\n[1/4 SORTING] Moving raw files into: {SORT_DIR / batch_id}")
    target_sort_folder = SORT_DIR / batch_id
    target_sort_folder.mkdir(parents=True, exist_ok=True)
    
    html_target_path = None
    
    if item_path.is_file():
        ext = item_path.suffix.lower()
        if ext == ".mhtml":
            print(f"  [*] Detected MHTML archive. Unpacking HTML & embedded assets...")
            html_target_path = unpack_mhtml(item_path, target_sort_folder)
            trash_file(item_path)  # trash, never hard-delete sources
            print(f"  [✓] Unpacked to: {html_target_path}")
        elif ext in [".html", ".htm"]:
            dest_html = target_sort_folder / item_path.name
            shutil.move(str(item_path), str(dest_html))
            html_target_path = dest_html
            
            # Move companion asset folder (_files, _berkas, _file) if present
            base_stem = item_path.stem
            for comp_suffix in ["_files", "_berkas", "_file"]:
                companion_folder = item_path.parent / f"{base_stem}{comp_suffix}"
                if companion_folder.exists() and companion_folder.is_dir():
                    dest_files = target_sort_folder / f"{base_stem}{comp_suffix}"
                    print(f"  [*] Moving companion asset folder: {companion_folder.name}...")
                    if dest_files.exists():
                        shutil.rmtree(str(dest_files))
                    shutil.move(str(companion_folder), str(dest_files))
                    break
            print(f"  [✓] Moved HTML & assets to {target_sort_folder}")
        else:
            dest = target_sort_folder / item_path.name
            shutil.move(str(item_path), str(dest))
            html_target_path = dest

        # PLUS MODE: Sweep any waiting audio files from the source folder into target_sort_folder
        if PLUS_MODE:
            audio_exts = {".mp3", ".wav", ".m4a", ".ogg", ".aac", ".opus", ".weba"}
            try:
                source_dir = item_path.parent if item_path.is_file() else item_path
                if source_dir.exists():
                    waiting_audios = [
                        af for af in source_dir.iterdir()
                        if af.is_file() and af.suffix.lower() in audio_exts
                        and not af.name.startswith(".")
                        and not af.name.endswith(".crdownload")
                        and not af.name.endswith(".download")
                    ]
                    if waiting_audios:
                        print(f"  [♫ PLUS AUDIO] Found {len(waiting_audios)} waiting audio file(s) in {source_dir.name}:")
                        for waf in waiting_audios:
                            dest_audio = target_sort_folder / waf.name
                            shutil.copy2(str(waf), str(dest_audio))
                            print(f"    -> Swept waiting audio into batch: {waf.name}")
            except Exception as e:
                print(f"  [!] Audio sweep notice: {e}")
    elif item_path.is_dir():
        target_sort_folder.rmdir()
        shutil.move(str(item_path), str(target_sort_folder))
        htmls = sorted(list(target_sort_folder.glob("*.html")) + list(target_sort_folder.glob("*.htm")))
        # Prefer index.html when a full-page save drops several files
        html_target_path = None
        for h in htmls:
            if h.name.lower() == "index.html":
                html_target_path = h
                break
        html_target_path = html_target_path or (htmls[0] if htmls else None)
        if len(htmls) > 1:
            print(f"  [!] {len(htmls)} HTML files in drop; using {html_target_path.name}")
        
    if not html_target_path or not html_target_path.exists():
        print(f"[!] Could not locate valid HTML in {target_sort_folder}. Aborting batch.")
        rep.update({"status": "fail", "fail_at": "sort", "fail_why": "no valid HTML",
                    "t_end": time.strftime("%Y-%m-%d %H:%M:%S")})
        write_report(rep)
        return
        
    # 2. CBT SMART HYBRID EXTRACTION
    target_extracted_batch = BATCHES_DIR / batch_id
    target_extracted_batch.mkdir(parents=True, exist_ok=True)
    print(f"\n[2/4 EXTRACTING] Running WebKit headless engine on {html_target_path.name}...")
    
    extract_cmd = [str(CBT_EXTRACT_BIN), str(html_target_path), str(target_extracted_batch)]
    if forced_list:
        extract_cmd.extend(["--force-imgs", ",".join(str(n) for n in forced_list)])

    try:
        res = subprocess.run(extract_cmd, timeout=360)  # extractor has own 300s cap
    except subprocess.TimeoutExpired:
        print(f"[!] Extraction hung >6min. Partial batch kept in {target_extracted_batch}.")
        return
    if res.returncode != 0:
        print(f"[!] Extraction returned error code {res.returncode} (marks kept for retry).")
        return

    extracted_files = sorted(list(target_extracted_batch.glob("*_soal.*")))
    if not extracted_files:
        print(f"[!] Extractor found 0 questions (unknown CBT template?). Marks kept; batch kept at {target_extracted_batch}.")
        rep.update({"status": "fail", "fail_at": "extract", "fail_why": "0 questions",
                    "t_end": time.strftime("%Y-%m-%d %H:%M:%S")})
        write_report(rep)
        return
    print(f"[✓] Extraction finished. Total {len(extracted_files)} questions ready in {target_extracted_batch}.")
    rep["extracted_files"] = len(extracted_files)
    rep["extract_secs"] = round(time.time() - t_batch0, 1)
    
    # 3. PREPARE KEYS FOR PHONE
    print(f"\n[3/4 API KEYS] Packaging Gemini API rotation keys from .env...")
    keys_file = target_extracted_batch / "keys.json"
    env_file = BASE_DIR / ".env"
    key_list = []
    if env_file.exists():
        with open(env_file) as ef:
            for line in ef:
                line = line.strip()
                # Gemini pool only: chat keys (OPENROUTER/CEREBRAS) ride the chat path
                if line.startswith("GEMINI_KEY_") and "=" in line:
                    _, v = line.split("=", 1)
                    v = v.strip()
                    if v and "here" not in v:
                        key_list.append(v)
    if not key_list:
        print(f"[!] .env has 0 API keys — refusing to send a batch the phone cannot solve (marks kept).")
        print(f"[*] Add keys to .env (GEMINI_KEY_1=...) and resend: ./blue {batch_id}")
        rep.update({"status": "fail", "fail_at": "keys", "fail_why": "0 keys in .env",
                    "t_end": time.strftime("%Y-%m-%d %H:%M:%S")})
        write_report(rep)
        return
    keys_data = {
        "keys": key_list,
        "model": "gemini-3.5-flash-lite"
    }
    with open(keys_file, "w", encoding="utf-8") as kf:
        json.dump(keys_data, kf, indent=2)
    print(f"  [✓] Packaged keys.json with {len(key_list)} rotation keys.")

    files_to_send = []
    if keys_file.exists():
        files_to_send.append(str(keys_file))
    files_to_send.extend([str(f) for f in extracted_files])

    # Wait for phone to be ready — ping loop, up to 60s
    print(f"\n[4/4 BLUETOOTH & ON-PHONE AI] Connecting to phone {TARGET_PHONE_MAC} to solve {batch_id}...")
    if CHAT_STATE["active"]:
        print("  [*] Prioritizing batch transmission: cancelling in-flight chat...")
        cancel_chat()
        time.sleep(0.5)
    check_and_ensure_bluetooth()

    print(f"[*] Probing Blue app on phone (patient mode: rides out screen-off freeze)...")
    phone_ready = False
    fast_fails = 0
    attempt = 0
    # Phase 1: 5 quick attempts. Fast refuses (frozen signature) trigger Phase 2:
    # 7 more attempts with growing backoff (~5 min total) instead of dying at 60s.
    for attempt in range(1, 13):
        phase2 = attempt > 5
        print(f"  [~] Ping attempt {attempt}/12 ...", end=" ", flush=True)
        t0 = time.time()
        try:
            ping_res = subprocess.run(
                [str(BLUEBRIDGE_SEND_BIN), "--ping", "--device", TARGET_PHONE_MAC],
                capture_output=True, text=True, timeout=15  # 15s: RFCOMM open can take ~8-10s
            )
            dt = time.time() - t0
            if ping_res.returncode == 0:
                print(f"[✓] PHONE ONLINE & READY")
                phone_ready = True
                break
            else:
                print(f"[not ready, exit={ping_res.returncode}]")
                if dt < 5.0:
                    fast_fails += 1
                if attempt == 2 and not phase2:
                    print(f"  [!] Screen OFF freezes the phone app (Oppo App Freeze).")
                    print(f"  [!] On phone: Fix Background Running + allow background activity,")
                    print(f"  [!] then run the 2-min self-test. Staying patient automatically...")
        except subprocess.TimeoutExpired:
            print(f"[timeout — RFCOMM taking too long, retrying]")
        except Exception as e:
            print(f"[error: {e}]")
        if attempt == 5 and fast_fails < 2:
            break  # slow fails = phone really gone; don't burn 5 minutes
        time.sleep(2.0 if attempt < 5 else (5 if attempt < 8 else 10))

    if not phone_ready:
        print(f"[!] Phone didn't respond after patient probing. Attempting send anyway (service may still accept)...")
    rep.update({"ping_attempts": attempt, "ping_fast_fails": fast_fails,
                "phone_ready": phone_ready})

    send_cmd = [
        str(BLUEBRIDGE_SEND_BIN),
        "--batch-id", batch_id,
        "--device", TARGET_PHONE_MAC,
        "--retry", "3"
    ] + files_to_send

    # Retry loop: up to 3 full send attempts (tracked so [ whole-reset can kill them)
    from subprocess import Popen
    bt_res = None
    CURRENT_BATCH["id"] = batch_id
    for send_attempt in range(1, 4):
        if send_attempt > 1:
            print(f"\n[!] Send attempt {send_attempt}/3 — Waiting 5s before retry...")
            time.sleep(5.0)
            check_and_ensure_bluetooth()
        proc = None
        try:
            proc = Popen(send_cmd)
            track_proc(proc)
            try:
                rc = proc.wait(timeout=360)  # 6min max for large batches
            finally:
                untrack_proc(proc)
            bt_res = subprocess.CompletedProcess(send_cmd, rc)
            if rc == 0:
                break
            print(f"  [!] Send attempt {send_attempt} failed (Exit code: {rc})")
        except subprocess.TimeoutExpired:
            print(f"  [!] Send attempt {send_attempt} timed out after 6 minutes")
            try:
                if proc is not None and proc.poll() is None:
                    proc.kill()
            except Exception:
                pass
            if proc is not None:
                untrack_proc(proc)
        except Exception as e:
            print(f"  [!] Send attempt {send_attempt} error: {e}")
            if proc is not None:
                untrack_proc(proc)


    # 5. SYNC LATEST ANSWERS FOR NOTCH APP
    batch_ans_file = ANSWERS_DIR / f"{batch_id}_answers.json"
    latest_ans_file = ANSWERS_DIR / "latest_answers.json"
    live_file = ANSWERS_DIR / f"{batch_id}_live.json"
    if batch_ans_file.exists():
        shutil.copy2(str(batch_ans_file), str(latest_ans_file))
        print(f"[✓] Updated latest_answers.json -> Blue Notch HUD immediately refreshed!")
    elif live_file.exists():
        # Partial progress is better than stale answers from a previous exam
        shutil.copy2(str(live_file), str(latest_ans_file))
        print(f"[~] Only partial answers arrived — showing live progress (PARTIAL, not final).")
    try:
        rep["send_attempts"] = send_attempt
        rep["send_rc"] = bt_res.returncode if bt_res is not None else None
    except Exception:
        pass
    ans_count, ans_auto = -1, []
    try:
        for cand in [batch_ans_file, live_file]:
            if cand.exists():
                _d = json.loads(cand.read_text(encoding="utf-8"))
                ans_count = len([k for k in _d if not str(k).startswith("_")])
                _m = _d.get("_meta", {})
                ans_auto = _m.get("auto_filled", []) if isinstance(_m, dict) else []
                break
    except Exception:
        pass
    rep.update({"answers": ans_count, "auto_filled": ans_auto})

    if bt_res is not None and bt_res.returncode == 0:
        DEAD_STREAK["n"] = 0  # alive: streak reset, park impossible
        rep.update({"status": "ok"})
        BRIDGE_STATE.get_and_consume_forced()  # success: clear marks now
        CURRENT_BATCH["id"] = None
        LAST_SOLVED_BATCH["id"] = batch_id
        print(f"  [*] Sustainability Loop: Notes consumed and reset to 0 for next batch.")
        print(f"\n[✓] Batch successfully processed and solved by Android phone!")
        print(f"\n[★ BATCH {batch_id} FULL PIPELINE COMPLETE!]")
        print(f"  • Raw Files   : {target_sort_folder}")
        print(f"  • Extracted   : {target_extracted_batch}")
        print(f"  • Phone Batch : /Android/data/com.bluebridge.app/files/batches/{batch_id}/")
        print(f"  • Answers     : {batch_ans_file}\n")
    else:
        rc = bt_res.returncode if bt_res else "N/A"
        rep.update({"status": "fail", "fail_at": "send", "fail_why": f"exit {rc}",
                    "send_rc": rc})
        # P1: fully-dead run (no ping AND send failed) feeds the breaker.
        if not phone_ready:
            DEAD_STREAK["n"] += 1
            rep["dead_streak"] = DEAD_STREAK["n"]
            if DEAD_STREAK["n"] >= 2 and not PARKED["on"]:
                PARKED["on"] = True
                PARKED["last_probe"] = 0.0
                print(f"\n[!!!] PHONE DEAD x{DEAD_STREAK['n']} — parking supervisor. New saves queue up, nothing burns.")
                print(f"[!!!] Auto-resumes on first successful ping. Manual override: ./blue <batch_id>\n")
        print(f"\n[!] Bluetooth transfer failed or was interrupted (Exit code: {rc}).")
        print(f"[*] Marks KEPT for retry. Raw+extracted kept on disk — nothing deleted.")
        print(f"[*] Recover: 1) open Blue app + Start Service, 2) run: ./blue {batch_id}")
        print(f"[*] Or pull solved answers directly: ./blue_fetch {batch_id} (see bluebridge_send --fetch-answers)")
        print(f"[*] Ensure Blue app is open and 'Start Service' is active on your phone.")
        print(f"[-] BATCH {batch_id} FAILED\n")
    rep.update({"t_end": time.strftime("%Y-%m-%d %H:%M:%S"),
                "total_secs": round(time.time() - t_batch0, 1)})
    write_report(rep)
    print("-" * 80)
    print("[STATUS] Watching for next batch or exam save...\n")

def process_incoming_batch(item_path: Path):
    try:
        _process_incoming_batch_inner(item_path)
    finally:
        CURRENT_BATCH["id"] = None

def send_existing_batch(batch_name_or_path: str):
    ensure_dirs()
    check_and_ensure_bluetooth()
    bp = Path(batch_name_or_path)
    if not bp.exists() and (BATCHES_DIR / batch_name_or_path).exists():
        bp = BATCHES_DIR / batch_name_or_path
    
    if not bp.exists() or not bp.is_dir():
        print(f"[!] Batch directory not found: {batch_name_or_path}")
        return
        
    batch_id = bp.name
    mark_solving(batch_id)
    rep = {"batch": batch_id, "mode": "resend", "t_start": time.strftime("%Y-%m-%d %H:%M:%S"),
           "source": str(bp)}
    t_r0 = time.time()
    # Refresh keys.json from current .env: stored batches carry stale pools
    # (e.g. 7 keys vs 10 now); resending dead keys burns quota and fails solves.
    try:
        fresh_keys = []
        with open(BASE_DIR / ".env") as ef:
            for line in ef:
                line = line.strip()
                if line.startswith("GEMINI_KEY_") and "=" in line:
                    _, v = line.split("=", 1)
                    v = v.strip()
                    if v and "here" not in v:
                        fresh_keys.append(v)
        if fresh_keys:
            with open(bp / "keys.json", "w", encoding="utf-8") as kf:
                json.dump({"keys": fresh_keys, "model": "gemini-3.5-flash-lite"}, kf, indent=2)
            print(f"  [✓] Refreshed keys.json with {len(fresh_keys)} current keys.")
    except Exception as e:
        print(f"  [!] keys.json refresh failed ({e}); sending stored copy.")
    files = sorted(list(bp.glob("*")))
    # Never resend answers/live/tmp/manifest sidecars; skip subdirs (sender takes files)
    files_to_send = [str(f) for f in files
                     if f.is_file()
                     and not f.name.endswith(("_answers.json", "_live.json", ".tmp"))
                     and f.name not in ("answers.json", "keys.json.bak", "manifest.json")
                     and not f.name.startswith(".")]
    
    print(f"[*] Dispatching {batch_id} ({len(files_to_send)} files) to phone {TARGET_PHONE_MAC}...")
    send_cmd = [
        str(BLUEBRIDGE_SEND_BIN),
        "--batch-id", batch_id,
        "--device", TARGET_PHONE_MAC,
        "--retry", "3"
    ] + files_to_send
    try:
        rc = subprocess.run(send_cmd).returncode
    except Exception:
        rc = None
    if rc == 0:
        DEAD_STREAK["n"] = 0
    rep.update({"status": "ok" if rc == 0 else "fail", "send_rc": rc,
                "files": len(files_to_send),
                "t_end": time.strftime("%Y-%m-%d %H:%M:%S"),
                "total_secs": round(time.time() - t_r0, 1)})
    write_report(rep)

# ==============================================================================
# 5. MAIN SUPERVISOR LOOP
# ==============================================================================

def main_loop():
    ensure_dirs()
    preflight_bins()
    start_http_bridge(20261)
    threading.Thread(target=idle_pinger, daemon=True).start()
    start_notch_app()
    
    bt_info = check_and_ensure_bluetooth()
    clear_screen()
    print_banner(bt_info)
    
    last_bt_check = time.time()
    last_hb_warn = 0.0
    seen_downloads = set()
    
    # Snapshot existing files in Downloads to avoid re-triggering old files.
    # Identity = (name, mtime_ns, size): a re-saved exam with the SAME name is a
    # NEW file (Chrome reuses the name after we moved the previous save away).
    def _dl_ident(f):
        try:
            st = f.stat()
            return (f.name, st.st_mtime_ns, st.st_size)
        except Exception:
            return None
    try:
        for f in DOWNLOADS_DIR.iterdir():
            ident = _dl_ident(f)
            if ident is not None:
                seen_downloads.add(ident)
    except Exception:
        pass
        
    while True:
        try:
            # / hides overlays only — watchers NEVER freeze (pause kills nothing).
            # P1 circuit breaker: while parked (phone dead x2), skip all processing.
            # The idle pinger owns probing cadence; reuse its fresh verdict here
            # instead of opening a second connection every minute.
            now = time.time()
            if PARKED["on"]:
                fresh = (now - PHONE_LIVE["t"] < 90)
                alive = PHONE_LIVE["ok"] if fresh else quick_phone_ping()
                if alive:
                    PARKED["on"] = False
                    DEAD_STREAK["n"] = 0
                    print(f"\n[✓] Phone back — resuming queued saves.\n")
                time.sleep(1.0)
                continue
            # Periodic BT check every 15 seconds
            now = time.time()
            if now - last_bt_check > 15:
                check_and_ensure_bluetooth()
                last_bt_check = now
                # Chrome tracker silent? Extension missing/disabled/dead.
                if LAST_CHROME_HB["t"] > 0 and now - LAST_CHROME_HB["t"] > 300 and now - last_hb_warn > 300:
                    print("[!] No Chrome extension heartbeat for 5+ min — is the extension enabled?")
                    last_hb_warn = now

            # Check 1: raw_batches/ folder (only HTML exam files or directories containing HTML)
            raw_entries = [p for p in RAW_DIR.iterdir() if not p.name.startswith(".") and not p.name.endswith(".download") and not p.name.endswith(".crdownload")]
            candidates = []
            for e in raw_entries:
                if e.name.endswith("_files"):
                    continue
                if e.is_file():
                    if e.suffix.lower() in [".html", ".htm", ".mhtml"]:
                        candidates.append(e)
                elif e.is_dir():
                    if any(h.suffix.lower() in [".html", ".htm"] for h in e.iterdir() if h.is_file()):
                        candidates.append(e)
            
            for candidate in candidates:
                if is_file_writing_complete(candidate):
                    process_incoming_batch(candidate)
                    break

            # Check 2: ~/Downloads/ folder for Ctrl+S saved CBT exams
            try:
                for f in DOWNLOADS_DIR.iterdir():
                    if f.name.startswith(".") or f.name.endswith(".crdownload") or f.name.endswith(".download"):
                        continue
                    ident = _dl_ident(f)
                    if ident is None or ident in seen_downloads:
                        continue
                    ext = f.suffix.lower()
                    if PLUS_MODE and ext in [".mp3", ".wav", ".m4a", ".ogg", ".aac", ".opus", ".weba"]:
                        # In PLUS mode, audio waits in Downloads for the exam HTML to arrive
                        continue
                    if ext in [".html", ".htm", ".mhtml"]:
                        # Check if writing complete (non-blocking stable ticks)
                        if is_file_writing_complete(f):
                            # Verify if it's an exam/CBT page
                            is_cbt = False
                            try:
                                with open(f, "rb") as check_f:
                                    header_snippet = check_f.read(65536).decode("utf-8", errors="ignore").lower()
                                    keywords = (
                                        "soal", "cbt", "ujian", "question", "calculus", "exam", "test",
                                        "assessment", "quiz", "matematika", "fisika", "kimia", "biologi",
                                        "sejarah", "sumatif", "pts", "pas", "pat", "evaluasi", "review",
                                        "pilihan", "pertanyaan", "data-nomor", "currentquestion",
                                        'class="soal', 'type="radio"'
                                    )
                                    if any(k in header_snippet for k in keywords):
                                        is_cbt = True
                            except Exception:
                                pass
                                
                            if len(seen_downloads) > 5000:
                                seen_downloads = set(list(seen_downloads)[-2500:])
                            if is_cbt:
                                seen_downloads.add(ident)
                                print(f"\n[*] [Ctrl+S DETECTED] Found CBT exam saved in Downloads: {f.name}")
                                process_incoming_batch(f)
                                break
                            else:
                                seen_downloads.add(ident)
                                print(f"  [~] Downloads: skipped non-exam file {f.name}")
            except Exception:
                pass
                    
            time.sleep(0.8)
            
        except KeyboardInterrupt:
            print("\n\n[*] Stopping Blue Master Terminal & closing Notch Overlay... Goodbye!")
            stop_notch_app()
            sys.exit(0)
        except Exception as e:
            print(f"[!] Unexpected error in main loop: {e}")
            time.sleep(2.0)

def handle_exit_signals(signum, frame):
    stop_notch_app()
    sys.exit(0)

signal.signal(signal.SIGINT, handle_exit_signals)
signal.signal(signal.SIGTERM, handle_exit_signals)

def report_env():
    """P5v2: provenance stamped on every report (ends 'which build/which phone' disputes)."""
    env = {}
    try:
        r = subprocess.run(["git", "-C", str(BASE_DIR), "rev-parse", "--short", "HEAD"],
                           capture_output=True, text=True, timeout=5)
        if r.returncode == 0:
            env["git"] = r.stdout.strip()
    except Exception:
        pass
    try:
        adb = shutil.which("adb") or os.environ.get("ADB_PATH") or os.path.expanduser("~/Library/Android/sdk/platform-tools/adb") or (Path.home() / "Documents/moova/.tools/android-sdk/platform-tools/adb").as_posix()
        m = subprocess.run([adb, "shell", "getprop", "ro.product.model"],
                           capture_output=True, text=True, timeout=10)
        if m.returncode == 0 and m.stdout.strip():
            env["phone"] = m.stdout.strip()
    except Exception:
        pass
    return env

def write_report(rep: dict):
    """P5: one JSON report card per batch run (proves what happened)."""
    try:
        rep.setdefault("env", report_env())
    except Exception:
        pass
    try:
        with open(ANSWERS_DIR / f"report_{rep.get('batch', 'unknown')}.json", "w", encoding="utf-8") as rf:
            json.dump(rep, rf, indent=2)
    except Exception as e:
        print(f"  [!] report write failed: {e}")

def print_report(batch_id: str):
    """./blue report <batch_id>: one readable page settling any dispute."""
    rp = ANSWERS_DIR / f"report_{batch_id}.json"
    if not rp.exists():
        # Fall back to whatever the answers file itself can prove.
        ap = ANSWERS_DIR / f"{batch_id}_answers.json"
        if ap.exists():
            try:
                _d = json.loads(ap.read_text(encoding="utf-8"))
                ks = [k for k in _d if not str(k).startswith("_")]
                from collections import Counter
                mods = Counter(v.get("model", "?") for v in _d.values() if isinstance(v, dict))
                auto = _d.get("_meta", {}).get("auto_filled", [])
                print(f"Batch {batch_id}: {len(ks)} answers, auto-filled {auto}")
                print(f"Models: {dict(mods)} (no run report — solved before reporting existed)")
                return
            except Exception as e:
                print(f"[!] unreadable answers file: {e}")
                return
        print(f"[!] No report and no answers for {batch_id}.")
        return
    try:
        r = json.loads(rp.read_text(encoding="utf-8"))
    except Exception as e:
        print(f"[!] unreadable report: {e}")
        return
    print(f"=== REPORT {r.get('batch')} ({r.get('mode')}) ===")
    print(f"  window : {r.get('t_start')} -> {r.get('t_end')} ({r.get('total_secs')}s)")
    print(f"  source : {r.get('source')}")
    print(f"  status : {r.get('status')}" + (f" @ {r.get('fail_at')}: {r.get('fail_why')}" if r.get('status') == 'fail' else ""))
    if "extracted_files" in r:
        print(f"  extract: {r['extracted_files']} files in {r.get('extract_secs')}s")
    if "ping_attempts" in r:
        print(f"  ping   : {r['ping_attempts']} attempts, {r.get('ping_fast_fails')} fast-fails, ready={r.get('phone_ready')}")
    if "send_attempts" in r:
        print(f"  send   : {r['send_attempts']} attempts, rc={r.get('send_rc')}")
    elif r.get("send_rc") is not None:
        print(f"  send   : rc={r['send_rc']}" + (f", {r['files']} files" if "files" in r else ""))
    if "answers" in r and r["answers"] is not None and r["answers"] >= 0:
        print(f"  answers: {r['answers']} (auto-filled: {r.get('auto_filled', [])})")
    # Phone-side chunk forensics (why questions went missing), when present.
    try:
        ap = ANSWERS_DIR / f"{batch_id}_answers.json"
        if ap.exists():
            _d = json.loads(ap.read_text(encoding="utf-8"))
            _m = _d.get("_meta", {})
            if isinstance(_m, dict):
                if _m.get("failed_chunks"):
                    print(f"  phone chunk failures: {json.dumps(_m['failed_chunks'], ensure_ascii=False)}")
                from collections import Counter as _C
                _mods = _C(v.get("model", "?") for k, v in _d.items()
                           if isinstance(v, dict) and not str(k).startswith("_"))
                if _mods:
                    print(f"  models: {dict(_mods)}")
    except Exception:
        pass
    if r.get("dead_streak"):
        print(f"  dead-streak after run: {r['dead_streak']}")

def fetch_batch_answers(batch_id: str):
    """Recovery path: pull stored answers.json from phone without resending."""
    ensure_dirs()
    preflight_bins()
    rc = subprocess.run([str(BLUEBRIDGE_SEND_BIN), "--fetch-answers", batch_id,
                         "--device", TARGET_PHONE_MAC]).returncode
    if rc == 0:
        src = ANSWERS_DIR / f"{batch_id}_answers.json"
        if src.exists():
            shutil.copy2(str(src), str(ANSWERS_DIR / "latest_answers.json"))
            print(f"[✓] Fetched + promoted latest_answers.json for {batch_id}")
    else:
        print(f"[!] Fetch failed (exit {rc}). Phone needs solved batch {batch_id} stored.")

if __name__ == "__main__":
    ensure_dirs()
    preflight_bins()
    PLUS_MODE = "--plus" in sys.argv or "--bg-plus" in sys.argv
    clean_argv = [a for a in sys.argv if a not in ("--plus", "--bg-plus")]
    if len(clean_argv) > 1:
        arg = clean_argv[1]
        arg_path = Path(arg)
        if arg in ("--daemon", "--bg"):
            main_loop()
        elif arg == "--alpha":
            import alpha_solver
            alpha_solver.run_alpha_solve()
        elif arg == "--stop":
            stop_notch_app()
            print("[*] Stopped Notch Overlay and daemons.")
            sys.exit(0)
        elif arg in ("fetch", "--fetch-answers") and len(clean_argv) > 2:
            fetch_batch_answers(clean_argv[2])
        elif arg == "report" and len(clean_argv) > 2:
            print_report(clean_argv[2])  # read-only: never ensure_dirs (would wipe latest)
        elif arg_path.is_file() and arg_path.suffix.lower() in [".html", ".htm", ".mhtml"]:
            process_incoming_batch(arg_path)
        elif (BATCHES_DIR / arg).is_dir() or arg_path.is_dir():
            send_existing_batch(arg)
        else:
            print(f"[!] Unknown batch or file: {arg}")
            print(f"Usage:")
            print(f"  ./blue                  (Run supervisor - auto watch Downloads & raw_batches/)")
            print(f"  ./blue --bg             (Run standard background daemon)")
            print(f"  ./blue --bg-plus        (Run background daemon with Audio Waiting + Auto-Sweep)")
            print(f"  ./blue batch_02         (Send specific batch directly)")
            print(f"  ./blue fetch batch_02   (Pull stored answers from phone, no resend)")
            print(f"  ./blue \"Ujian Siswa.html\" (Extract & send specific HTML directly)")
    else:
        main_loop()
