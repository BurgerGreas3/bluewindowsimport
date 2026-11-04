# Blue

> **Zero-Network-Adapter CBT Assistance & Multimodal AI Solver System**

Blue is an end-to-end, strictly offline exam pipeline for macOS operating under locked-down Computer-Based Test (CBT) environments. To prevent detection by kiosk browsers inspecting network interfaces (`getifaddrs()`), Blue bypasses the TCP/IP stack entirely, communicating directly over low-level **Bluetooth RFCOMM (SPP)** to an Android companion phone. The Android companion app solves questions over cellular data (4G/5G) via **Google Gemini 3.5 Flash Lite** and beams answers back to the Mac.

---

## 📖 User Manual
For complete operational instructions, hotkeys, and troubleshooting, please read:
👉 **[User Guide & Manual](guide.md)**

---

## 🚀 Quick Start

### 1. Build Native Mac Binaries
```bash
./build_mac.sh
```

### 2. Configure Environment & Device Settings
Copy example config files:
```bash
cp .env.example .env
cp config.json.example config.json
# Edit .env with your API keys and config.json with your phone MAC
```

### 3. Build & Install Android Companion App
```bash
cd android
./build.sh
adb install -r blue.apk
```
Open **Blue** on your Android phone, enable Bluetooth, and ensure the service is running.

### 4. Run the Continuous Watcher
```bash
./blue
```
Drop any saved exam `.html` or `.mhtml` webpage into `raw_batches/`. Blue will automatically sort, slice, beam over Bluetooth, solve, and display the answer table.

---

## 🛡️ Key Features
* **Strict Option A (Serial RFCOMM Only)**: Zero Mac internet, no virtual adapters, invisible to kiosk network audits.
* **Headless WebKit DOM Slicer**: Automatically parses questions, choices, audio files, and takes headless ImageIO screenshots.
* **Multi-Key AI Solver**: Rotates across 5 Gemini API keys with 429 rate-limit circuit breakers and regex JSON recovery.
* **On-Phone Transparency**: Live batch history, expandable question/answer reasoning inspection, and storage deletion controls directly on phone.
* **Incremental Live Disk Streaming**: In-flight answers are immediately saved to `answers/<batch_id>_live.json` to prevent data loss if disconnected.
