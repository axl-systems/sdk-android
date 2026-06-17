# axl SDK — Features Overview

**Version 26.2.3**  
Android SDK for AXL hardware device integration over USB and Bluetooth LE

---

## What is axl SDK?

axl SDK is an Android library that lets your app communicate with AXL hardware devices over a **USB cable** or **Bluetooth LE**. It takes care of all the low-level communication details — transport connection, protocol messaging, error handling, and reconnection — so your team can focus on building the POS application, not the hardware layer.

---

## Key Features

### 1. USB Device Connection

Connect to an AXL RFID device with a single method call. The SDK automatically:
- Detects the device when plugged in
- Requests USB permission from the user (once)
- Performs a connection handshake with the device
- Reports the device type, SKU, and name to your app
- Returns the device's current configuration immediately on connect
- Reconnects automatically if the cable is briefly disconnected

Your app receives a simple `onConnected()` or `onDisconnected()` callback — no USB driver code required.

---

### 2. Bluetooth LE Connectivity — Configuration Only

Connect to an AXL device over Bluetooth LE to push configuration updates remotely — without a USB cable.

**Important:** BLE connectivity is designed for **configuration updates only**. When a USB tablet is actively connected to the device, the BLE-connected tablet automatically enters config-only mode. Reading (RFID, Barcode, NFC) and checkout are not available over BLE in this scenario.

**How it works:**
- Scan for nearby AXL devices using `startBleScan()`
- Already-paired devices appear instantly via `getBondedBleDevices()`
- Connect to a selected device with `connectBle(macAddress)`
- On connect, the SDK checks whether a USB host is already active on the device
- If a USB host is active → SDK fires `onUsbLocked()` — only `sendDeviceConfig()` is permitted
- When the USB tablet disconnects, the SDK detects this automatically and fires `onUsbUnlocked()` — full access restored

---

### 3. Device Configuration on Connect

On every successful connection the SDK fires `onDeviceConfigLoaded(JSONObject config)` immediately after `onConnected()` with the device's current hardware configuration.

This means:
- Your Settings dialog can be pre-populated with live device values automatically
- No separate "fetch config" command is needed
- Works the same over both USB and BLE

---

### 4. RFID Tag Scanning

Start, pause, and stop RFID tag scanning at any time.

- **Start** — The device begins scanning and fires `onTagDetected(epc, antenna)` for every tag it reads. Tags can be detected many times per second.
- **Pause** — Scanning is suspended but your collected tag list is preserved. Useful when the cashier needs to review items before checkout.
- **Stop** — Scanning ends and the full list of scanned EPC tags is sent to the device in one message.

The SDK also tells you which **antenna port** detected each tag — helpful for multi-antenna setups (up to 4 ports).

> **BLE restriction:** RFID scanning is blocked when `isUsbLockedByRemote()` is true.

---

### 5. Checkout Transaction — Auto-Batched

When the customer is ready to pay, call `checkoutCompleted(transactionId, tags)`. The SDK automatically splits the EPC list into batches and sends them sequentially — protecting the AXL device from memory pressure on large reads.

**Batching behaviour:**
- Default batch size: **15 EPCs per `checkout_complete` command**
- If total EPCs ≤ batch size → single command (no change in behaviour)
- If total EPCs > batch size → multiple commands sent sequentially, each waiting for device acknowledgement
- `onCheckoutConfirmed(txnId)` fires **once** after all batches complete — not per batch
- Configurable via `SdkConfig.Builder().checkoutBatchSize(n)` — set to `0` to disable batching

Example — 45 EPCs with default batch size 15:
```
Batch 1 → checkout_complete [EPC 1–15]   → ack ✓
Batch 2 → checkout_complete [EPC 16–30]  → ack ✓
Batch 3 → checkout_complete [EPC 31–45]  → ack ✓
→ onCheckoutConfirmed("#TX123") fires once
```

> **BLE restriction:** Checkout is blocked when `isUsbLockedByRemote()` is true.

---

### 6. Barcode Scanning

Start and stop an attached barcode scanner. Each scanned barcode triggers `onBarcodeTagDetected(data)` with the raw barcode string. Useful for scanning loyalty cards, shipping labels, or product codes alongside RFID.

> **BLE restriction:** Barcode scanning is blocked when `isUsbLockedByRemote()` is true.

---

### 7. NFC Reading

Start and stop NFC card reading. Each detected NFC tag triggers `onNfcTagDetected(uid, antenna)` with the tag's UID. Works alongside RFID on the same device.

> **BLE restriction:** NFC reading is blocked when `isUsbLockedByRemote()` is true.

---

### 8. Device Configuration

Push hardware settings to the device at any time — no restart required. **Available over both USB and BLE.** Configurable settings include:

| Setting | Description |
|---|---|
| Region | RFID frequency region (e.g. Indonesia = "ID", North America = "NA") |
| Protocol | Tag protocol (default: GEN2) |
| Read Power | Antenna transmit power in mdBm |
| Antennas | Enable/disable individual antenna ports (1–4) |
| Network | LAN or Wi-Fi (with SSID, security type, password) |
| Frequency | Hop frequency list, hop time, read on/off timing |

The device acknowledges config updates with `onConfigUpdated()`.

---

### 9. Device Health Monitoring

Call `getHealthInfo()` to request a real-time health report from the device. The response (`onHealthInfoReceived`) contains:

- Module temperature (°C)

Useful for diagnostics and support.

---

### 10. Device Log Streaming

When device-side debug logging is active, the firmware streams log entries over USB in real time. Your app receives each entry via `onDeviceLogReceived(level, message, timestamp)` — level can be DEBUG, INFO, WARN, or ERROR. Useful for troubleshooting device-side behaviour without physical access to the hardware.

---

### 11. SDK Logging & Diagnostics

The SDK has a built-in logging system with five severity levels (VERBOSE → ERROR). You can:

- Enable verbose debug logging at runtime without restarting the app
- Set a minimum log level to filter noise
- Register a live callback to receive log entries as they happen
- Call `getDiagnosticReport()` to get a snapshot of the recent log buffer — useful for attaching to support tickets

---

### 12. Error Reporting

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

## Transport Comparison

| Feature | USB | Bluetooth LE |
|---|---|---|
| RFID reading | ✓ | ✗ (USB-locked) |
| Barcode reading | ✓ | ✗ (USB-locked) |
| NFC reading | ✓ | ✗ (USB-locked) |
| Checkout | ✓ | ✗ (USB-locked) |
| Device config update | ✓ | ✓ |
| Auto-reconnect | ✓ | — |
| Range | Cable length | ~10 m |

---

## Supported Hardware

| Device | Use Case |
|---|---|
| **AXL FLAT** | Flat countertop POS reader |

---

## Platform Requirements

| Requirement | Value |
|---|---|
| Android version | 8.0 and above (API 26+) |
| Language | Java 11 |
| USB connection | USB OTG (USB-A to USB-C or Micro-USB) |
| BLE connection | Bluetooth 4.0+ (optional) |
| Distribution | AAR file |

---

## Integration at a Glance

The full integration with a POS app involves four steps:

1. **Add the SDK** to your Android project (AAR file)
2. **Initialize** the SDK once when the app starts
3. **Implement `SdkListener`** to receive events (connected, tag detected, checkout confirmed, USB lock state, errors)
4. **Call commands** (`connect`, `startReading`, `checkoutCompleted`, `sendDeviceConfig`, etc.) in response to user actions

No background services, no complex setup, no native code required.

---

## What the SDK Does Not Do

- It does not manage your item database or EPC-to-product mapping — that stays in your app.
- It does not handle payment processing — `checkoutCompleted` only signals the device; payment is your app's responsibility.
- It does not manage Wi-Fi on the Android device itself — it configures Wi-Fi credentials on the connected RFID hardware.
- It does not allow RFID reading or checkout over BLE when a USB host is active on the device.

---

*For integration details and full API reference, see [README.md](README.md).*
