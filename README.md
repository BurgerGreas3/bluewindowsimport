# Blue (Windows Edition)

> **Zero-Network-Adapter CBT Assistance & Multimodal AI Solver System**

Blue is an end-to-end, strictly offline exam pipeline ported to **Windows** for operating under locked-down Computer-Based Test (CBT) environments. To prevent detection by kiosk browsers inspecting active network interfaces (`GetAdaptersAddresses`), Blue bypasses the external TCP/IP internet stack on the PC entirely, communicating directly over low-level **Windows Winsock Bluetooth RFCOMM (`AF_BTH`)** to an Android companion phone. The Android companion app solves questions over cellular data (4G/5G) via **Google Gemini 3.5 Flash Lite** and beams answers back to the Windows PC.

---

## 📖 User Manual
For complete operational instructions, hotkeys, and troubleshooting, please read:
👉 **[User Guide & Manual](guide.md)**

---

## 🛠️ Prerequisites (Windows)

1. **Python 3.10+**: Install Python and ensure `python` and `pip` are added to your system `PATH`.
   ```powershell
   pip install beautifulsoup4 lxml pillow requests python-dotenv
   ```
2. **Java Development Kit (JDK 17)**: Required for building the Android companion APK.
   ```powershell
   winget install --id EclipseAdoptium.Temurin.17.JDK -e --source winget
   ```
3. **Android SDK Command-Line Tools**: Installed at `%LOCALAPPDATA%\Android\Sdk` with `platforms;android-34` and `build-tools;34.0.0`.

---

## 🚀 Quick Start (PowerShell)

### 1. Configure Environment & Device Settings
Copy the example configuration files in PowerShell:
```powershell
Copy-Item .env.example .env
Copy-Item config.json.example config.json
```
* **Edit `.env`**: Add your active Google AI Studio Gemini keys (`GEMINI_KEY_1=AQ....`). To prevent `HTTP 429` rate limits on 20-question batches, use **3 to 5 keys created in separate Google Cloud projects** (delete any unused placeholder lines completely).
* **Edit `config.json`**: Set `target_phone_mac` to your Android phone's Bluetooth MAC address (found on your phone under **Settings > About Phone > Status > Bluetooth address**). Ensure your phone is paired with your Windows PC under **Settings > Bluetooth & devices**.

### 2. Build & Install the Android Companion App
Navigate to the `android` directory and run the PowerShell build script:
```powershell
Set-Location .\android
powershell -ExecutionPolicy Bypass -File .\build.ps1
adb install -r blue.apk
Set-Location ..
```
*(Alternatively, transfer `android\blue.apk` to your phone manually via USB/file sharing and tap to install).*

Open **Blue** on your Android phone, enable Bluetooth, tap **Start Service**, and set the app's battery usage to **Unrestricted** in Android Settings so it is not suspended when the screen turns off.

### 3. Verify Bluetooth RFCOMM Connection
Test the low-level Bluetooth socket link from your PC before starting an exam:
```powershell
python bluebridge_send.py --ping
```
Wait for `[OK] Blue Phone Service ONLINE`.

### 4. Run the Continuous Watcher
Launch the Windows supervisor and overlay HUD:
```powershell
.\blue.bat
```
Press **`Ctrl+S`** in Chrome to save an exam webpage into your `Downloads` folder, or drop any saved `.html` / `.mhtml` exam file (along with its `*_files` asset folder) into `raw_batches\`. Blue will automatically sort, slice, beam over Bluetooth, solve, and display the answers in the bottom-left slide-out HUD.

---

## 🛡️ Key Features
* **Strict Option A (Winsock RFCOMM Only)**: Zero PC internet required during exams, no virtual network adapters, invisible to kiosk network audits.
* **Headless DOM & Asset Slicer**: Automatically parses questions, multiple-choice options, audio files, and companion images on Windows.
* **Native Windows Slide-Out HUD (`blue_notch.py`)**: Lightweight Tkinter topmost overlay tucked into the bottom-left edge of the screen, synced in real time with the Chrome tracker extension (`http://127.0.0.1:20261`).
* **Multi-Key AI Solver**: Rotates across multiple Gemini API keys (`AQ.` / `AIzaSy`) across independent GCP projects with `HTTP 429` rate-limit circuit breakers and regex JSON recovery.
* **On-Phone Transparency**: Live batch history, expandable question/answer reasoning inspection, and storage deletion controls directly on the phone.
* **Incremental Live Disk Streaming**: In-flight answers are immediately saved to `answers\<batch_id>_live.json` and `latest_answers.json` to prevent data loss if disconnected.
