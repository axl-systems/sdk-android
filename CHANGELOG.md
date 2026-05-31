# Changelog

All notable changes to axl SDK are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
Versioning follows [Semantic Versioning](https://semver.org/).

---

## [26.2.1] — 2026-05-29

Initial public release of axl SDK.

### Features

**Connection**
- USB connection management with automatic device detection
- `connection_sync` / `disconnect_sync` handshake protocol
- Auto-reconnect on unexpected USB drop (configurable)
- `onConnected()`, `onDisconnected()` lifecycle callbacks
- `onDeviceIdentified(DeviceInfo)` — device SKU and display name after handshake
- `onAntennasDetected(List<Integer>)` — hardware antenna ports reported after connect

**RFID**
- `startReading()` — start RFID tag scanning
- `pauseReading()` — pause scanning, preserve collected tags
- `stopReading(epcs)` — stop scanning and deliver EPC list to device
- `checkoutCompleted(transactionNo, epcs)` — complete a POS checkout transaction
- `sendDeviceConfig(RfidDeviceConfig)` — push configuration in AE03A001 lean format (`config` command)
- `updateDeviceConfig(RfidDeviceConfig)` — push configuration in full format (`update_config` command)
- `onTagDetected(epc, antenna)` — fires per EPC during active scanning
- `onReadingPaused()`, `onReadingStopped()`, `onCheckoutConfirmed()`, `onConfigUpdated()` callbacks

**Barcode**
- `startBarcodeReading()` / `stopBarcodeReading()`
- `onBarcodeTagDetected(data)`, `onBarcodeCommandAcknowledged(cmd)`, `onBarcodeReadingStopped()` callbacks

**NFC**
- `startNfcReading()` / `stopNfcReading()`
- `onNfcTagDetected(uid, antenna)`, `onNfcCommandAcknowledged(cmd)`, `onNfcReadingStopped()` callbacks

**Device Configuration (`RfidDeviceConfig.Builder`)**
- Region, protocol, read power, per-antenna enable/power settings (4 ports)
- Network: LAN (`networkLan()`) or Wi-Fi (`networkWifi(ssid, security, password)`)
- Frequency: hop time, read on/off frequency, hop frequency list
- `toJson()` — full `update_config` payload
- `toAe03Json()` — lean `config` payload for AE03A001 devices
- `put(key, value)` escape hatch for future fields

**SDK Configuration (`SdkConfig.Builder`)**
- Configurable command ACK timeout, baud rate, auto-reconnect, debug logging
- `reconfigure(SdkConfig)` — change transport settings at runtime

**Logging & Diagnostics**
- Built-in ring-buffer logger with five levels: VERBOSE, DEBUG, INFO, WARN, ERROR
- `setDebugLogging(boolean)` — toggle at runtime
- `getDiagnosticReport()` — formatted log snapshot for support
- `onDeviceLogReceived(level, message, timestamp)` — real-time firmware log stream

**Supported Devices**
- AXL FLAT — `DeviceInfo.DEVICE_TYPE_AXL_FLAT`
- AXL BIN — `DeviceInfo.DEVICE_TYPE_AXL_BIN`
- AXL GATE — `DeviceInfo.DEVICE_TYPE_AXL_GATE`

**Error Codes**
- E001 `DEVICE_NOT_CONNECTED`
- E002 `USB_PERMISSION_DENIED`
- E003 `COMMAND_TIMEOUT`
- E004 `INVALID_JSON`
- E005 `UNSUPPORTED_COMMAND`
- E006 `SDK_NOT_INITIALIZED`
- E007 `INVALID_PAYLOAD`

---

<!-- next release entry goes above this line -->
