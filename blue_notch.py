#!/usr/bin/env python3
import os
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

class WindowsNotchHUD:
    def __init__(self):
        self.root = tk.Tk()
        self.root.overrideredirect(True)
        self.root.attributes("-topmost", True)
        self.root.attributes("-alpha", 0.0)
        self.root.configure(bg="#101010")

        self.screen_w = self.root.winfo_screenwidth()
        self.screen_h = self.root.winfo_screenheight()
        self.width = 440
        self.height = 36
        self.x = 12
        self.y = self.screen_h - 86
        self.root.geometry(f"{self.width}x{self.height}+{self.x}+{self.y}")

        self.label = tk.Label(
            self.root,
            text="Q01: (waiting)",
            fg="#B0B0B0",
            bg="#101010",
            font=("Consolas", 10, "bold"),
            anchor="w",
            padx=10
        )
        self.label.pack(fill="both", expand=True)

        self.root.update_idletasks()
        self._apply_click_through()
        self.poll()

    def _apply_click_through(self):
        try:
            hwnd = ctypes.windll.user32.GetParent(self.root.winfo_id())
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
                if val.isdigit():
                    return val
        except Exception:
            pass
        return "1"

    def get_answer_for_q(self, q_str: str) -> str:
        latest = ANSWERS_DIR / "latest_answers.json"
        if not latest.exists():
            candidates = sorted(ANSWERS_DIR.glob("*_answers.json"), key=lambda p: p.stat().st_mtime, reverse=True)
            if not candidates:
                candidates = sorted(ANSWERS_DIR.glob("*_live.json"), key=lambda p: p.stat().st_mtime, reverse=True)
            if candidates:
                latest = candidates[0]
            else:
                return "(no answers yet)"
        try:
            data = json.loads(latest.read_text(encoding="utf-8"))
            if isinstance(data.get("answers"), list):
                for item in data["answers"]:
                    if str(item.get("nomor") or item.get("num")) == q_str:
                        return f"{item.get('jawaban') or item.get('answer', '-')} | {item.get('alasan') or item.get('reason', '')}"
            q_obj = data.get(q_str) or data.get(f"{int(q_str):02d}")
            if isinstance(q_obj, dict):
                ans = q_obj.get("answer") or q_obj.get("option") or "-"
                rsn = q_obj.get("reason") or q_obj.get("alasan") or ""
                return f"{ans}  {rsn}".strip()
        except Exception:
            pass
        return "(solving...)"

    def poll(self):
        try:
            if PAUSED_FILE.exists():
                self.root.attributes("-alpha", 0.0)
            else:
                mx = self.root.winfo_pointerx()
                my = self.root.winfo_pointery()
                q_str = self.get_active_q()
                ans_text = self.get_answer_for_q(q_str)
                self.label.config(text=f"#{int(q_str):02d}  {ans_text[:65]}")
                if mx <= 18 or (mx <= 380 and my >= self.screen_h - 110):
                    self.root.attributes("-alpha", 0.88)
                else:
                    self.root.attributes("-alpha", 0.0)
        except Exception:
            pass
        self.root.after(120, self.poll)

    def run(self):
        self.root.mainloop()

if __name__ == "__main__":
    WindowsNotchHUD().run()
