#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HTML_FILE="${1:-}"

if [ -z "$HTML_FILE" ] || [ ! -f "$HTML_FILE" ]; then
    echo "Usage: ./send_exam_batch.sh <exam.html> [--extract-only]"
    echo "[-] Error: HTML file not found: $HTML_FILE"
    exit 1
fi

TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
BATCH_DIR="$SCRIPT_DIR/batches/batch_${TIMESTAMP}"
mkdir -p "$BATCH_DIR"

echo "======================================================="
echo "        Exam Batch Processor & Bluetooth Sender        "
echo "======================================================="
echo "[*] HTML Source  : $HTML_FILE"
echo "[*] Batch Folder : $BATCH_DIR"
echo ""

# 1. Run Smart Hybrid Extractor
"$SCRIPT_DIR/cbt_extract" "$HTML_FILE" "$BATCH_DIR"
if [ -f "$SCRIPT_DIR/keys.json" ]; then
    cp -f "$SCRIPT_DIR/keys.json" "$BATCH_DIR/keys.json" 2>/dev/null || true
fi

echo ""
echo "[*] Ready to transmit to phone via BlueBridge:"
ls -lh "$BATCH_DIR"
echo ""

# 2. Transmit to phone over Bluetooth RFCOMM (auto channel probe, batch-id = dir name)
if [ "$2" == "--extract-only" ]; then
    echo "[✓] Extraction complete. Skipping Bluetooth transfer (--extract-only)."
else
    BATCH_ID="$(basename "$BATCH_DIR")"
    shopt -s nullglob
    SEND_FILES=("$BATCH_DIR"/*)
    shopt -u nullglob
    if [ "${#SEND_FILES[@]}" -eq 0 ]; then
        echo "[-] Extraction produced 0 files, nothing to send."
        exit 1
    fi
    echo "[*] Transmitting batch $BATCH_ID (${#SEND_FILES[@]} files) to Android over Bluetooth..."
    "$SCRIPT_DIR/bluebridge_send" --batch-id "$BATCH_ID" "${SEND_FILES[@]}"
fi
