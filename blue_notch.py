#!/usr/bin/env python3
import os
import re
import sys
import json
import ctypes
import tempfile
import tkinter as tk
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
ANSWERS_DIR = BASE_DIR / "answers"
TMP_DIR = Path(tempfile.gettempdir())

ACTIVE_Q_FILE = TMP_DIR / "blue_active_q.txt"
PAUSED_FILE = TMP_DIR / "blue_paused"
SCROLL_CMD_FILE = TMP_DIR / "blue_chat_scroll"

LATEST_ANS_FILE = ANSWERS_DIR / "latest_answers.json"
ALPHA_ANS_FILE = ANSWERS_DIR / "alpha_latest_answers.json"
CHAT_FILE = ANSWERS_DIR / "chat_latest.json"
CHAT_HIST_FILE = ANSWERS_DIR / "chat_history.json"


def strip_latex(s: str) -> str:
    if not s:
        return ""
    t = str(s)
    for token in ("$$", "$", "\\(", "\\)", "\\[", "\\]", "**"):
        t = t.replace(token, "")
    for _ in range(3):
        n = re.sub(r"\\frac\s*\{([^{}]*)\}\s*\{([^{}]*)\}", r"(\1)/(\2)", t)
        if n == t:
            break
        t = n
    t = re.sub(r"\\sqrt\s*\{([^{}]*)\}", r"√(\1)", t)
    for _ in range(3):
        n = re.sub(r"\\[a-zA-Z]+\s*\{([^{}]*)\}", r"\1", t)
        if n == t:
            break
        t = n
    sym = {
        "\\pm": "±", "\\times": "×", "\\cdot": "·", "\\div": "÷",
        "\\leq": "≤", "\\geq": "≥", "\\neq": "≠", "\\approx": "≈",
        "\\infty": "∞", "\\to": "→", "\\pi": "π", "\\theta": "θ",
        "\\alpha": "α", "\\beta": "β", "\\sqrt": "√",
        "^2": "²", "^3": "³"
    }
    for k, v in sym.items():
        t = t.replace(k, v)
    t = re.sub(r"\\([a-zA-Z]+)", r"\1", t)
    return t.strip()


def parse_verdict(s: str) -> str:
    l = s.lower()
    if "benar" in l and ("salah" in l or "false" in l):
        return "?"
    if "tidak benar" in l or "salah" in l:
        return "S"
    if "false" in l or "faux" in l:
        return "F"
    if "true" in l:
        return "T"
    if "vrai" in l:
        return "V"
    if "benar" in l:
        return "B"
    if "tidak" in l or "undetermined" in l or "?" in l:
        return "?"
    t = s.strip()
    return t[0].upper() if t else "?"


def format_answer_obj(q_obj: dict) -> tuple[str, str]:
    """Returns (compact_answer, detail_reason) for True/False, MCMA, and standard questions."""
    if not isinstance(q_obj, dict):
        return "-", ""

    if q_obj.get("_status") == "solving":
        return "…", "solving…"

    ans_text = ""
    reason_text = ""

    # 1. Check "statements" array (True/False matrix)
    stmts = q_obj.get("statements")
    if isinstance(stmts, list) and stmts:
        tf_list = []
        for i, stmt in enumerate(stmts):
            if isinstance(stmt, dict):
                idx = stmt.get("index", i + 1)
                raw_status = str(stmt.get("status", "?")).strip()
                v = parse_verdict(raw_status)
                tf_list.append(f"{idx}.{v}")
        ans_text = " ".join(tf_list)

    # 2. Check "answer" or "jawaban" field
    if not ans_text:
        a = q_obj.get("answer") if "answer" in q_obj else q_obj.get("jawaban")
        if isinstance(a, list):
            str_arr = [str(x).strip() for x in a if str(x).strip()]
            is_stmt = any(
                any(k in s.lower() for k in ("benar", "salah", "true", "false", "vrai", "faux"))
                for s in str_arr
            )
            if is_stmt:
                tf_list = []
                for i, s in enumerate(str_arr):
                    m = re.match(r"^(\d+)", s)
                    idx_str = m.group(1) if m else str(i + 1)
                    # Strip leading "1. " before checking verdict
                    body_part = re.sub(r"^\d+[\.\)\s-]*", "", s)
                    v = parse_verdict(body_part)
                    tf_list.append(f"{idx_str}.{v}")
                ans_text = " ".join(tf_list)
                reason_text = " | ".join(str_arr)
            else:
                # MCMA (Pilihan Ganda Kompleks): extract option letters or short items
                letters = []
                for s in str_arr:
                    m = re.match(r"^([A-Za-z])[\.\)\s-]", s)
                    if m:
                        letters.append(m.group(1).upper())
                    elif len(s) <= 2:
                        letters.append(s.upper())
                    else:
                        letters.append(s[0].upper())
                ans_text = ", ".join(letters)
                reason_text = " | ".join(str_arr)
        elif isinstance(a, str):
            if "\n" in a:
                rows = [r.strip() for r in a.split("\n") if r.strip()]
                is_stmt = any(
                    any(k in r.lower() for k in ("benar", "salah", "true", "false", "vrai", "faux"))
                    for r in rows
                )
                if is_stmt:
                    tf_list = []
                    for i, r in enumerate(rows):
                        m = re.match(r"^(\d+)", r)
                        idx_str = m.group(1) if m else str(i + 1)
                        body_part = re.sub(r"^\d+[\.\)\s-]*", "", r)
                        tf_list.append(f"{idx_str}.{parse_verdict(body_part)}")
                    ans_text = " ".join(tf_list)
                else:
                    ans_text = ", ".join(rows)
            else:
                ans_text = a.strip()

    if not ans_text:
        opt = q_obj.get("option")
        opt_ans = q_obj.get("option_answer")
        if opt and opt_ans:
            ans_text = f"{opt}. {opt_ans}"
        elif opt:
            ans_text = str(opt)

    if not reason_text:
        reason_text = (
            q_obj.get("reason")
            or q_obj.get("alasan")
            or q_obj.get("penjelasan")
            or q_obj.get("reasoning")
            or q_obj.get("analysis")
            or ""
        )

    if q_obj.get("confidence") == "auto-filled":
        ans_text = f"? {ans_text}"

    return strip_latex(ans_text or "-"), strip_latex(str(reason_text))


class WindowsNotchHUD:
    def __init__(self):
        self.root = tk.Tk()
        self.root.overrideredirect(True)
        self.root.attributes("-topmost", True)
        self.root.attributes("-alpha", 0.0)
        self.root.configure(bg="#101010")

        self.screen_w = self.root.winfo_screenwidth()
        self.screen_h = self.root.winfo_screenheight()

        # Left / Center HUD (Main & Alpha answers) - wider & 2 lines tall
        self.hud_w = 560
        self.hud_h = 48
        self.hud_x_left = 10
        self.hud_x_mid = (self.screen_w - self.hud_w) // 2
        self.hud_y = self.screen_h - 92
        self.root.geometry(f"{self.hud_w}x{self.hud_h}+{self.hud_x_left}+{self.hud_y}")

        self.label = tk.Label(
            self.root,
            text="SOAL #01\n(waiting)",
            fg="#B8B8B8",
            bg="#101010",
            font=("Consolas", 9, "bold"),
            anchor="w",
            justify="left",
            padx=10,
            pady=4,
            wraplength=self.hud_w - 20
        )
        self.label.pack(fill="both", expand=True)

        # Right HUD (Chat & Presets 1-8)
        self.chat_win = tk.Toplevel(self.root)
        self.chat_win.overrideredirect(True)
        self.chat_win.attributes("-topmost", True)
        self.chat_win.attributes("-alpha", 0.0)
        self.chat_win.configure(bg="#101010")

        self.chat_w = 680
        self.chat_h = 84
        self.chat_x = self.screen_w - self.chat_w - 14
        self.chat_y = self.screen_h - 128
        self.chat_win.geometry(f"{self.chat_w}x{self.chat_h}+{self.chat_x}+{self.chat_y}")

        self.chat_text = tk.Text(
            self.chat_win,
            fg="#B8B8B8",
            bg="#101010",
            font=("Consolas", 9),
            wrap="word",
            bd=0,
            padx=10,
            pady=6,
            highlightthickness=0
        )
        self.chat_text.pack(fill="both", expand=True)
        self.chat_text.insert("1.0", "(chat / preset ready)")
        self.chat_text.config(state="disabled")

        self.last_chat_mtime = 0.0
        self.chat_force_show_until = 0.0
        self.mode = "main"  # "main" or "alpha"

        self.root.update_idletasks()
        self.chat_win.update_idletasks()
        self._apply_click_through(self.root)
        self._apply_click_through(self.chat_win)
        self.poll()

    def _apply_click_through(self, win):
        try:
            hwnd = ctypes.windll.user32.GetParent(win.winfo_id())
            GWL_EXSTYLE = -20
            WS_EX_LAYERED = 0x00080000
            WS_EX_TRANSPARENT = 0x00000020
            WS_EX_TOOLWINDOW = 0x00000080
            style = ctypes.windll.user32.GetWindowLongW(hwnd, GWL_EXSTYLE)
            ctypes.windll.user32.SetWindowLongW(
                hwnd, GWL_EXSTYLE, style | WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_TOOLWINDOW
            )
        except Exception:
            pass

    def get_active_q(self) -> str:
        try:
            if ACTIVE_Q_FILE.exists():
                val = ACTIVE_Q_FILE.read_text(encoding="utf-8").strip()
                if val.isdigit() and int(val) > 0:
                    return val
        except Exception:
            pass
        return "1"

    def _load_json_dict(self, path: Path) -> dict:
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            if isinstance(data, dict):
                if isinstance(data.get("answers"), list):
                    out = {}
                    for item in data["answers"]:
                        if isinstance(item, dict):
                            no = item.get("nomor") or item.get("number") or item.get("num") or item.get("nomor_tertera")
                            if no is not None:
                                out[f"{int(no):02d}"] = item
                                out[str(int(no))] = item
                    return out
                elif isinstance(data.get("answers"), dict):
                    return data["answers"]
                return data
        except Exception:
            pass
        return {}

    def get_answer_display(self, q_str: str, mode: str) -> str:
        q_num = int(q_str)
        q_key_pad = f"{q_num:02d}"

        if mode == "alpha":
            if not ALPHA_ANS_FILE.exists():
                return f"ALPHA #{q_key_pad}: (Press 9 to run DeepSeek Vision)"
            data = self._load_json_dict(ALPHA_ANS_FILE)
            q_obj = data.get(q_key_pad) or data.get(str(q_num))
            if not q_obj:
                return f"ALPHA #{q_key_pad}: (solving / no answer yet)"
            ans, rsn = format_answer_obj(q_obj)
            return f"ALPHA #{q_key_pad}  {ans}\n{rsn[:140]}"

        # Main mode
        target = LATEST_ANS_FILE
        if not target.exists():
            candidates = sorted(
                [p for p in ANSWERS_DIR.glob("*_answers.json") if not p.name.startswith("alpha_")],
                key=lambda p: p.stat().st_mtime,
                reverse=True
            )
            if not candidates:
                candidates = sorted(ANSWERS_DIR.glob("*_live.json"), key=lambda p: p.stat().st_mtime, reverse=True)
            if candidates:
                target = candidates[0]
            else:
                return f"SOAL #{q_key_pad}: (no answers yet — Ctrl+S exam page)"

        data = self._load_json_dict(target)
        if data.get("_status") == "solving":
            return f"SOAL #{q_key_pad}: … (solving batch…)"

        q_obj = data.get(q_key_pad) or data.get(str(q_num))
        if not q_obj:
            return f"SOAL #{q_key_pad}: (solving...)"

        ans, rsn = format_answer_obj(q_obj)
        if rsn:
            return f"#{q_key_pad}  {ans}\n{rsn[:140]}"
        return f"#{q_key_pad}  {ans}"

    def check_chat_updates(self):
        import time
        try:
            if CHAT_FILE.exists():
                mtime = CHAT_FILE.stat().st_mtime
                if mtime > self.last_chat_mtime:
                    self.last_chat_mtime = mtime
                    raw = json.loads(CHAT_FILE.read_text(encoding="utf-8"))
                    ans = strip_latex(raw.get("answer", "-"))
                    self.chat_text.config(state="normal")
                    self.chat_text.delete("1.0", "end")
                    self.chat_text.insert("1.0", ans)
                    self.chat_text.yview_moveto(0.0)
                    self.chat_text.config(state="disabled")
                    # Pop chat HUD briefly for 4s when a preset or chat answer arrives
                    self.chat_force_show_until = time.time() + 4.0
        except Exception:
            pass

        # Check scroll commands (= / -)
        try:
            if SCROLL_CMD_FILE.exists():
                cmd = SCROLL_CMD_FILE.read_text(encoding="utf-8").strip()
                SCROLL_CMD_FILE.unlink(missing_ok=True)
                self.chat_force_show_until = time.time() + 4.0
                if cmd == "down":
                    self.chat_text.yview_scroll(2, "units")
                elif cmd == "up":
                    self.chat_text.yview_scroll(-2, "units")
        except Exception:
            pass

    def poll(self):
        import time
        try:
            if PAUSED_FILE.exists():
                self.root.attributes("-alpha", 0.0)
                self.chat_win.attributes("-alpha", 0.0)
            else:
                self.check_chat_updates()

                mx = self.root.winfo_pointerx()
                my = self.root.winfo_pointery()
                in_bottom_y = (my >= self.screen_h - 110)

                # 1. Bottom-Left trigger (Main Answers)
                at_left = (mx <= 18) or (in_bottom_y and mx <= 380)
                # 2. Bottom-Center trigger (Alpha Vision Answers - Key 9)
                at_mid = in_bottom_y and (abs(mx - (self.screen_w // 2)) <= 160)
                # 3. Bottom-Right trigger (Chat \ & Presets 1-8)
                at_right = (mx >= self.screen_w - 25) or (in_bottom_y and mx >= self.screen_w - 420)

                q_str = self.get_active_q()

                if at_mid:
                    if self.mode != "alpha":
                        self.mode = "alpha"
                        self.root.geometry(f"{self.hud_w}x{self.hud_h}+{self.hud_x_mid}+{self.hud_y}")
                    self.label.config(text=self.get_answer_display(q_str, "alpha"))
                    self.root.attributes("-alpha", 0.88)
                elif at_left:
                    if self.mode != "main":
                        self.mode = "main"
                        self.root.geometry(f"{self.hud_w}x{self.hud_h}+{self.hud_x_left}+{self.hud_y}")
                    self.label.config(text=self.get_answer_display(q_str, "main"))
                    self.root.attributes("-alpha", 0.88)
                else:
                    self.root.attributes("-alpha", 0.0)

                if at_right or time.time() < self.chat_force_show_until:
                    self.chat_win.attributes("-alpha", 0.88)
                else:
                    self.chat_win.attributes("-alpha", 0.0)
        except Exception:
            pass
        self.root.after(100, self.poll)

    def run(self):
        self.root.mainloop()


if __name__ == "__main__":
    WindowsNotchHUD().run()