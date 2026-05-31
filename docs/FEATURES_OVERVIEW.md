# axl SDK — Features Overview

**Version 26.2.1**  
Android SDK for AXL RFID POS hardware integration

---

## What is axl SDK?

axl SDK is an Android library that lets your app communicate with AXL RFID POS devices over a USB cable. It takes care of all the low-level communication details — USB connection, protocol messaging, error handling, and reconnection — so your team can focus on building the POS application, not the hardware layer.

---

## Key Features

### 1. USB Device Connection

Connect to an AXL RFID device with a single method call. The SDK automatically:
- Detects the device when plugged in
- Requests USB permission from the user (once)
- Performs a connection handshake with the device
- Reports the device type and name to your app
- Reconnects automatically if the cable is briefly disconnected

Your app receives a simple `onConnected()` or `onDisconnected()` callback — no USB driver code required.

---

### 2. RFID Tag Scanning

Start, pause, and stop RFID tag scanning at any time.

- **Start** — The device begins scanning and fires `onTagDetected(epc, antenna)` for every tag it reads. Tags can be detected many times per second.
- **Pause** — Scanning is suspended but your collected tag list is preserved. Useful when the cashier needs to review items before checkout.
- **Stop** — Scanning ends and the full list of scanned EPC tags is sent to the device in one message.

The SDK also tells you which **antenna port** detected each tag — helpful for multi-antenna setups (up to 4 ports).

---

### 3. Checkout Transaction

When the customer is ready to pay, call `checkoutCompleted(transactionId, tags)`. The SDK sends the transaction number and the full EPC list to the device in a single command. The device responds with a confirmation (`onCheckoutConfirmed`) when it has processed the transaction.

---

### 4. Barcode Scanning

Start and stop an attached barcode scanner. Each scanned barcode triggers `onBarcodeTagDetected(data)` with the raw barcode string. Useful for scanning loyalty cards, shipping labels, or product codes alongside RFID.

---

### 5. NFC Reading

Start and stop NFC card reading. Each detected NFC tag triggers `onNfcTagDetected(uid, antenna)` with the tag's UID. Works alongside RFID on the same device.

---

### 6. Device Configuration

Push hardware settings to the device at any time — no restart required. Configurable settings include:

| Setting | Description |
|---|---|
| Region | RFID frequency region (e.g. Indonesia = "ID") |
| Protocol | Tag protocol (default: GEN2) |
| Read Power | Antenna transmit power in mdBm |
| Antennas | Enable/disable individual antenna ports (1–4) |
| Network | LAN or Wi-Fi (with SSID, security type, password) |
| Frequency | Hop frequency, hop time, read on/off timing |

The device acknowledges config updates with `onConfigUpdated()`.

Two config formats are supported:
- **`sendDeviceConfig()`** — Lean format for AE03A001 (AXL FLAT) devices
- **`updateDeviceConfig()`** — Full format for other AXL devices

---

### 7. Device Health Monitoring

Call `getHealthInfo()` to request a real-time health report from the device. The response (`onHealthInfoReceived`) contains:

- CPU usage (%)
- Memory usage (% and MB)
- Device temperature (where available)

Useful for diagnostics and support.

---

### 8. Device Log Streaming

When device-side debug logging is active, the firmware streams log entries over USB in real time. Your app receives each entry via `onDeviceLogReceived(level, message, timestamp)` — level can be DEBUG, INFO, WARN, or ERROR. Useful for troubleshooting device-side behaviour without physical access to the hardware.

---

### 9. SDK Logging & Diagnostics

The SDK has a built-in logging system with five severity levels (VERBOSE → ERROR). You can:

- Enable verbose debug logging at runtime without restarting the app
- Set a minimum log level to filter noise
- Register a live callback to receive log entries as they happen
- Call `getDiagnosticReport()` to get a snapshot of the recent log buffer — useful for attaching to support tickets

---

### 10. Error Reporting

All errors surface through a single `onError(message)` callback with a human-readable description and an error code:

| Code | Situation |
|---|---|
| E001 | No device connected |
| E002 | USB permission was denied |
| E003 | Device did not respond in time |
| E004 | Device sent a malformed message |
| E005 | Unknown command received |
| E006 | SDK used before initialization |
| E007 | Missing required field in a message |

---

## Supported Hardware

| Device | Use Case |
|---|---|
| **AXL FLAT** (AE03A001) | Flat countertop POS reader |
| **AXL BIN** | Bin / container scanning |
| **AXL GATE** | Portal / gate scanning |

---

## Platform Requirements

| Requirement | Value |
|---|---|
| Android version | 8.0 and above (API 26+) |
| Language | Java 11 |
| Connection | USB OTG (USB-A to USB-C or Micro-USB) |
| Distribution | AAR file, module reference, or Maven |

---

## Integration at a Glance

The full integration with a POS app involves four steps:

1. **Add the SDK** to your Android project (AAR file or Maven dependency)
2. **Initialize** the SDK once when the app starts
3. **Implement `SdkListener`** to receive events (connected, tag detected, checkout confirmed, errors)
4. **Call commands** (`connect`, `startReading`, `stopReading`, `checkoutCompleted`, etc.) in response to user actions

No background services, no complex setup, no native code required.

---

## What the SDK Does Not Do

- It does not manage your item database or EPC-to-product mapping — that stays in your app.
- It does not handle payment processing — `checkoutCompleted` only signals the device; payment is your app's responsibility.
- It does not manage Wi-Fi on the Android device itself — it configures Wi-Fi credentials on the connected RFID hardware.

---

*For integration details and full API reference, see [README.md](README.md).*
