#!/bin/bash
# 2-hour no-cable test. Mirrors EXACTLY what the main app does on the wire:
#   - idle: one fast --ping every 60s (same binary, same flags as idle_pinger)
#   - at t=60 and t=110: one chatbot question via --chat (same path as \ key)
# Nothing else runs: no daemon needed, no ADB, lock/wake the phone by hand.
# Usage: ./tests/two_hour.sh
BLUE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
MAC="${TARGET_PHONE_MAC:-$(python3 -c 'import json, os; p = os.path.join("'"$BLUE_DIR"'", "config.json"); print(json.load(open(p)).get("target_phone_mac", "")) if os.path.exists(p) else print("")' 2>/dev/null)}"
[ -z "$MAC" ] && MAC="00-00-00-00-00-00"
SEND="$BLUE_DIR/bluebridge_send"
TOTAL_MIN=120
PING_EVERY=60
CHAT_AT="60 110"
LOG="$BLUE_DIR/twohour_$(date +%Y%m%d_%H%M%S).log"
exec > >(tee "$LOG") 2>&1

say() { echo "[$(date +%H:%M:%S)] $*"; }
P_OK=0; P_FAIL=0; C_OK=0; C_FAIL=0

say "=== PREFLIGHT (bluetooth only) ==="
[ -x "$SEND" ] || { say "FATAL: bluebridge_send missing"; exit 1; }
OR_KEY=$(grep ^OPENROUTER_KEY "$BLUE_DIR/.env" 2>/dev/null | cut -d= -f2- | tr -d '"'"'" | xargs)
CB_KEY=$(grep ^CEREBRAS_KEY "$BLUE_DIR/.env" 2>/dev/null | cut -d= -f2- | tr -d '"'"'" | xargs)
[ -n "$OR_KEY" ] && [ -n "$CB_KEY" ] || { say "FATAL: chat keys missing in .env"; exit 1; }
"$SEND" --ping --device "$MAC" 2>&1 | tail -n 1 | grep -q ONLINE \
    || { say "FATAL: phone not reachable (open blue, Start Service, pair Bluetooth)"; exit 1; }
say "phone ONLINE. Lock the screen BY HAND now (90s grace, then 120min start)..."
sleep 90

do_chat() {
    local t=$1 mark=$2
    rm -f "$BLUE_DIR/answers/chat_latest.json"
    if "$SEND" --chat "Reply with exactly: $mark" --or-key "$OR_KEY" --cb-key "$CB_KEY" \
            --device "$MAC" 2>&1 | tail -n 1 | grep -q "Saved to"; then
        if grep -q "$mark" "$BLUE_DIR/answers/chat_latest.json" 2>/dev/null; then
            say "t=${t}min chat: OK ($mark back)"; C_OK=$((C_OK+1))
        else
            say "t=${t}min chat: ANSWER WRONG"; C_FAIL=$((C_FAIL+1))
        fi
    else
        say "t=${t}min chat: FAIL (no answer)"; C_FAIL=$((C_FAIL+1))
    fi
}

t=0
say "--- t=0min start ---"
if "$SEND" --ping --device "$MAC" 2>&1 | tail -n 1 | grep -q ONLINE; then P_OK=$((P_OK+1)); else say "t=0 ping: FAIL"; P_FAIL=$((P_FAIL+1)); fi
while [ "$t" -lt "$TOTAL_MIN" ]; do
    sleep "$PING_EVERY"
    t=$((t+1))
    if echo " $CHAT_AT " | grep -q " $t "; then
        do_chat "$t" "H$t"
    else
        if "$SEND" --ping --device "$MAC" 2>&1 | tail -n 1 | grep -q ONLINE; then
            P_OK=$((P_OK+1))
            [ $((t % 10)) -eq 0 ] && say "t=${t}min ping: ONLINE"
        else
            say "t=${t}min ping: FAIL"; P_FAIL=$((P_FAIL+1))
        fi
    fi
done

say "=== REPORT ==="
say "pings: $P_OK ok / $P_FAIL failed (of $((P_OK+P_FAIL)))"
say "chats: $C_OK ok / $C_FAIL failed (markers H60 H110)"
say "wake the phone by hand — test over"
say "full log: $LOG"
