#!/usr/bin/env python3
"""Option A matrix probe. Usage: probe2.py MODEL KEYIDX LOW|DEFAULT
ONE tiny text call per invocation. Never prints keys."""
import json, os, sys, time, urllib.request, urllib.error
from pathlib import Path

ENV_PATH = Path(__file__).resolve().parent.parent / ".env"
API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"

model, keyidx, mode = sys.argv[1], int(sys.argv[2]) - 1, sys.argv[3]
keys = []
with open(ENV_PATH) as f:
    for line in f:
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            _, v = line.split("=", 1)
            v = v.strip().strip('"').strip("'")
            if v and "here" not in v:
                keys.append(v)
print(f"[*] {model} key#{keyidx+1} thinking={mode} (1 tiny call)")
gen = {"responseMimeType": "application/json", "maxOutputTokens": 16384}
if mode == "LOW":
    gen["thinkingConfig"] = {"thinkingLevel": "LOW"}
payload = {
    "contents": [{"parts": [{"text":
        'Reply ONLY raw JSON. Q: 2+2? A.3 B.4 C.5 Format: {"01":{"number":1,"option":"B","answer":"B. 4","reason":"x"}}'}]}],
    "generationConfig": gen,
}
url = f"{API_BASE}/{model}:generateContent?key={keys[keyidx]}"
req = urllib.request.Request(url, data=json.dumps(payload).encode(),
                             headers={"Content-Type": "application/json"})
t0 = time.time()
try:
    with urllib.request.urlopen(req, timeout=90) as resp:
        el = time.time() - t0
        body = json.loads(resp.read().decode())
        txt = body.get("candidates", [{}])[0].get("content", {}).get("parts", [{}])[0].get("text", "")
        print(f"[OK] HTTP {resp.status} in {el:.1f}s -> {txt[:120]}")
except urllib.error.HTTPError as e:
    print(f"[HTTP-{e.code}] in {time.time()-t0:.1f}s: {e.read().decode(errors='replace')[:300]}")
except Exception as e:
    print(f"[ERR] in {time.time()-t0:.1f}s: {e}")
