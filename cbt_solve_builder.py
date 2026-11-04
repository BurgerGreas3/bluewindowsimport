#!/usr/bin/env python3
"""
CBT Exam Solver via Google Cloud GenAI App Builder (Discovery Engine).
Strictly adheres to PROTOCOL_GENAI_BUILDER.md:
- Uses Discovery Engine 'default_search:answer'
- Mandatory Header: X-Goog-User-Project: project-a1bdd6c3-0562-4d45-91e
- Consumes Rp 17.2M GenAI App Builder trial credit (Rp 0 charged to credit card)
"""

import os
import sys
import json
import time
import re
import glob
import subprocess
import requests
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent

PROJECT_ID = "project-a1bdd6c3-0562-4d45-91e"
ENGINE_ID = "gemini-search-engine"
SERVING_CONFIG = "default_search"
DATA_STORE_ID = "gemini-search-ds"

def get_auth_token() -> str:
    """Fetch fresh OAuth token using gcloud CLI."""
    try:
        token = subprocess.check_output(
            ["gcloud", "auth", "print-access-token"],
            stderr=subprocess.PIPE
        ).decode("utf-8").strip()
        return token
    except subprocess.CalledProcessError as e:
        print(f"[!] Error getting gcloud token: {e}", file=sys.stderr)
        sys.exit(1)

def ocr_image_apple_vision(image_path: str) -> str:
    """Fast local OCR using macOS native Apple Vision framework."""
    swift_code = f"""
import Cocoa
import Vision

let imageURL = URL(fileURLWithPath: "{image_path}")
guard let nsImage = NSImage(contentsOf: imageURL),
      let cgImage = nsImage.cgImage(forProposedRect: nil, context: nil, hints: nil) else {{
    exit(1)
}}
let request = VNRecognizeTextRequest {{ request, error in
    guard let observations = request.results as? [VNRecognizedTextObservation] else {{ return }}
    let strings = observations.compactMap {{ $0.topCandidates(1).first?.string }}
    print(strings.joined(separator: "\\n"))
}}
request.recognitionLevel = .accurate
let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
try? handler.perform([request])
"""
    try:
        res = subprocess.run(["swift", "-e", swift_code], capture_output=True, text=True, timeout=10)
        return res.stdout.strip()
    except Exception as e:
        print(f"[!] Vision OCR error on {image_path}: {e}", file=sys.stderr)
        return ""

def ingest_document_to_datastore(token: str, doc_id: str, title: str, content: str) -> bool:
    """Ingest inline document to Discovery Engine gemini-search-ds."""
    url = (
        f"https://discoveryengine.googleapis.com/v1/projects/{PROJECT_ID}"
        f"/locations/global/collections/default_collection/dataStores/{DATA_STORE_ID}"
        f"/branches/default_branch/documents?documentId={doc_id}"
    )
    headers = {
        "Authorization": f"Bearer {token}",
        "X-Goog-User-Project": PROJECT_ID,
        "Content-Type": "application/json",
    }
    payload = {
        "jsonData": json.dumps({
            "title": title,
            "content": content
        })
    }
    try:
        r = requests.post(url, headers=headers, json=payload, timeout=15)
        return r.status_code == 200
    except Exception as e:
        print(f"[!] Ingest error for {doc_id}: {e}", file=sys.stderr)
        return False

def query_answer_engine(token: str, query_text: str, preamble: str, retries: int = 3) -> dict:
    """Query Discovery Engine default_search:answer with 429 backoff retry."""
    url = (
        f"https://discoveryengine.googleapis.com/v1/projects/{PROJECT_ID}"
        f"/locations/global/collections/default_collection/engines/{ENGINE_ID}"
        f"/servingConfigs/{SERVING_CONFIG}:answer"
    )
    headers = {
        "Authorization": f"Bearer {token}",
        "X-Goog-User-Project": PROJECT_ID,
        "Content-Type": "application/json",
    }
    payload = {
        "query": {"text": query_text},
        "answerGenerationSpec": {
            "ignoreLowRelevantContent": False,
            "promptSpec": {"preamble": preamble},
            "includeCitations": False
        }
    }
    for attempt in range(retries):
        try:
            r = requests.post(url, headers=headers, json=payload, timeout=30)
            if r.status_code == 429:
                print(f"[Rate limit, waiting 7s...] ", end="", flush=True)
                time.sleep(7)
                continue
            if r.status_code != 200:
                return {"error": f"HTTP {r.status_code}: {r.text}"}
            data = r.json()
            ans_text = data.get("answer", {}).get("answerText", "")
            return {"raw_text": ans_text, "response": data}
        except Exception as e:
            if attempt < retries - 1:
                time.sleep(3)
                continue
            return {"error": str(e)}
    return {"error": "Rate limit exceeded after retries"}

def parse_json_from_llm(raw_text: str) -> dict:
    """Extract JSON object from markdown codeblock or raw response."""
    cleaned = raw_text.strip()
    if cleaned.startswith("```"):
        cleaned = re.sub(r"^```[a-zA-Z]*\n?", "", cleaned)
        cleaned = re.sub(r"\n?```$", "", cleaned).strip()
    m = re.search(r"(\{.*\})", cleaned, re.DOTALL)
    if m:
        try:
            return json.loads(m.group(1))
        except Exception:
            pass
    return {"raw_answer": raw_text}

def solve_batch(batch_dir: str, answers_output_dir: str = None) -> dict:
    batch_path = Path(batch_dir)
    batch_name = batch_path.name
    
    if not answers_output_dir:
        answers_output_dir = str(BASE_DIR / "answers")
    out_dir = Path(answers_output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    
    print(f"\n=======================================================")
    print(f"   BLUE AI SOLVER (Discovery Engine / GenAI Builder)")
    print(f"=======================================================")
    print(f"[*] Batch Folder : {batch_path}")
    print(f"[*] Project ID   : {PROJECT_ID}")
    print(f"[*] Engine ID    : {ENGINE_ID}")
    print(f"[*] SKU / Credit : Trial Credit for GenAI App Builder (Rp 0 Card)")
    print(f"-------------------------------------------------------")
    
    files = sorted(list(batch_path.glob("*_soal.*")))
    if not files:
        files = sorted([f for f in batch_path.iterdir() if f.suffix.lower() in [".txt", ".jpg", ".png"]])
        
    print(f"[*] Found {len(files)} question files in batch.")
    
    token = get_auth_token()
    questions_data = []
    
    for f in files:
        m = re.match(r"^(\d+)", f.name)
        q_num = int(m.group(1)) if m else len(questions_data) + 1
        
        content_text = ""
        if f.suffix.lower() == ".txt":
            with open(f, "r", encoding="utf-8", errors="replace") as tf:
                content_text = tf.read().strip()
        elif f.suffix.lower() in [".jpg", ".png", ".jpeg"]:
            ocr_text = ocr_image_apple_vision(str(f))
            content_text = f"[SOAL {q_num:02d} - DARI GAMBAR]\n{ocr_text}"
            
        questions_data.append({
            "num": q_num,
            "filename": f.name,
            "path": str(f),
            "content": content_text
        })
        
    print(f"[*] Ingesting {len(questions_data)} questions into Discovery Engine dataStore '{DATA_STORE_ID}'...")
    for q in questions_data:
        doc_id = f"{batch_name}-q{q['num']:02d}"
        title = f"Soal Ujian {q['num']:02d}"
        ingest_document_to_datastore(token, doc_id, title, q["content"])
        
    print(f"[✓] Ingestion complete. Allowing 6s for Discovery Engine fast indexing...")
    time.sleep(6)
    
    answers_result = {
        "batch_id": batch_name,
        "total_questions": len(questions_data),
        "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
        "engine": ENGINE_ID,
        "answers": []
    }
    
    preamble = (
        "You are an expert academic exam solver. Analyze the provided exam question and choices carefully. "
        "Choose the single best answer (A, B, C, D, or E) or state matching evaluations. "
        "Output ONLY valid JSON with keys: 'nomor' (integer), 'jawaban' (string, e.g. 'A', 'B', etc.), and 'alasan' (concise 1-2 sentences explanation in Indonesian)."
    )
    
    for q in questions_data:
        q_num = q["num"]
        prompt = (
            f"Berdasarkan dokumen Soal Ujian {q_num:02d}, apa pilihan jawaban yang paling tepat di antara A, B, C, D, atau E atau evaluasi pernyataan? "
            f"Format jawaban HANYA JSON: {{\"nomor\": {q_num}, \"jawaban\": \"X\", \"alasan\": \"...\"}}"
        )
        print(f"  [>] Solving Soal #{q_num:02d}... ", end="", flush=True)
        res = query_answer_engine(token, prompt, preamble)
        
        parsed = {}
        if "raw_text" in res and res["raw_text"]:
            parsed = parse_json_from_llm(res["raw_text"])
            if "jawaban" in parsed:
                print(f"[✓] Jawaban: {parsed.get('jawaban')} | {parsed.get('alasan', '')[:50]}...")
            else:
                print(f"[?] {res['raw_text'][:60]}...")
        else:
            print(f"[!] Error / Skipped: {res.get('error', 'No answerText')}")
            parsed = {"nomor": q_num, "jawaban": "-", "alasan": "Tidak dapat diproses oleh AI"}
            
        answers_result["answers"].append({
            "nomor": q_num,
            "jawaban": parsed.get("jawaban", "-"),
            "alasan": parsed.get("alasan", parsed.get("raw_answer", "")),
            "file": q["filename"]
        })
        time.sleep(2.5)
        
    out_file = out_dir / f"{batch_name}_answers.json"
    with open(out_file, "w", encoding="utf-8") as jf:
        json.dump(answers_result, jf, indent=2, ensure_ascii=False)
        
    print(f"\n=======================================================")
    print(f"                   BATCH SOLVING COMPLETE              ")
    print(f"=======================================================")
    print(f"[+] Saved to: {out_file}")
    print(f"-------------------------------------------------------")
    print(f"{'No':<4} | {'Jawaban':<8} | {'Alasan / Ringkasan':<50}")
    print(f"-----+----------+---------------------------------------------------")
    for ans in answers_result["answers"]:
        alasan_trunc = (ans["alasan"][:47] + "...") if len(ans["alasan"]) > 47 else ans["alasan"]
        print(f"{ans['nomor']:<4} | {ans['jawaban']:<8} | {alasan_trunc:<50}")
    print(f"=======================================================\n")
    
    return answers_result

if __name__ == "__main__":
    if len(sys.argv) > 1:
        b_dir = sys.argv[1]
    else:
        b_dir = str(Path(__file__).resolve().parent / "batches" / "sample_batch")
    solve_batch(b_dir)
