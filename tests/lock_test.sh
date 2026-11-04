#!/bin/bash
# Screen-off survival test. Everything is printed AND saved to locktest_<ts>.log
# Usage:
#   ./tests/lock_test.sh 20     (~25 min: ping/2min + HTTPS chats at 10,20)
#   ./tests/lock_test.sh 180    (~3h10m: ping/10min + HTTPS chats at 30,60,120,180)
#   ./tests/lock_test.sh check  (preflight only, touches nothing)
#   ./tests/lock_test.sh --no-adb 30  (no cable: ping+chat over Bluetooth only,
#                               lock/wake the phone by hand)
BLUE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB:-$(which adb 2>/dev/null || echo "$HOME/Documents/moova/.tools/android-sdk/platform-tools/adb")}"
MAC="${TARGET_PHONE_MAC:-$(python3 -c 'import json, os; p = os.path.join("'"$BLUE_DIR"'", "config.json"); print(json.load(open(p)).get("target_phone_mac", "")) if os.path.exists(p) else print("")' 2>/dev/null)}"
[ -z "$MAC" ] && MAC="00-00-00-00-00-00"
SEND="$BLUE_DIR/bluebridge_send"
PKG="com.bluebridge.app"
NOADB=0
if [ "${1:-}" = "--no-adb" ]; then NOADB=1; shift; fi
ARG="${1:-20}"
LOG="$BLUE_DIR/locktest_$(date +%Y%m%d_%H%M%S).log"
exec > >(tee "$LOG") 2>&1

say() { echo "[$(date +%H:%M:%S)] $*"; }

# --- preflight ---------------------------------------------------------------
say "=== PREFLIGHT ==="
[ -x "$SEND" ] || { say "FATAL: bluebridge_send missing (run build_mac.sh)"; exit 1; }
OR_KEY=$(grep ^OPENROUTER_KEY "$BLUE_DIR/.env" 2>/dev/null | cut -d= -f2- | tr -d '"'"'" | xargs)
CB_KEY=$(grep ^CEREBRAS_KEY "$BLUE_DIR/.env" 2>/dev/null | cut -d= -f2- | tr -d '"'"'" | xargs)
if [ "$NOADB" = "1" ]; then
    say "mode: BLUETOOTH ONLY (no adb, no screen/logcat — lock the phone by hand)"
    "$SEND" --ping --device "$MAC" 2>&1 | tail -n 1 | grep -q ONLINE \
        || { say "FATAL: phone not reachable over Bluetooth (open blue, Start Service)"; exit 1; }
    say "phone reachable over Bluetooth"
else
[ -x "$ADB" ] || { say "FATAL: adb missing"; exit 1; }
"$ADB" devices | grep -q "device$" || { say "FATAL: phone not on adb"; exit 1; }
PID0=$("$ADB" shell pidof $PKG 2>/dev/null | tr -d '\r')
[ -n "$PID0" ] || { say "FATAL: $PKG not running (open blue app, Start Service)"; exit 1; }
UIDV=$("$ADB" shell cmd package list packages -U 2>/dev/null | grep bluebridge | grep -oE 'uid:[0-9]+' | cut -d: -f2)
BAT0=$("$ADB" shell dumpsys battery 2>/dev/null | grep level | grep -oE '[0-9]+')
SCR0=$("$ADB" shell dumpsys display 2>/dev/null | grep mScreenState | head -n 1)
say "PID=$PID0 UID=$UIDV battery=${BAT0}% screen: $SCR0"
fi
[ -n "$OR_KEY" ] && [ -n "$CB_KEY" ] && say "chat keys: present (HTTPS checks ON)" || say "chat keys: MISSING (HTTPS checks skipped)"
[ "$ARG" = "check" ] && { say "preflight OK, nothing touched"; exit 0; }

MIN="$ARG"
if [ "$MIN" -le 30 ]; then PING_EVERY=120; CHAT_AT="10 20"; [ "$MIN" -ge 30 ] && CHAT_AT="$CHAT_AT 30"; else PING_EVERY=600; CHAT_AT="30 60 120 180"; fi
say "=== TEST: ${MIN}min locked (ping/${PING_EVERY}s, HTTPS chats at: $CHAT_AT) ==="

screen_is() { "$ADB" shell dumpsys display 2>/dev/null | grep -q "mScreenState=$1"; }

# --- lock --------------------------------------------------------------------
if [ "$NOADB" = "1" ]; then
    say "lock the phone screen BY HAND now (5s grace)..."; sleep 5
    FREEZE0=0
else
if screen_is ON; then "$ADB" shell input keyevent 26; say "screen locked by script"; else say "screen already OFF"; fi
sleep 3
screen_is OFF || say "WARN: screen not OFF after lock (continuing anyway)"

FREEZE0=$("$ADB" logcat -d 2>/dev/null | grep "$UIDV" | grep -cE "FROZEN_TRANS|freeze operation")
fi

checkpoint() {
    local t=$1
    say "--- checkpoint t=${t}min ---"
    if [ "$NOADB" != "1" ]; then
    screen_is OFF && say "screen: OFF" || say "screen: NOT-OFF (touched?)"
    local hb=$("$ADB" logcat -d 2>/dev/null | grep BridgeService | grep -E "\b$PID0\b" | grep heartbeat | tail -n 1)
    [ -n "$hb" ] && say "heartbeat: $hb" || say "heartbeat: NONE for PID $PID0 (!!)"
    fi
    if "$SEND" --ping --device "$MAC" 2>&1 | tail -n 1 | grep -q ONLINE; then say "ping: ONLINE"; else say "ping: FAIL"; fi
    if echo " $CHAT_AT " | grep -q " $t "; then
        if [ -n "$OR_KEY" ] && [ -n "$CB_KEY" ]; then
            rm -f "$BLUE_DIR/answers/chat_latest.json"
            if "$SEND" --chat "Reply with exactly: T7" --or-key "$OR_KEY" --cb-key "$CB_KEY" --device "$MAC" 2>&1 | tail -n 1 | grep -q "Saved to"; then
                grep -q "T7" "$BLUE_DIR/answers/chat_latest.json" 2>/dev/null && say "https(chat): OK (T7 back)" || say "https(chat): ANSWER WRONG"
            else
                say "https(chat): FAIL (no answer while locked)"
            fi
        else
            say "https(chat): skipped (no keys)"
        fi
    fi
}

# --- loop --------------------------------------------------------------------
t=0
checkpoint 0
while [ "$t" -lt "$MIN" ]; do
    sleep "$PING_EVERY"
    t=$((t + PING_EVERY / 60))
    [ "$t" -gt "$MIN" ] && t=$MIN
    if [ "$NOADB" != "1" ]; then
    PIDNOW=$("$ADB" shell pidof $PKG 2>/dev/null | tr -d '\r')
    [ "$PIDNOW" != "$PID0" ] && say "NOTE: PID changed $PID0 -> $PIDNOW (service restarted mid-test)"
    fi
    checkpoint "$t"
done

# --- unlock + report ---------------------------------------------------------
if [ "$NOADB" = "1" ]; then
say "=== REPORT (bluetooth only) ==="
say "wake the phone by hand — test over"
else
screen_is OFF && { "$ADB" shell input keyevent 82; sleep 2; say "screen woken by script"; }
FREEZE1=$("$ADB" logcat -d 2>/dev/null | grep "$UIDV" | grep -cE "FROZEN_TRANS|freeze operation")
BAT1=$("$ADB" shell dumpsys battery 2>/dev/null | grep level | grep -oE '[0-9]+')
PID1=$("$ADB" shell pidof $PKG 2>/dev/null | tr -d '\r')
say "=== REPORT ==="
say "freeze events during test: $((FREEZE1 - FREEZE0))"
say "battery: ${BAT0}% -> ${BAT1}%"
say "PID: $PID0 -> $PID1"
fi
if "$SEND" --ping --device "$MAC" 2>&1 | tail -n 1 | grep -q ONLINE; then say "final ping: ONLINE"; else say "final ping: FAIL"; fi
say "full log: $LOG"
