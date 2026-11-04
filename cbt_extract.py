#!/usr/bin/env python3
import sys
import os
import re
import json
import shutil
import tempfile
from pathlib import Path
from datetime import datetime
from urllib.parse import unquote

try:
    from bs4 import BeautifulSoup
except ImportError:
    print("[!] Missing beautifulsoup4. Please install: pip install beautifulsoup4 lxml")
    sys.exit(1)

TMP_DIR = Path(tempfile.gettempdir())

def clean_formula_text(el) -> str:
    if el is None:
        return ""
    clone = BeautifulSoup(str(el), "html.parser")
    for sup in clone.find_all("sup"):
        sup.replace_with("^" + sup.get_text())
    for sub in clone.find_all("sub"):
        sub.replace_with("_" + sub.get_text())
    for mjx in clone.find_all("mjx-container"):
        alt = mjx.get("aria-label") or mjx.get("alt") or ""
        if alt:
            mjx.replace_with(f" {alt} ")
    return clone.get_text(" ", strip=True)

def main():
    args = sys.argv[1:]
    if not args:
        print("Usage: cbt_extract <path-to-html> [output-batch-dir] [--force-imgs 1,2,3]")
        sys.exit(1)

    html_path = Path(args[0]).resolve()
    batch_dir = None
    forced_set = set()

    i = 1
    while i < len(args):
        a = args[i]
        if a == "--force-imgs" and i + 1 < len(args):
            for part in args[i + 1].split(","):
                part = part.strip()
                if part.isdigit():
                    forced_set.add(int(part))
            i += 2
        elif not a.startswith("--") and batch_dir is None:
            batch_dir = Path(a).resolve()
            i += 1
        else:
            i += 1

    forced_json = TMP_DIR / "blue_forced_imgs.json"
    if forced_json.exists():
        try:
            raw = json.loads(forced_json.read_text(encoding="utf-8"))
            arr = raw if isinstance(raw, list) else raw.get("forced", [])
            for el in arr:
                if str(el).strip().isdigit():
                    forced_set.add(int(str(el).strip()))
        except Exception:
            pass

    if batch_dir is None:
        ts = datetime.now().strftime("%Y%m%d_%H%M%S")
        batch_dir = Path(__file__).resolve().parent / "batches" / f"batch_{ts}"
    batch_dir.mkdir(parents=True, exist_ok=True)

    soup = BeautifulSoup(html_path.read_text(encoding="utf-8", errors="ignore"), "html.parser")
    containers = soup.select('.question-container, .soal-container, [id^="soal-"], .box-soal, .card-soal, .soal_konten')
    print(f"[*] Total questions detected in DOM: {len(containers)}")

    for idx, container in enumerate(containers):
        real_num = idx + 1
        data_urut = container.get("data-urut")
        if data_urut and str(data_urut).isdigit():
            real_num = int(data_urut)

        file_num = f"{real_num:02d}"
        badge_el = container.select_one(".badge")
        badge = badge_el.get_text(strip=True) if badge_el else "Pilihan Ganda"

        q_text_el = container.select_one(".question-text") or container
        q_text = clean_formula_text(q_text_el)

        options_text = []
        matching_rows = container.select(".matching-table tr")
        if matching_rows:
            for r_idx, row in enumerate(matching_rows, start=1):
                td = row.find("td")
                stmt = clean_formula_text(td)
                if stmt:
                    options_text.append(f"  [{r_idx}] {stmt} -> (Pilihan: Benar / Salah)")
        else:
            for lbl in container.select("label.option-circle"):
                span = lbl.find("span")
                letter = span.get_text(strip=True) if span else ""
                clone = BeautifulSoup(str(lbl), "html.parser")
                for tag in clone.find_all(["span", "input"]):
                    tag.decompose()
                txt = clean_formula_text(clone)
                if letter and txt:
                    options_text.append(f"{letter}. {txt}")
                elif txt:
                    options_text.append(txt)

        lines = [
            f"[SOAL {file_num}]",
            f"Tipe: {badge}",
            "Pertanyaan:",
            q_text,
            ""
        ]
        if options_text:
            lines.append("Pilihan Jawaban:")
            lines.extend(options_text)

        base_content = "\n".join(lines)
        txt_file = batch_dir / f"{file_num}_soal.txt"
        txt_file.write_text(base_content, encoding="utf-8")

        imgs = [img for img in container.find_all("img") if not (img.get("src", "").startswith("data:image/svg"))]
        if imgs or real_num in forced_set:
            for img in imgs:
                src = unquote(img.get("src") or img.get("data-src") or "")
                if src and not src.startswith(("http://", "https://", "data:")):
                    img_path = (html_path.parent / src).resolve()
                    if img_path.exists():
                        shutil.copy2(img_path, batch_dir / f"{file_num}_soal{img_path.suffix}")
                        break

        print(f"  [{idx+1:02d}/{len(containers):02d}] -> Saved {txt_file.name}")

if __name__ == "__main__":
    main()
