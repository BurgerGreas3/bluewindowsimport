#!/usr/bin/env python3
"""
BlueBridge macOS Client Daemon
Connects to the BlueBridge Android App over Bluetooth RFCOMM,
and exposes a local SOCKS5 proxy on 127.0.0.1:1080.
All web traffic sent through this proxy routes through Android's cellular/Wi-Fi connection.
"""

import sys
import os
import struct
import socket
import threading
import time
from Foundation import NSBundle, NSRunLoop, NSDate, NSDefaultRunLoopMode
from objc import lookUpClass, super

NSBundle.bundleWithPath_("/System/Library/Frameworks/IOBluetooth.framework").load()
IOBluetoothDevice = lookUpClass("IOBluetoothDevice")
IOBluetoothSDPUUID = lookUpClass("IOBluetoothSDPUUID")
NSObject = lookUpClass("NSObject")

MAGIC = 0x4242
CMD_CONNECT = 0x01
CMD_CONNECTED = 0x02
CMD_DATA = 0x03
CMD_CLOSE = 0x04
CMD_ERROR = 0x05

SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"

class RFCOMMBridgeDelegate(NSObject):
    def init(self):
        self = super(RFCOMMBridgeDelegate, self).init()
        if self:
            self.channel = None
            self.connected = False
            self.rx_buffer = bytearray()
            self.streams = {}  # stream_id -> socket
            self.stream_events = {}  # stream_id -> threading.Event (connected)
            self.lock = threading.Lock()
        return self

    def rfcommChannelOpenComplete_status_(self, channel, status):
        print(f"[RFCOMM] Channel open complete: status={status}")
        if status == 0:
            self.channel = channel
            self.connected = True
            print("[RFCOMM] Connected to BlueBridge on Android!")
        else:
            self.connected = False

    def rfcommChannelClosed_(self, channel):
        print("[RFCOMM] Channel closed by remote peer.")
        self.connected = False
        with self.lock:
            for s in list(self.streams.values()):
                try:
                    s.close()
                except Exception:
                    pass
            self.streams.clear()

    def rfcommChannelData_data_length_(self, channel, data_ptr, length):
        raw_bytes = bytes(data_ptr[:length])
        self.rx_buffer.extend(raw_bytes)
        self.process_rx_frames()

    def process_rx_frames(self):
        while len(self.rx_buffer) >= 7:
            # Check magic
            magic, stream_id, cmd, length = struct.unpack(">HHBH", self.rx_buffer[:7])
            if magic != MAGIC:
                # Discard 1 byte to re-sync
                self.rx_buffer.pop(0)
                continue

            if len(self.rx_buffer) < 7 + length:
                # Wait for complete frame
                break

            payload = bytes(self.rx_buffer[7:7 + length])
            del self.rx_buffer[:7 + length]

            self.handle_frame(stream_id, cmd, payload)

    def handle_frame(self, stream_id, cmd, payload):
        if cmd == CMD_CONNECTED:
            # Android succeeded in connecting to target host:port
            ev = self.stream_events.get(stream_id)
            if ev:
                ev.set()
        elif cmd == CMD_DATA:
            sock = self.streams.get(stream_id)
            if sock:
                try:
                    sock.sendall(payload)
                except Exception:
                    self.close_stream(stream_id)
        elif cmd == CMD_CLOSE or cmd == CMD_ERROR:
            self.close_stream(stream_id)

    def send_frame(self, stream_id, cmd, payload=b""):
        if not self.channel or not self.connected:
            return False
        frame = struct.pack(">HHBH", MAGIC, stream_id, cmd, len(payload)) + payload
        self.channel.writeSync_length_(frame, len(frame))
        return True

    def close_stream(self, stream_id):
        with self.lock:
            sock = self.streams.pop(stream_id, None)
            self.stream_events.pop(stream_id, None)
            if sock:
                try:
                    sock.close()
                except Exception:
                    pass
        self.send_frame(stream_id, CMD_CLOSE)


class SOCKS5Server:
    def __init__(self, bridge_delegate, host="127.0.0.1", port=1080):
        self.bridge = bridge_delegate
        self.host = host
        self.port = port
        self.next_stream_id = 1
        self.lock = threading.Lock()

    def start(self):
        server_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server_sock.bind((self.host, self.port))
        server_sock.listen(50)
        print(f"\n=======================================================")
        print(f"[*] SOCKS5 Proxy Server active on {self.host}:{self.port}")
        print(f"[*] To use it, set your browser or terminal proxy:")
        print(f"    export all_proxy=socks5h://{self.host}:{self.port}")
        print(f"    curl --socks5-hostname {self.host}:{self.port} https://icanhazip.com")
        print(f"=======================================================\n")

        while True:
            client_sock, _ = server_sock.accept()
            threading.Thread(target=self.handle_client, args=(client_sock,), daemon=True).start()

    @staticmethod
    def recv_exact(sock, n):
        buf = bytearray()
        while len(buf) < n:
            chunk = sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionResetError("Socket closed prematurely")
            buf.extend(chunk)
        return bytes(buf)

    def handle_client(self, client_sock):
        try:
            # 1. SOCKS5 Handshake
            ver, nmethods = struct.unpack("!BB", self.recv_exact(client_sock, 2))
            if ver != 5:
                client_sock.close()
                return
            methods = self.recv_exact(client_sock, nmethods)
            client_sock.sendall(b"\x05\x00")  # NO AUTH

            # 2. SOCKS5 Request
            ver, cmd, rsv, atyp = struct.unpack("!BBBB", self.recv_exact(client_sock, 4))
            if cmd != 1:  # CONNECT only
                client_sock.sendall(b"\x05\x07\x00\x01\x00\x00\x00\x00\x00\x00")
                client_sock.close()
                return

            if atyp == 1:  # IPv4
                dest_addr = socket.inet_ntoa(self.recv_exact(client_sock, 4))
            elif atyp == 3:  # Domain
                domain_len = self.recv_exact(client_sock, 1)[0]
                dest_addr = self.recv_exact(client_sock, domain_len).decode("utf-8")
            elif atyp == 4:  # IPv6
                dest_addr = socket.inet_ntop(socket.AF_INET6, self.recv_exact(client_sock, 16))
            else:
                client_sock.close()
                return

            dest_port = struct.unpack("!H", self.recv_exact(client_sock, 2))[0]

            with self.lock:
                start_id = self.next_stream_id
                while True:
                    candidate = self.next_stream_id
                    self.next_stream_id = (self.next_stream_id + 1) & 0x7FFF
                    if self.next_stream_id == 0:
                        self.next_stream_id = 1
                    if candidate not in self.streams and candidate != 0:
                        stream_id = candidate
                        break
                    if self.next_stream_id == start_id:
                        raise RuntimeError("All 32k stream IDs exhausted")

            # Prepare connect frame
            addr_bytes = dest_addr.encode("utf-8")
            payload = struct.pack(">HH", dest_port, len(addr_bytes)) + addr_bytes

            connect_ev = threading.Event()
            with self.bridge.lock:
                self.bridge.streams[stream_id] = client_sock
                self.bridge.stream_events[stream_id] = connect_ev

            # Send connect request to Android over Bluetooth
            if not self.bridge.send_frame(stream_id, CMD_CONNECT, payload):
                client_sock.sendall(b"\x05\x04\x00\x01\x00\x00\x00\x00\x00\x00")
                self.bridge.close_stream(stream_id)
                return

            # Wait for Android response
            if not connect_ev.wait(timeout=10.0):
                client_sock.sendall(b"\x05\x04\x00\x01\x00\x00\x00\x00\x00\x00")
                self.bridge.close_stream(stream_id)
                return

            # Reply SUCCESS to local SOCKS client
            client_sock.sendall(b"\x05\x00\x00\x01\x7f\x00\x00\x01\x04\x38")

            # Forward from local socket to Android
            while True:
                buf = client_sock.recv(8192)
                if not buf:
                    break
                if not self.bridge.send_frame(stream_id, CMD_DATA, buf):
                    break
        except Exception:
            pass
        finally:
            self.bridge.close_stream(stream_id)


def find_rfcomm_channel_id(device):
    print(f"[*] Querying SDP records on {device.name()}...")
    services = device.services()
    for s in services or []:
        name = str(s.getServiceName())
        # Check if BlueBridge or SerialPort
        rf_id = s.getRFCOMMChannelID_(None)
        if rf_id and rf_id[0] == 0:
            print(f" -> Found RFCOMM Service: '{name}' on Channel ID: {rf_id[1]}")
            return rf_id[1]
    return 1  # Default standard RFCOMM channel 1


def main():
    target_addr = os.environ.get("TARGET_PHONE_MAC")
    if not target_addr:
        cfg_file = Path(__file__).resolve().parent / "config.json"
        if cfg_file.exists():
            try:
                cfg = json.loads(cfg_file.read_text(encoding="utf-8"))
                target_addr = cfg.get("target_phone_mac")
            except Exception:
                pass
    if not target_addr:
        target_addr = "00-00-00-00-00-00"
    dev = IOBluetoothDevice.deviceWithAddressString_(target_addr)
    if not dev:
        print(f"[-] Bluetooth device with address {target_addr} not found.")
        sys.exit(1)

    print(f"[*] Target device: {dev.name()} [{dev.addressString()}], connected: {dev.isConnected()}")
    rfcomm_channel_id = find_rfcomm_channel_id(dev)

    delegate = RFCOMMBridgeDelegate.alloc().init()
    print(f"[*] Connecting to RFCOMM channel {rfcomm_channel_id} on {dev.name()}...")

    ret, channel = dev.openRFCOMMChannelSync_withChannelID_delegate_(None, rfcomm_channel_id, delegate)
    if ret != 0:
        print(f"[-] Failed to open RFCOMM channel: status = {ret} (0x{ret:08X})")
        print("[-] Make sure the BlueBridge app is running and 'Start Service' is pressed on your phone.")
        sys.exit(1)

    delegate.channel = channel
    delegate.connected = True
    print(f"[+] RFCOMM Channel opened successfully! Channel ID: {channel.getChannelID()}")

    # Start local SOCKS5 server in background thread
    socks_server = SOCKS5Server(delegate, "127.0.0.1", 1080)
    threading.Thread(target=socks_server.start, daemon=True).start()

    print("[+] Bridge active. Press Ctrl+C to exit.")
    try:
        while True:
            NSRunLoop.currentRunLoop().runMode_beforeDate_(NSDefaultRunLoopMode, NSDate.dateWithTimeIntervalSinceNow_(0.5))
    except KeyboardInterrupt:
        print("\n[*] Shutting down bridge.")
        if channel:
            channel.closeChannel()


if __name__ == "__main__":
    main()
