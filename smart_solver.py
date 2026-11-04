#!/usr/bin/env python3
"""
Smart Batch Exam Solver with Key Rotation & Multimodal Routing.
- Groups text questions into 1 batch call.
- Groups image questions into 2-image chunk calls (total ~5 calls).
- Auto-rotates across 5 API keys with 1-second interval.
- Auto-retries with next key on 429/error.
- Saves answers.json and renders clean terminal table.
"""

import os
import sys
import glob
import json
import time
import base64
import re
import urllib.request
import urllib.error

from pathlib import Path
ENV_PATH = str(Path(__file__).resolve().parent / ".env")
MODEL_NAME = "gemini-3.5-flash-lite"
API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"

class KeyManager:
    def __init__(self, env_file=ENV_PATH):
        self.keys = []
        if os.path.exists(env_file):
            with open(env_file) as f:
                for line in f:
                    line = line.strip()
                    if line and not line.startswith("#") and line.startswith("GEMINI_KEY_") and "=" in line:
                        _, v = line.split("=", 1)
                        if v.strip():
                            self.keys.append(v.strip())
        if not self.keys:
            raise ValueError(f"No API keys found in {env_file}")
        self.current_index = 0
        print(f"[*] Loaded {len(self.keys)} API keys into rotation pool.")

    def get_key(self):
        return self.keys[self.current_index], self.current_index + 1

    def next_key(self):
        self.current_index = (self.current_index + 1) % len(self.keys)
        return self.keys[self.current_index], self.current_index + 1

def clean_json_text(raw: str) -> str:
    raw = raw.strip()
    if "```" in raw:
        match = re.search(r"```(?:json)?\s*([\s\S]*?)\s*```", raw)
        if match:
            raw = match.group(1).strip()
    b_idx = raw.find("{")
    arr_idx = raw.find("[")
    if b_idx != -1 and (arr_idx == -1 or b_idx < arr_idx):
        e_idx = raw.rfind("}")
        if e_idx > b_idx:
            raw = raw[b_idx:e_idx + 1]
    elif arr_idx != -1:
        e_idx = raw.rfind("]")
        if e_idx > arr_idx:
            raw = raw[arr_idx:e_idx + 1]
    return raw

def call_gemini_with_rotation(key_manager, parts, system_instruction=None, max_retries=5):
    """Call Gemini API with auto key rotation and 1s interval."""
    for attempt in range(max_retries):
        key, key_num = key_manager.get_key()
        url = f"{API_BASE}/{MODEL_NAME}:generateContent?key={key}"
        
        payload = {
            "contents": [{"parts": parts}],
            "generationConfig": {
                "responseMimeType": "application/json",
                "temperature": 0.1,
                "topP": 0.95,
                "maxOutputTokens": 16384,
                "thinkingConfig": {"thinkingLevel": "LOW"}
            }
        }
        if system_instruction:
            payload["system_instruction"] = {
                "parts": [{"text": system_instruction}]
            }
        
        req = urllib.request.Request(
            url,
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"}
        )
        
        t0 = time.time()
        try:
            with urllib.request.urlopen(req, timeout=90) as resp:
                elapsed = time.time() - t0
                body = json.loads(resp.read().decode("utf-8"))
                candidates = body.get("candidates", [])
                if not candidates:
                    raise ValueError("Model returned empty candidates list")
                text = candidates[0].get("content", {}).get("parts", [{}])[0].get("text", "").strip()
                cleaned = clean_json_text(text)
                parsed = json.loads(cleaned)
                key_manager.next_key()
                time.sleep(1.0) # 1 sec interval
                return parsed, key_num, elapsed
                
        except urllib.error.HTTPError as e:
            elapsed = time.time() - t0
            err_msg = e.read().decode("utf-8", errors="replace")
            print(f"\n    [!] Key {key_num} returned HTTP {e.code} ({err_msg[:60]}). Rotating to next key...")
            key_manager.next_key()
            time.sleep(1.0)
            
        except json.JSONDecodeError as e:
            elapsed = time.time() - t0
            print(f"\n    [!] JSON parse failure from Key {key_num}: {e}. Retrying with key rotation...")
            key_manager.next_key()
            time.sleep(1.0)

        except Exception as e:
            elapsed = time.time() - t0
            print(f"\n    [!] Error on Key {key_num}: {e}. Rotating to next key...")
            key_manager.next_key()
            time.sleep(1.0)
            
    raise RuntimeError("All retries and key rotations failed.")

def solve_exam_batch(batch_dir):
    print("=======================================================")
    print("    SMART BATCH EXAM SOLVER (5-CALL KEY-ROTATED)")
    print("=======================================================")
    print(f"[*] Target Directory: {batch_dir}")
    print(f"[*] Engine: {MODEL_NAME}")
    
    km = KeyManager()
    
    # 1. Discover all questions
    txt_files = sorted(glob.glob(os.path.join(batch_dir, "*_soal.txt")))
    jpg_files = sorted(glob.glob(os.path.join(batch_dir, "*_soal.jpg")))
    
    print(f"[*] Detected {len(txt_files)} text questions and {len(jpg_files)} image questions.")
    
    all_answers = {}
    call_log = []
    
    total_calls_planned = (1 if txt_files else 0) + ((len(jpg_files) + 1) // 2)
    current_call = 1
    
    # --- CALL 1: All Text Questions in 1 Single Call ---
    if txt_files:
        print(f"\n[Call {current_call}/{total_calls_planned}] Batching {len(txt_files)} pure text questions into 1 API call...")
        sys_inst = (
            "You are an elite academic examination solver with 100% precision.\n"
            "Answer strictly in the same language as the questions (Indonesian -> Bahasa Indonesia; English -> English).\n"
            "Format: Return a single JSON object where keys are 2-digit question numbers ('01', '02') and values have:\n"
            "- 'analysis': concise step-by-step reasoning (reason-first)\n"
            "- 'answer': choice letter (e.g. 'A. option text') or matrix statement array\n"
            "- 'reason': concise justification\n"
            "No markdown code fences, pure JSON only."
        )
        content_text = ""
        for tf in txt_files:
            with open(tf, "r", encoding="utf-8", errors="replace") as f:
                content_text += "\n" + f.read() + "\n---"
                
        parts = [{"text": content_text}]
        
        t_start = time.time()
        res_json, key_used, elapsed = call_gemini_with_rotation(km, parts, system_instruction=sys_inst)
        print(f"    [✓] Solved {len(res_json)} questions via Key {key_used} in {elapsed:.2f}s.")
        
        for q_num, ans_data in res_json.items():
            formatted_num = q_num.zfill(2)
            all_answers[formatted_num] = ans_data
            call_log.append((formatted_num, key_used, f"Call {current_call} (Text Batch)"))
            
        current_call += 1

    # --- CALLS 2+: Image Questions in 2-Image Chunks ---
    if jpg_files:
        chunk_size = 2
        for i in range(0, len(jpg_files), chunk_size):
            chunk = jpg_files[i:i + chunk_size]
            q_names = [os.path.basename(f).split("_")[0] for f in chunk]
            print(f"\n[Call {current_call}/{total_calls_planned}] Batching image questions [{', '.join(q_names)}] into 1 API call...")
            
            sys_inst = (
                "You are an elite exam solver. Inspect the provided images and solve each question with 100% precision.\n"
                "Return a JSON object where keys are 2-digit question numbers and values contain 'analysis', 'answer', and 'reason'.\n"
                "Language lock: Bahasa Indonesia for Indonesian exams, English for English exams. No markdown, raw JSON only."
            )
            parts = []
            
            for jf in chunk:
                q_num = os.path.basename(jf).split("_")[0]
                parts.append({"text": f"Question {q_num}:"})
                with open(jf, "rb") as img_file:
                    b64_data = base64.b64encode(img_file.read()).decode("utf-8")
                parts.append({
                    "inline_data": {
                        "mime_type": "image/jpeg",
                        "data": b64_data
                    }
                })
                
            res_json, key_used, elapsed = call_gemini_with_rotation(km, parts, system_instruction=sys_inst)
            print(f"    [✓] Solved {len(res_json)} question(s) via Key {key_used} in {elapsed:.2f}s.")
            
            for q_num, ans_data in res_json.items():
                formatted_num = q_num.zfill(2)
                all_answers[formatted_num] = ans_data
                call_log.append((formatted_num, key_used, f"Call {current_call} (Image Chunk)"))
                
            current_call += 1

    # 3. Save answers.json
    out_file = os.path.join(batch_dir, "answers.json")
    with open(out_file, "w", encoding="utf-8") as f:
        json.dump(all_answers, f, indent=2, ensure_ascii=False)
        
    print("\n=======================================================")
    print("                    FINAL RESULTS                      ")
    print("=======================================================")
    print(f"{'No':<4} | {'Jawaban':<8} | {'Key':<5} | {'API Call':<22} | {'Alasan / Penjelasan'}")
    print("-" * 80)
    
    sorted_questions = sorted(all_answers.keys(), key=lambda x: int(x) if x.isdigit() else 999)
    call_dict = {q: (k, c) for q, k, c in call_log}
    
    for q_num in sorted_questions:
        raw_ans = all_answers[q_num].get("answer", "-")
        ans = ", ".join(raw_ans) if isinstance(raw_ans, list) else str(raw_ans)
        reason = str(all_answers[q_num].get("reason", "-")).replace("\n", " ")[:38]
        k_used, c_used = call_dict.get(q_num, ("-", "-"))
        print(f"{q_num:<4} | {ans:<8} | Key {k_used:<1} | {c_used:<22} | {reason}")
        
    print("-" * 80)
    print(f"[✓] Total Questions Solved: {len(all_answers)}")
    print(f"[✓] Total API Calls Used  : {current_call - 1} calls (Quota saved: {len(all_answers) - (current_call - 1)} calls!)")
    print(f"[✓] Saved to               : {out_file}")
    print("=======================================================\n")

if __name__ == "__main__":
    default_batch = str(Path(__file__).resolve().parent / "batches" / "batch_01")
    target = sys.argv[1] if len(sys.argv) > 1 else default_batch
    solve_exam_batch(target)
