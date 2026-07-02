# axl SDK — Features Overview

**Version 26.2.6**  
Android SDK for AXL Hardware Device integration over USB and Bluetooth LE

---

## What is axl SDK?

AXL SDK is an Android library that lets your app communicate with AXL Hardware devices over a **USB cable** or **Bluetooth LE**. It takes care of all the low-level communication details — transport connection, protocol messaging, error handling, and reconnection — so your team can focus on building the POS application, not the hardware layer.

---

## Key Features

### Plug-and-Play USB Connectivity

The SDK detects the connected AXL device automatically when plugged in, handles USB permission with a one-time user prompt, performs the connection handshake, and delivers the device identity and current configuration directly to the app. No USB driver code or hardware knowledge required.

---

### Bluetooth LE Remote Configuration

Supports connecting to an AXL device over Bluetooth LE for remote configuration updates — no USB cable needed. The SDK manages device discovery, pairing state, and connection lifecycle transparently.

When a POS tablet is actively connected to the device over USB, a second Bluetooth-connected device automatically enters a safe configuration-only mode — ensuring there is no conflict between simultaneous connections.

---

### Automatic Device Configuration Loading

Every time a connection is established — whether USB or Bluetooth — the SDK immediately delivers the device's current hardware configuration to the application. Settings screens and configuration dialogs can be pre-populated with live device values without any additional fetch request.

---

### RFID Tag Scanning

Full RFID scanning lifecycle support — start, pause, and resume without losing the collected tag list. The SDK normalises tag data across event formats and delivers each EPC to `onTagDetected(epc)` as it is read.

---

### Checkout Transaction Processing

Submitting a completed transaction is a single call. The SDK automatically splits large EPC lists into sequential batches, sending each group to the device and waiting for acknowledgement before sending the next. This ensures reliable, high-throughput transactions regardless of tag count.

- **Default batch size:** 20 EPCs per command
- **Configurable:** batch size can be tuned per deployment
- **Single confirmation:** the application receives one transaction-confirmed event after all batches complete

---

### Barcode Scanning

Integrated barcode reader support for scanning loyalty cards, product barcodes, or shipping labels — all on the same device and the same connection, delivered through the same event listener.

---

### NFC Card Reading

Integrated NFC reading alongside RFID on the same hardware. Each NFC detection reports the tag UID via `onNfcTagDetected(uid)`. On new firmware, `onNfcRawDataReceived(uid, tech, rawData)` also fires with the NFC technology type and raw protocol data.

---

### Live Device Configuration

Push hardware settings to the device at any time without a restart. Configurable settings include:

- RFID frequency region (e.g. Indonesia, North America, Europe)
- Tag air protocol
- Per-antenna enable/disable and read power
- Network mode — wired LAN or Wi-Fi (SSID, security type, password)
- Frequency channel sequence (channel 1–20)
- Read timing — on-frequency dwell, off-frequency dwell

The device confirms every configuration update, and the SDK surfaces that confirmation to the application.

---

### Frequency Channel Management

For supported device types, the SDK translates between simple channel indices (1–20) and the corresponding hardware frequencies automatically. The application works with channel numbers; the SDK handles all frequency resolution internally. No firmware changes and no protocol changes required.

---

### Multi-Antenna Support

Supports up to four antenna ports per device. Each antenna is individually configurable — active state and read power — via `sendDeviceConfig()`.

---

### Device Health Monitoring

On demand, the SDK requests and delivers a real-time health snapshot from the device. The response always includes RFID module temperature; new firmware also includes SD card usage (total, used, free in MB). Useful for field diagnostics and proactive support without requiring physical access to the hardware.

---

### Live Device Log Streaming

When diagnostic logging is enabled on the device, the firmware streams log entries to the application in real time — level, message, and timestamp. Enables deep troubleshooting without needing direct access to the hardware.

---

### SDK Diagnostics

Built-in SDK logging with five severity levels. Debug logging can be toggled at runtime. A full diagnostic report — the recent log buffer — can be captured in a single call and attached directly to a support ticket.

---

### Structured Error Reporting

All errors surface through a single callback with a human-readable description and a short error code, making it straightforward to display appropriate messages to operators or route errors to a logging backend.

| Code | Situation |
|---|---|
| E001 | No device connected |
| E002 | USB permission denied |
| E003 | Device did not respond in time |
| E004 | Device sent a malformed message |
| E005 | Unknown command received |
| E006 | SDK used before initialisation |
| E007 | Missing required field in a message |

---

### Reconnect on Cable Replugged

When the USB cable is replugged, the Android OS fires a USB attach event. The host application handles this to pin the new device path so that when the user taps Connect, the SDK targets the correct port immediately — even if the OS assigned a different device path after re-enumeration.

---

## Transport Comparison

| Capability | USB | Bluetooth LE |
|---|---|---|
| RFID scanning | ✓ | — |
| Barcode scanning | ✓ | — |
| NFC reading | ✓ | — |
| Checkout transactions | ✓ | — |
| Device configuration | ✓ | ✓ |
| Reconnect on replug | ✓ | — |
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
| Android version | 5.0 and above (API 21+) |
| Language | Java 11 |
| USB connection | USB OTG (USB-A to USB-C or Micro-USB) |
| Bluetooth | 4.0+ (optional, for BLE connectivity) |
| Distribution | AAR file — drop into any Android project |

---

## Integration Summary

Integrating axl SDK into a POS application requires four steps:

1. **Add the SDK** — copy the AAR file into the project
2. **Initialise** — one call when the app starts
3. **Implement the event listener** — receive connected, tag detected, checkout confirmed, config loaded, and error events
4. **Call commands** — connect, start scanning, submit checkout, push config — in response to user actions

No background services, no native code, no hardware expertise required.

---

## What the SDK Does Not Handle

- Item database or EPC-to-product mapping — this stays in the application
- Payment processing — the SDK signals transaction completion; payment is the application's responsibility
- Wi-Fi management on the Android device — it configures Wi-Fi credentials on the RFID hardware itself
