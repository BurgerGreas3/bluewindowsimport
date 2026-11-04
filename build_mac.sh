#!/usr/bin/env bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

echo "======================================================="
echo "       Building Blue Native Mac Binaries            "
echo "======================================================="

if ! xcode-select -p >/dev/null 2>&1; then
    echo "[-] Xcode Command Line Tools missing. Run: xcode-select --install"
    exit 1
fi
if ! command -v swiftc >/dev/null 2>&1; then
    echo "[-] swiftc not found. Install Xcode CLT first."
    exit 1
fi

echo "[1/4] Compiling bt_ctrl (Bluetooth controller)..."
clang -framework Foundation -framework IOBluetooth -O2 -o bt_ctrl bt_ctrl.m

echo "[2/4] Compiling bluebridge_send (RFCOMM transport)..."
clang -framework Foundation -framework IOBluetooth -framework AppKit -framework ImageIO -framework CoreGraphics -O2 -o bluebridge_send bluebridge_send.m

echo "[3/4] Compiling cbt_extract (Headless WebKit parser)..."
swiftc -O cbt_extract.swift -o cbt_extract

echo "[4/4] Compiling blue_notch (Native macOS slide-out overlay)..."
swiftc -O -framework Cocoa -framework WebKit blue_notch.swift -o blue_notch

echo "[5/5] Compiling cbt_screens (Full WebKit question screenshot exporter)..."
swiftc -O -framework Foundation -framework WebKit -framework AppKit export_all_screens.swift -o cbt_screens

echo "======================================================="
echo "[✓] All 5 native Mac binaries compiled successfully!"
echo "======================================================="

