#!/usr/bin/env python3
"""
==============================================================================
    ALPHA SOLVER: DeepSeek-V4.1-Flash Vision CBT Solver Pipeline
    - Triggered by '9' key or CLI: ./blue --alpha
    - Extracts all questions from the latest HTML as high-DPI screenshots
    - Divides questions into chunks of 3 (10 chunks for 30 questions)
    - Submits parallel vision requests to EdgeOne DeepSeek-V4.1-Flash
    - Employs deep reasoning / high thinking prompt with strict JSON output
    - Handles all 3 CBT question types (Multiple Choice, Complex, True/False)
    - Assembles final answers and syncs to Mac HUD and Android Phone App (ALPHA tab)
==============================================================================
"""

import os
import sys
import re
import json
import time
import base64
import shutil
import subprocess
import urllib.request
import urllib.error
from pathlib import Path
from datetime import datetime
from concurrent.futures import ThreadPoolExecutor, as_completed

BASE_DIR = Path(__file__).resolve().parent
ANSWERS_DIR = BASE_DIR / "answers"
RAW_DIR = BASE_DIR / "raw_batches"
RAW_SORT_DIR = BASE_DIR / "raw_sort_batch"
BATCHES_DIR = BASE_DIR / "batches"
CBT_SCREENS_BIN = BASE_DIR / "cbt_screens"

ALPHA_LATEST_FILE = ANSWERS_DIR / "alpha_latest_answers.json"
ALPHA_STATUS_FILE = Path("/tmp/blue_alpha_status.json")

# Load environment variables from .env if present
def _load_env():
    env_file = BASE_DIR / ".env"
    if env_file.exists():
        with open(env_file, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    k = k.strip()
                    v = v.strip().strip('"').strip("'")
                    if k and k not in os.environ:
                        os.environ[k] = v

_load_env()

# EdgeOne AI Gateway Configuration
API_BASE_URL = "https://ai-gateway.edgeone.link/v1"
API_CHAT_URL = f"{API_BASE_URL}/chat/completions"
API_KEY = os.environ.get("EDGEONE_API_KEY", "")
MODEL_NAME = "@makers/deepseek-v4.1-flash"

DOWNLOADS_DIR = Path(os.environ.get("DOWNLOADS_DIR", str(Path.home() / "Downloads")))
adb_path = shutil.which("adb") or os.environ.get("ADB_PATH", str(Path.home() / "Documents/moova/.tools/android-sdk/platform-tools/adb"))
ADB_BIN = Path(adb_path)

_ALPHA_MUTEX = threading.Lock()

def log(msg: str):
    ts = datetime.now().strftime("%H:%M:%S")
    print(f"[{ts}] [ALPHA] {msg}", flush=True)

def find_latest_html() -> Path:
    """Find the most recent valid CBT HTML file from Downloads or raw_batches."""
    cand = []
    # 1. Check raw_batches
    if RAW_DIR.exists():
        cand.extend([p for p in RAW_DIR.glob("*.htm*") if p.is_file() and not p.name.startswith(".")])

    # 2. Check Downloads
    if DOWNLOADS_DIR.exists():
        for p in DOWNLOADS_DIR.glob("*.htm*"):
            if p.is_file() and not p.name.startswith(".") and not p.name.endswith(".download") and not p.name.endswith(".crdownload"):
                cand.append(p)

    # 3. Check raw_sort_batch subdirectories
    if RAW_SORT_DIR.exists():
        for d in RAW_SORT_DIR.glob("batch_*"):
            if d.is_dir():
                cand.extend([p for p in d.glob("*.htm*") if p.is_file() and not p.name.startswith(".")])

    if cand:
        cand.sort(key=lambda p: p.stat().st_mtime, reverse=True)
        return cand[0]

    raise FileNotFoundError("No CBT HTML file found in Downloads, raw_batches, or raw_sort_batch.")

def encode_image_base64(path: Path) -> str:
    with open(path, "rb") as f:
        return base64.b64encode(f.read()).decode("utf-8")

def clean_json_text(raw: str) -> str:
    """Extract valid JSON from raw text even if wrapped in markdown or notes."""
    raw = raw.strip()
    # Strip markdown ```json ... ``` fences
    if "```" in raw:
        match = re.search(r"```(?:json)?\s*([\s\S]*?)\s*```", raw)
        if match:
            raw = match.group(1).strip()
    # Find outer bracket or brace
    b_idx = raw.find("{")
    arr_idx = raw.find("[")
    if b_idx != -1 and (arr_idx == -1 or b_idx < arr_idx):
        end_idx = raw.rfind("}")
        if end_idx != -1:
            raw = raw[b_idx:end_idx+1]
    elif arr_idx != -1:
        end_idx = raw.rfind("]")
        if end_idx != -1:
            raw = raw[arr_idx:end_idx+1]
    return raw

def call_edgeone_chunk(chunk_idx: int, target_q_nums: list, image_paths: list) -> dict:
    """
    Sends 3 question screenshots to EdgeOne DeepSeek-V4.1-Flash.
    Returns normalized answers dict for target_q_nums.
    """
    system_prompt = """You are the core high-precision CBT Examination Grading and Evaluation Engine.
Your task is to analyze the provided question screenshots, perform rigorous high-level logical reasoning, and output the exact correct answer for each question.

QUESTION TYPES:
1. 'pilihan_ganda' (Single-choice Multiple Choice):
   - Output must be a single uppercase letter: 'A', 'B', 'C', 'D', or 'E'.
2. 'pilihan_ganda_kompleks' (Multiple Selection / Complex Multiple Choice):
   - Output must be comma-separated sorted uppercase letters, e.g. 'A, C' or 'B, D, E'.
3. 'benar_salah' (Statements / True or False):
   - Output must follow the format '1.B 2.S 3.S' where the number is the statement index, followed by 'B' (Benar/True) or 'S' (Salah/False).

INSTRUCTIONS:
- Perform deep thinking and step-by-step reasoning for each question before answering.
- Examine the question number displayed on each screenshot (nomor_tertera).
- Return ONLY a raw JSON object with no markdown wrappers (do NOT wrap with ```json or ```), no introductory or concluding text.

JSON FORMAT SPECIFICATION:
{
  "answers": [
    {
      "image_slot": 1,
      "nomor_tertera": 1,
      "tipe": "pilihan_ganda",
      "jawaban": "A",
      "penjelasan": "Brief concise explanation."
    }
  ]
}"""

    prompt_intro = f"Analyze these {len(image_paths)} exam question screenshots carefully.\n"
    for i, q in enumerate(target_q_nums, 1):
        prompt_intro += f"Image {i} corresponds to Question Slot {q}.\n"
    prompt_intro += "\nSolve all questions with 100% precision. Return STRICT JSON ONLY following the required format."

    user_content = [{"type": "text", "text": prompt_intro}]
    for img_path in image_paths:
        b64 = encode_image_base64(img_path)
        user_content.append({
            "type": "image_url",
            "image_url": {"url": f"data:image/jpeg;base64,{b64}"}
        })

    payload = {
        "model": MODEL_NAME,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_content}
        ],
        "temperature": 0.1
    }

    req_body = json.dumps(payload).encode("utf-8")
    headers = {
        "Authorization": f"Bearer {API_KEY}",
        "Content-Type": "application/json"
    }

    req = urllib.request.Request(API_CHAT_URL, data=req_body, headers=headers, method="POST")

    max_retries = 3
    for attempt in range(1, max_retries + 1):
        try:
            start_t = time.time()
            with urllib.request.urlopen(req, timeout=60) as resp:
                elapsed = time.time() - start_t
                res_data = json.loads(resp.read().decode("utf-8"))
                choice = res_data["choices"][0]["message"]
                raw_content = choice.get("content", "")
                reasoning = choice.get("reasoning_content", "")
                
                log(f"Chunk {chunk_idx+1} [Q{target_q_nums[0]}-Q{target_q_nums[-1]}] answered in {elapsed:.2f}s")
                
                clean_json = clean_json_text(raw_content)
                parsed = json.loads(clean_json)
                
                # Normalize parsed answers
                ans_list = []
                if isinstance(parsed, dict):
                    ans_list = parsed.get("answers", []) or parsed.get("questions", [])
                    if not ans_list and "nomor_tertera" in parsed:
                        ans_list = [parsed]
                elif isinstance(parsed, list):
                    ans_list = parsed

                chunk_results = {}
                for idx, q_num in enumerate(target_q_nums):
                    q_data = None
                    # Try finding by slot or nomor_tertera
                    for item in ans_list:
                        if not isinstance(item, dict):
                            continue
                        slot = item.get("image_slot")
                        nomor = item.get("nomor_tertera") or item.get("nomor") or item.get("no")
                        if slot == (idx + 1) or nomor == q_num:
                            q_data = item
                            break
                    # Fallback by index
                    if not q_data and idx < len(ans_list) and isinstance(ans_list[idx], dict):
                        q_data = ans_list[idx]

                    if q_data:
                        raw_ans = str(q_data.get("jawaban") or "").strip()
                        q_type = str(q_data.get("tipe") or "pilihan_ganda").lower().strip()
                        penjelasan = str(q_data.get("penjelasan") or "").strip()
                        
                        # Post-process answer format
                        if q_type == "pilihan_ganda" and len(raw_ans) == 1:
                            raw_ans = raw_ans.upper()
                        elif "kompleks" in q_type:
                            parts = [p.strip().upper() for p in re.findall(r"[A-Ea-e]", raw_ans)]
                            if parts:
                                raw_ans = ", ".join(sorted(list(set(parts))))
                        elif "benar" in q_type or "salah" in q_type or "pernyataan" in q_type:
                            # Standardize 1.B 2.S format
                            bs_matches = re.findall(r"(\d+)[\.\s]*([BSbs])", raw_ans)
                            if bs_matches:
                                raw_ans = " ".join([f"{num}.{letter.upper()}" for num, letter in bs_matches])

                        chunk_results[f"{q_num:02d}"] = {
                            "nomor": q_num,
                            "tipe": q_type,
                            "jawaban": raw_ans or "-",
                            "penjelasan": penjelasan
                        }
                    else:
                        chunk_results[f"{q_num:02d}"] = {
                            "nomor": q_num,
                            "tipe": "pilihan_ganda",
                            "jawaban": "-",
                            "penjelasan": "No response for slot"
                        }
                return chunk_results

        except Exception as e:
            log(f"[!] Chunk {chunk_idx+1} attempt {attempt} failed: {e}")
            if attempt < max_retries:
                time.sleep(1.5 * attempt)
            else:
                log(f"[-] Chunk {chunk_idx+1} permanently failed. Generating empty slots.")
                fail_res = {}
                for q_num in target_q_nums:
                    fail_res[f"{q_num:02d}"] = {
                        "nomor": q_num,
                        "tipe": "pilihan_ganda",
                        "jawaban": "-",
                        "penjelasan": f"Error: {e}"
                    }
                return fail_res

def run_alpha_solve():
    """Main execution entry point."""
    if not _ALPHA_MUTEX.acquire(blocking=False):
        log("Solve already in progress. Ignoring duplicate trigger.")
        return

    try:
        log("=======================================================")
        log("       STARTING ALPHA VISION CBT SOLVER PIPELINE       ")
        log("=======================================================")

        # Preflight check cbt_screens
        if not CBT_SCREENS_BIN.exists():
            log(f"[-] cbt_screens binary missing at {CBT_SCREENS_BIN}. Compiling...")
            subprocess.run(["swiftc", "-O", "-framework", "Foundation", "-framework", "WebKit", "-framework", "AppKit",
                            str(BASE_DIR / "export_all_screens.swift"), "-o", str(CBT_SCREENS_BIN)], check=True)

        # Mark status as solving
        ALPHA_STATUS_FILE.write_text(json.dumps({
            "status": "solving",
            "start_time": time.time()
        }), encoding="utf-8")

        # 1. Locate HTML
        html_file = find_latest_html()
        log(f"Target CBT HTML: {html_file}")

        # 2. Setup Batch Directory
        now = datetime.now()
        ts = f"{now.strftime('%Y%m%d_%H%M%S')}_{now.microsecond // 1000:03d}"
        batch_id = f"alpha_batch_{ts}"
        batch_dir = BATCHES_DIR / batch_id
        screens_dir = batch_dir / "screens"
        screens_dir.mkdir(parents=True, exist_ok=True)
        ANSWERS_DIR.mkdir(parents=True, exist_ok=True)

        # 3. Extract all questions as screenshots using WebKit headless
        log("Extracting full questions as screenshots via cbt_screens...")
        proc = subprocess.run([str(CBT_SCREENS_BIN), str(html_file), str(screens_dir)],
                              capture_output=True, text=True, timeout=120)
        if proc.returncode != 0:
            log(f"[!] cbt_screens failed with code {proc.returncode}: {proc.stderr[:100]}")

        # Collect and sort image files
        img_files = sorted(list(screens_dir.glob("soal_*.jpg")), key=lambda p: int(re.search(r"\d+", p.name).group() if re.search(r"\d+", p.name) else 0))
        if not img_files:
            # Fallback check any jpg
            img_files = sorted(list(screens_dir.glob("*.jpg")))
        
        total_q = len(img_files)
        log(f"Successfully extracted {total_q} question screenshot(s).")
        if total_q == 0:
            log("[-] No screenshots produced. Aborting.")
            return

        # 4. Divide into chunks of 3 questions
        chunk_size = 3
        chunks = []
        for i in range(0, total_q, chunk_size):
            chunk_imgs = img_files[i:i+chunk_size]
            chunk_nums = [i + j + 1 for j in range(len(chunk_imgs))]
            chunks.append((len(chunks), chunk_nums, chunk_imgs))

        log(f"Divided {total_q} questions into {len(chunks)} parallel chunks (3 questions each).")

        # 5. Dispatch parallel solves to EdgeOne DeepSeek-V4.1-Flash
        all_answers = {}
        start_solve_t = time.time()
        with ThreadPoolExecutor(max_workers=5) as executor:
            future_to_chunk = {
                executor.submit(call_edgeone_chunk, c_idx, c_nums, c_imgs): c_idx
                for c_idx, c_nums, c_imgs in chunks
            }
            for future in as_completed(future_to_chunk):
                c_idx = future_to_chunk[future]
                try:
                    res = future.result()
                    all_answers.update(res)
                except Exception as e:
                    log(f"[!] Chunk {c_idx+1} generated unhandled exception: {e}")

        total_solve_time = time.time() - start_solve_t
        log(f"All {len(chunks)} chunks solved in {total_solve_time:.2f} seconds!")

        # 6. Format final results
        final_payload = {
            "batch_id": batch_id,
            "engine": "DeepSeek-V4.1-Flash (Vision)",
            "gateway": "EdgeOne AI Gateway",
            "solved_at": datetime.now().isoformat(),
            "solve_duration_sec": round(total_solve_time, 2),
            "total_questions": total_q,
            "answers": all_answers
        }

        # 7. Write answers locally
        batch_answers_file = batch_dir / "alpha_answers.json"
        batch_answers_file.write_text(json.dumps(final_payload, indent=2), encoding="utf-8")
        
        named_answers_file = ANSWERS_DIR / f"{batch_id}_answers.json"
        named_answers_file.write_text(json.dumps(final_payload, indent=2), encoding="utf-8")

        ALPHA_LATEST_FILE.write_text(json.dumps(final_payload, indent=2), encoding="utf-8")
        log(f"[✓] Saved local answers to {ALPHA_LATEST_FILE.name} and {named_answers_file.name}")

        # 8. Sync to Android Phone
        sync_to_phone(batch_id, batch_answers_file)

        # 9. Mark status as done
        ALPHA_STATUS_FILE.write_text(json.dumps({
            "status": "done",
            "batch_id": batch_id,
            "total": total_q,
            "time": time.time()
        }), encoding="utf-8")

        log("=======================================================")
        log("            ALPHA SOLVER PIPELINE COMPLETE             ")
        log("=======================================================")

    except Exception as e:
        log(f"[!] Pipeline fatal error: {e}")
        import traceback
        traceback.print_exc()
        ALPHA_STATUS_FILE.write_text(json.dumps({
            "status": "error",
            "error": str(e)
        }), encoding="utf-8")
    finally:
        _ALPHA_MUTEX.release()

def sync_to_phone(batch_id: str, answers_file: Path):
    """Syncs alpha answers to connected Android phone via ADB."""
    if not ADB_BIN.exists() or not os.access(ADB_BIN, os.X_OK):
        log("ADB binary not available; skipping phone sync.")
        return

    try:
        # Check connected devices
        dev_res = subprocess.run([str(ADB_BIN), "devices"], capture_output=True, text=True, timeout=5)
        lines = [line.strip() for line in dev_res.stdout.splitlines() if line.strip() and not line.startswith("List")]
        if not lines:
            log("No Android phone connected via ADB; answers saved locally.")
            return

        phone_batch_dir = f"/sdcard/Android/data/com.bluebridge.app/files/batches/{batch_id}"
        subprocess.run([str(ADB_BIN), "shell", "mkdir", "-p", phone_batch_dir], capture_output=True, timeout=10)
        
        # Push batch answers and latest_alpha.json
        subprocess.run([str(ADB_BIN), "push", str(answers_file), f"{phone_batch_dir}/alpha_answers.json"],
                       capture_output=True, timeout=15)
        subprocess.run([str(ADB_BIN), "push", str(answers_file), "/sdcard/Android/data/com.bluebridge.app/files/batches/latest_alpha.json"],
                       capture_output=True, timeout=15)

        # Broadcast intent to app
        subprocess.run([str(ADB_BIN), "shell", "am", "broadcast", "-a", "com.bluebridge.app.ALPHA_UPDATED",
                        "--es", "batch_id", batch_id], capture_output=True, timeout=10)
        log("[✓] Successfully synced Alpha answers to phone and broadcasted update!")

    except Exception as e:
        log(f"[!] Phone sync failed: {e}")

if __name__ == "__main__":
    run_alpha_solve()
