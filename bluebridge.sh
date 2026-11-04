#!/usr/bin/env bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DAEMON_BIN="$DIR/bluebridge_daemon"
PID_FILE="/tmp/bluebridge_daemon.pid"

start() {
    if pgrep -f "$DAEMON_BIN" > /dev/null; then
        echo "[*] BlueBridge daemon is already running."
    else
        echo "[*] Starting BlueBridge daemon..."
        nohup "$DAEMON_BIN" > /tmp/bluebridge.log 2>&1 &
        echo $! > "$PID_FILE"
        sleep 2
    fi

    echo "[*] Verifying connection..."
    if curl --socks5-hostname 127.0.0.1:1080 -s -m 5 https://icanhazip.com > /tmp/bb_ip.txt; then
        IP=$(cat /tmp/bb_ip.txt)
        echo "[+] CONNECTED! Cellular IP: $IP"
    else
        echo "[-] SOCKS5 proxy starting up, please check phone connection."
    fi
}

enable_system_proxy() {
    echo "[*] Enabling macOS system SOCKS proxy (Wi-Fi -> 127.0.0.1:1080)..."
    networksetup -setsocksfirewallproxy Wi-Fi 127.0.0.1 1080
    networksetup -setsocksfirewallproxystate Wi-Fi on
    echo "[+] System proxy ENABLED. All Mac browsers and apps are now using Bluetooth internet."
}

disable_system_proxy() {
    echo "[*] Disabling macOS system SOCKS proxy..."
    networksetup -setsocksfirewallproxystate Wi-Fi off
    echo "[+] System proxy DISABLED."
}

stop() {
    echo "[*] Stopping BlueBridge..."
    disable_system_proxy
    pkill -f "$DAEMON_BIN" 2>/dev/null
    rm -f "$PID_FILE"
    echo "[+] Stopped."
}

status() {
    if pgrep -f "$DAEMON_BIN" > /dev/null; then
        echo "[+] Daemon is RUNNING."
        PROXY_STATE=$(networksetup -getsocksfirewallproxy Wi-Fi | grep "Enabled: Yes")
        if [ -n "$PROXY_STATE" ]; then
            echo "[+] System Proxy: ENABLED"
        else
            echo "[-] System Proxy: DISABLED (SOCKS5 is available on 127.0.0.1:1080)"
        fi
        echo "[*] Testing internet via Bluetooth proxy..."
        IP=$(curl --socks5-hostname 127.0.0.1:1080 -s -m 5 https://icanhazip.com 2>/dev/null)
        if [ -n "$IP" ]; then
            echo "[+] Live Internet Confirmed! IP: $IP"
        else
            echo "[-] Proxy not responding."
        fi
    else
        echo "[-] Daemon is NOT running."
    fi
}

case "$1" in
    start)
        start
        ;;
    proxy-on)
        enable_system_proxy
        ;;
    proxy-off)
        disable_system_proxy
        ;;
    stop)
        stop
        ;;
    status)
        status
        ;;
    *)
        echo "Usage: $0 {start|stop|status|proxy-on|proxy-off}"
        exit 1
        ;;
esac
