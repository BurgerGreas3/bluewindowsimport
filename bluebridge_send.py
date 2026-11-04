#!/usr/bin/env python3
import os
import sys
import json
import time
import struct
import socket
import tempfile
from pathlib import Path
from datetime import datetime
from io import BytesIO

try:
    from PIL import Image
except ImportError:
    Image = None

MAGIC = 0x4242
CMD_BATCH_START = 0x10
CMD_ITEM_START  = 0x11
CMD_CHUNK_DATA  = 0x12
CMD_ITEM_END    = 0x13
CMD_BATCH_END   = 0x14
CMD_ACK         = 0x20
CMD_PING        = 0x21
CMD_ANSWERS     = 0x30
CMD_PROGRESS    = 0x31
CMD_ANS_BEGIN   = 0x32
CMD_ANS_CHUNK   = 0x33
CMD_ANS_END     = 0x34
CMD_FETCH       = 0x35
CMD_CHAT        = 0x40
CMD_CHAT_ANS    = 0x41
CMD_CANCEL      = 0x42
CMD_PAUSE       = 0x43
CMD_RESOLVE_ONE = 0x44

TYPE_TEXT  = 0x01
TYPE_IMAGE = 0x02
TYPE_AUDIO = 0x03

BASE_DIR = Path(os.environ.get("BLUE_DIR", Path(__file__).resolve().parent))
TMP_DIR = Path(tempfile.gettempdir())
LAST_GOOD_CH_PATH = TMP_DIR / "blue_last_ch"

def read_last_good_channel() -> int:
    try:
        ch = int(LAST_GOOD_CH_PATH.read_text(encoding="utf-8").strip())
        return ch if 1 <= ch <= 30 else 0
    except Exception:
        return 0

def write_last_good_channel(ch: int):
    try:
        LAST_GOOD_CH_PATH.write_text(str(ch), encoding="utf-8")
    except Exception:
        pass

def get_answers_dir() -> Path:
    env_dir = os.environ.get("BLUE_ANSWERS_DIR")
    if env_dir:
        return Path(env_dir)
    return BASE_DIR / "answers"

def optimize_image(data: bytes) -> bytes:
    if not Image or len(data) < 150 * 1024:
        return data
    try:
        img = Image.open(BytesIO(data)).convert("RGB")
        img.thumbnail((1400, 1400))
        out = BytesIO()
        img.save(out, format="JPEG", quality=75)
        return out.getvalue()
    except Exception:
        return data

def write_frame(sock: socket.socket, stream_id: int, cmd: int, payload: bytes = b""):
    hdr = struct.pack(">HHBH", MAGIC, stream_id & 0xFFFF, cmd & 0xFF, len(payload))
    sock.sendall(hdr + payload)

def read_frame(sock: socket.socket, timeout: float = 5.0):
    sock.settimeout(timeout)
    b1 = sock.recv(1)
    if not b1:
        return None
    b2 = sock.recv(1)
    if not b2:
        return None
    while not (b1[0] == 0x42 and b2[0] == 0x42):
        b1 = b2
        b2 = sock.recv(1)
        if not b2:
            return None
    rest = b""
    while len(rest) < 5:
        chunk = sock.recv(5 - len(rest))
        if not chunk:
            return None
        rest += chunk
    stream_id, cmd, plen = struct.unpack(">HBH", rest)
    payload = bytearray()
    while len(payload) < plen:
        chunk = sock.recv(plen - len(payload))
        if not chunk:
            break
        payload.extend(chunk)
    return stream_id, cmd, bytes(payload)

def wait_for_ack(sock: socket.socket, expected_id: int, timeout: float = 5.0) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            rem = max(0.1, deadline - time.time())
            frame = read_frame(sock, timeout=rem)
            if not frame:
                return False
            sid, cmd, payload = frame
            if cmd == CMD_ACK and sid == expected_id and len(payload) >= 1 and payload[0] == 0:
                return True
        except Exception:
            return False
    return False

def open_rfcomm(mac: str, channels: list):
    bt_mac = mac.replace("-", ":")
    for ch in channels:
        if ch <= 0:
            continue
        try:
            sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
            sock.settimeout(6.0)
            sock.connect((bt_mac, ch))
            return sock, ch
        except Exception:
            try:
                sock.close()
            except Exception:
                pass
    return None, 0

def main():
    args = sys.argv[1:]
    if not args:
        print("Usage: bluebridge_send [--batch-id ID] [--device MAC] [--channel CH] [--ping] [files...]")
        sys.exit(1)

    is_ping = "--ping" in args
    no_wait = "--no-wait" in args
    is_cancel = "--cancel-all" in args
    is_pause = "--pause" in args
    is_chat = "--chat" in args
    is_fetch = "--fetch-answers" in args
    is_resolve_one = "--resolve-one" in args

    target_mac = os.environ.get("TARGET_PHONE_MAC", "")
    if not target_mac:
        cfg_file = BASE_DIR / "config.json"
        if cfg_file.exists():
            try:
                target_mac = json.loads(cfg_file.read_text(encoding="utf-8")).get("target_phone_mac", "")
            except Exception:
                pass
    if not target_mac:
        target_mac = "00-00-00-00-00-00"

    batch_id = None
    channel_arg = 0
    chat_text = ""
    pause_val = "1"
    resolve_num = -1
    files = []

    i = 0
    while i < len(args):
        a = args[i]
        if a in ("--ping", "--no-compress", "--no-wait", "--cancel-all"):
            i += 1
        elif a == "--batch-id" and i + 1 < len(args):
            batch_id = args[i + 1]; i += 2
        elif a == "--device" and i + 1 < len(args):
            target_mac = args[i + 1]; i += 2
        elif a == "--channel" and i + 1 < len(args):
            channel_arg = int(args[i + 1]); i += 2
        elif a == "--retry" and i + 1 < len(args):
            i += 2
        elif a == "--fetch-answers" and i + 1 < len(args):
            batch_id = args[i + 1]; i += 2
        elif a == "--chat" and i + 1 < len(args):
            chat_text = args[i + 1]; i += 2
        elif a in ("--or-key", "--cb-key") and i + 1 < len(args):
            i += 2
        elif a == "--pause" and i + 1 < len(args):
            pause_val = args[i + 1]; i += 2
        elif a == "--resolve-one" and i + 1 < len(args):
            resolve_num = int(args[i + 1]); i += 2
        else:
            files.append(a); i += 1

    cached_ch = read_last_good_channel()
    ch_list = [channel_arg] if channel_arg > 0 else ([cached_ch, 25, 1, 2, 3, 4, 5] if cached_ch > 0 else [25, 1, 2, 3, 4, 5])

    if is_ping:
        sock, ch = open_rfcomm(target_mac, ch_list)
        if not sock:
            sys.exit(1)
        try:
            write_frame(sock, 0, CMD_PING, b"PING")
            if wait_for_ack(sock, 0, 3.5):
                write_last_good_channel(ch)
                print(f"[OK] Blue Phone Service ONLINE (CH {ch})")
                sys.exit(0)
        finally:
            sock.close()
        sys.exit(1)

    if not batch_id:
        batch_id = "batch_" + datetime.now().strftime("%Y%m%d_%H%M%S")

    items = []
    for fpath in files:
        p = Path(fpath)
        if not p.exists() or not p.is_file():
            continue
        raw = p.read_bytes()
        ext = p.suffix.lower().lstrip(".")
        itype = TYPE_TEXT
        fname = p.name
        if ext in ("png", "jpg", "jpeg", "webp"):
            itype = TYPE_IMAGE
            if len(raw) > 200 * 1024:
                raw = optimize_image(raw)
                fname = p.stem + ".jpg"
        elif ext in ("mp3", "m4a", "wav", "aac", "ogg"):
            itype = TYPE_AUDIO
        items.append((itype, fname, raw))

    sock, ch = open_rfcomm(target_mac, ch_list)
    if not sock:
        print(f"[-] Could not connect to phone {target_mac}")
        sys.exit(1)

    try:
        if is_cancel:
            write_frame(sock, 0, CMD_CANCEL, b"*")
            wait_for_ack(sock, 0, 4.0)
            sys.exit(0)
        if is_pause:
            write_frame(sock, 0, CMD_PAUSE, pause_val.encode("utf-8"))
            wait_for_ack(sock, 0, 4.0)
            sys.exit(0)
        if is_chat:
            req = {
                "q": chat_text,
                "or_key": os.environ.get("OPENROUTER_KEY", ""),
                "cb_key": os.environ.get("ONOMEO_KEY", "")
            }
            write_frame(sock, 0, CMD_CHAT, json.dumps(req).encode("utf-8"))
            wait_for_ack(sock, 0, 6.0)
            deadline = time.time() + 240.0
            while time.time() < deadline:
                fr = read_frame(sock, timeout=10.0)
                if not fr:
                    continue
                sid, cmd, payload = fr
                if cmd == CMD_CHAT_ANS:
                    ans_dir = get_answers_dir()
                    ans_dir.mkdir(parents=True, exist_ok=True)
                    chat_file = ans_dir / "chat_latest.json"
                    try:
                        obj = json.loads(payload.decode("utf-8"))
                    except Exception:
                        obj = {"question": chat_text, "answer": payload.decode("utf-8", errors="replace")}
                    obj["ts"] = time.time()
                    chat_file.write_text(json.dumps(obj, indent=2), encoding="utf-8")
                    write_frame(sock, 0, CMD_ACK, b"CHAT_OK")
                    sys.exit(0)
            sys.exit(1)

        if is_resolve_one:
            req = {"num": resolve_num, "batch_id": batch_id}
            write_frame(sock, 0, CMD_RESOLVE_ONE, json.dumps(req).encode("utf-8"))
            wait_for_ack(sock, 0, 6.0)
        elif is_fetch:
            write_frame(sock, 0, CMD_FETCH, batch_id.encode("utf-8"))
            wait_for_ack(sock, 0, 6.0)
        else:
            b_bytes = batch_id.encode("utf-8")
            start_payload = struct.pack(">HH", len(items), len(b_bytes)) + b_bytes
            write_frame(sock, 0, CMD_BATCH_START, start_payload)
            if not wait_for_ack(sock, 0, 6.0):
                print("[-] No BATCH_START ACK")
                sys.exit(1)
            write_last_good_channel(ch)

            for idx, (itype, fname, raw) in enumerate(items, start=1):
                n_bytes = fname.encode("utf-8")
                ihdr = struct.pack(">BIH", itype, len(raw), len(n_bytes)) + n_bytes
                write_frame(sock, idx, CMD_ITEM_START, ihdr)
                off = 0
                while off < len(raw):
                    chunk = raw[off:off + 850]
                    write_frame(sock, idx, CMD_CHUNK_DATA, chunk)
                    off += len(chunk)
                write_frame(sock, idx, CMD_ITEM_END, b"")
                if not wait_for_ack(sock, idx, 5.0):
                    print(f"[-] Item #{idx} not ACKed")
                    sys.exit(1)
                print(f"  [{idx:02d}/{len(items):02d}] {fname} -> Verified [OK]")

            write_frame(sock, 0, CMD_BATCH_END, b"")
            wait_for_ack(sock, 0, 5.0)

        if no_wait and not is_fetch and not is_resolve_one:
            sys.exit(0)

        ans_dir = get_answers_dir()
        ans_dir.mkdir(parents=True, exist_ok=True)
        live_answers = []
        accum = None
        received_answers = None
        deadline = time.time() + 600.0

        while time.time() < deadline and received_answers is None:
            fr = read_frame(sock, timeout=120.0)
            if not fr:
                continue
            sid, cmd, payload = fr
            if cmd == CMD_PROGRESS:
                try:
                    pjson = json.loads(payload.decode("utf-8"))
                    live_answers.append(pjson)
                    live_dict = {
                        "batch_id": batch_id,
                        "solved_count": len(live_answers),
                        "status": "solving_in_progress",
                        "answers": live_answers
                    }
                    (ans_dir / f"{batch_id}_live.json").write_text(json.dumps(live_dict, indent=2), encoding="utf-8")
                except Exception:
                    pass
            elif cmd == CMD_ANSWERS:
                received_answers = payload
            elif cmd == CMD_ANS_BEGIN:
                accum = bytearray()
            elif cmd == CMD_ANS_CHUNK:
                if accum is None:
                    accum = bytearray()
                accum.extend(payload)
            elif cmd == CMD_ANS_END:
                if accum is not None:
                    received_answers = bytes(accum)

        if received_answers:
            ans_file = ans_dir / f"{batch_id}_answers.json"
            ans_file.write_bytes(received_answers)
            live_file = ans_dir / f"{batch_id}_live.json"
            if live_file.exists():
                live_file.unlink()
            write_frame(sock, 0, CMD_ACK, b"ANSWERS_OK")
            print(f"[OK] Saved answers to {ans_file}")
            sys.exit(0)
        else:
            sys.exit(1)
    finally:
        sock.close()

if __name__ == "__main__":
    main()