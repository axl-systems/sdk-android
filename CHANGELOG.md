# Changelog

All notable changes to axl SDK are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
Versioning follows [Semantic Versioning](https://semver.org/).

---

## [26.2.5] — 2026-06-12

### Changed
- **Checkout batch size default raised from 15 to 20** — `SdkConfig.DEFAULT_CHECKOUT_BATCH_SIZE` is now `20`; apps not setting `checkoutBatchSize()` explicitly will send up to 20 EPCs per `checkout_complete` command instead of 15

### Fixed
- **Spurious disconnect/connect cycling during auto-reconnect**: `openDevice()` was calling `onTransportDisconnected()` on every failed reconnect attempt (no driver, `usbManager.openDevice()` returned null, port config error). Each failure fired `onDisconnected()` in the app, producing visible connect/disconnect cycling while the cable was unplugged. Failures during the reconnect path are now handled silently; `onTransportDisconnected()` is only fired when the initial `connect()` call fails.
- **Auto-reconnect after power-cycle fails to initialise device**: `onTransportReconnected()` was dispatching `onConnected()` directly without performing the `connection_sync` handshake. After a power-cycle the device reboots into its initial state and requires a fresh handshake. Auto-reconnect now sends `connection_sync` and waits for the ACK before dispatching `onConnected()`. If the device is not yet ready (handshake times out), the port is closed and another reconnect attempt is scheduled automatically.
- **Permission receiver ignored reconnect context**: The USB permission broadcast receiver always called `openDevice(device, false)`, which suppressed the `onTransportReconnected()` notification even when permission was requested during auto-reconnect. The receiver now passes the correct reconnect context flag so `onConnected()` fires correctly. It also schedules a retry if permission is denied during auto-reconnect instead of silently stopping the loop.
- **Auto-reconnect loops indefinitely on devices that report zero antennas in `ack_connection_sync`**: A previous fix added an antenna-count guard to `SessionManager.onTransportReconnected()` — if `ack_connection_sync` contained zero antennas the handshake was retried, on the assumption that zero antennas meant the RFID module was not yet ready. Testing against SKU `AE10A001` showed that this device never includes antenna data in `ack_connection_sync` by firmware design, even when fully operational. The guard caused the retry loop to exhaust all five attempts on every reconnect and left the device permanently disconnected. The antenna-count check has been removed. `onConnected()` is now dispatched as soon as a valid `ack_connection_sync` ACK is received; the retry loop fires only when the handshake ACK does not arrive within the configured timeout window.
- **`connection_sync` sent before device UART is ready after USB re-enumeration**: After the serial port was opened on reconnect, `connection_sync` was sent immediately — before the CDC/ACM UART receive buffer had stabilised and before any DTR-triggered device reset had completed. On some STM32 firmware configurations asserting DTR causes a brief MCU reset; sending a command during that window meant the device never received it, causing the handshake to time out unnecessarily. A 500 ms settle delay (`PORT_SETTLE_MS`) has been added in `UsbTransport.openDevice()` between port open and the first handshake command on the reconnect path. Initial `connect()` calls are unaffected.
- **Spurious disconnect/connect cycling during auto-reconnect**: `openDevice()` was calling `onTransportDisconnected()` on every failed reconnect attempt (no driver, `usbManager.openDevice()` returned null, port config error). Each failure fired `onDisconnected()` in the app, producing visible connect/disconnect cycling while the cable was unplugged. Failures during the reconnect path are now handled silently; `onTransportDisconnected()` is only fired when the initial `connect()` call fails.
- **Auto-reconnect after power-cycle fails to initialise device**: `onTransportReconnected()` was dispatching `onConnected()` directly without performing the `connection_sync` handshake. After a power-cycle the device reboots into its initial state and requires a fresh handshake. Auto-reconnect now sends `connection_sync` and waits for the ACK before dispatching `onConnected()`. If the device is not yet ready (handshake times out), the port is closed and another reconnect attempt is scheduled automatically.
- **Permission receiver ignored reconnect context**: The USB permission broadcast receiver always called `openDevice(device, false)`, which suppressed the `onTransportReconnected()` notification even when permission was requested during auto-reconnect. The receiver now passes the correct reconnect context flag so `onConnected()` fires correctly. It also schedules a retry if permission is denied during auto-reconnect instead of silently stopping the loop.

---

## [26.2.4] — 2026-06-11

### Changed
- **Min SDK lowered from API 26 to API 21** (Android 5.0 Lollipop) — the SDK now supports a wider range of Android devices
- Removed `java.time.Instant` usage in `getCurrentTimestamp()` — replaced with `SimpleDateFormat` which works on all supported API levels without requiring core library desugaring

---

## [26.2.3] — 2026-06-08

### Changed
- `ack_connection_sync` response key renamed: `config` → `config_data` for the device hardware configuration block
- Health info response field changed: `onHealthInfoReceived` now receives `module_temperature` (Integer, °C) only — previous fields `cpu_percent`, `memory_percent`, `memory_used_mb`, `memory_total_mb`, and `temperature` are no longer returned by current device firmware

### Fixed
- **BLE reconnect**: `negotiatedMtu` is now reset to `DEFAULT_MTU` at the start of each `connect()` call — prevents oversized chunks being sent in a new session that reused a stale MTU value from a prior connection
- **BLE MTU overflow**: `IllegalArgumentException` thrown by `gatt.writeCharacteristic()` on API 33+ (when chunk size exceeds OS-tracked MTU limit) is now caught in `writeChunk()`; MTU resets to default on catch so the session recovers rather than hanging
- **Duplicate checkout callback**: `suppressCheckoutConfirmed` is now always set to `true` before the batch loop, eliminating a double `onCheckoutConfirmed` dispatch that occurred for single-batch checkouts (previously the flag was only set when batch count > 1)

### Known Limitations
- USB lock detection over BLE (`parseUsbLockState`) is temporarily disabled pending a firmware update that correctly distinguishes USB host connection from USB power-only. `onUsbLocked()` / `onUsbUnlocked()` will not fire until the firmware update is available.

---

## [26.2.2] — 2026-06-04

### Added
**Bluetooth LE transport**
- `TransportType.BLUETOOTH` — new transport option alongside existing `USB`
- `BluetoothLeTransport` — Nordic UART Service (NUS) GATT transport using the same JSON protocol as USB
- `Sdk.connectBle(macAddress)` — convenience method to reconfigure and connect via BLE
- `Sdk.startBleScan()` / `stopBleScan()` / `getBondedBleDevices()` — BLE device discovery
- `Sdk.isBluetoothTransport()` — returns `true` when active transport is BLE
- `SdkConfig.Builder.bleDeviceAddress(String)` — BLE MAC address for BLUETOOTH transport type
- `BleDeviceInfo` model — name, address, RSSI, bonded state
- `SdkListener.onBleDeviceFound(BleDeviceInfo)` — fires per device during scan
- `SdkListener.onBleScanComplete(List<BleDeviceInfo>)` — fires when scan ends
- `SdkConfig.checkoutBatchSize` — default 15 EPCs per `checkout_complete` batch
- `onDeviceConfigLoaded(JSONObject)` — device's current config received on connect
- `onUsbLocked()` / `onUsbUnlocked()` — BLE config-only mode callbacks
- `DeviceInfo.getSku()` and `DeviceInfo.SKU_AXL_FLAT = "A120IAB"`
- `Sdk.isUsbLockedByRemote()`, `Sdk.isBluetoothTransport()`
- BLE scan: `startBleScan()`, `stopBleScan()`, `getBondedBleDevices()`, `connectBle()`

**USB lock / config-only mode**
- `Sdk.isUsbLockedByRemote()` — `true` when BLE-connected and device reports USB host active
- `SdkListener.onUsbLocked()` — fires when `ack_connection_sync` or `usb_state_changed` contains `usb:true`
- `SdkListener.onUsbUnlocked()` — fires when device broadcasts `usb_state_changed` with `usb:false`
- All read and checkout commands (`startReading`, `pauseReading`, `stopReading`, `checkoutCompleted`, `startBarcodeReading`, `stopBarcodeReading`, `startNfcReading`, `stopNfcReading`) throw `IllegalStateException` and dispatch `onError` when USB-locked
- `Command.CMD_USB_STATE_CHANGED = "usb_state_changed"` — new inbound SYS command

**Device config loaded on connect**
- `SdkListener.onDeviceConfigLoaded(JSONObject config)` — fires after `onConnected()` with the device's current hardware configuration parsed from `ack_connection_sync`
- Apps can use this to pre-populate Settings dialogs without a separate fetch command

**Checkout batching**
- `SdkConfig.DEFAULT_CHECKOUT_BATCH_SIZE = 15` — default EPC batch size
- `SdkConfig.Builder.checkoutBatchSize(int)` — configure EPCs per `checkout_complete` command
- `checkoutCompleted()` automatically splits EPC lists into sequential batches
- `onCheckoutConfirmed()` fires once after all batches ACK'd — not per batch
- `CommandProcessor.setSuppressCheckoutConfirmed(boolean)` — internal mechanism for batch control
- Set to `0` to disable batching (send all EPCs in one command)

**DeviceInfo updates**
- `DeviceInfo.getSku()` — returns the device SKU string from `ack_connection_sync` (e.g. `"A120IAB"`)
- `DeviceInfo.SKU_AXL_FLAT = "A120IAB"` — SKU constant for AXL FLAT STM device
- New constructor `DeviceInfo(name, deviceType, sku)` — existing 2-arg constructor unchanged

### Changed
- `usb-serial-for-android` updated to `3.9.0`
- All read/checkout commands blocked when USB-locked via BLE
- `ack_connection_sync` response now parsed for `sku`, `usb`, and `config` fields in addition to existing `device` and `device_type`
- `connection_sync` and `disconnect_sync` handshake commands unchanged in wire format — firmware update required to enable `usb:true` signalling

### Dependencies

- `usb-serial-for-android` updated from `3.8.1` to `3.9.0`


## [26.2.1] — 2026-05-29

Initial public release of axl SDK.

### Features

**Connection**
- USB connection management with automatic device detection
- `connection_sync` / `disconnect_sync` handshake protocol
- Auto-reconnect on unexpected USB drop (configurable)
- `onConnected()`, `onDisconnected()` lifecycle callbacks
- `onDeviceIdentified(DeviceInfo)` — device type and display name after handshake
- `onAntennasDetected(List<Integer>)` — hardware antenna ports reported after connect

**RFID**
- `startReading()` — start RFID tag scanning
- `pauseReading()` — pause scanning, preserve collected tags
- `stopReading(epcs)` — stop scanning and deliver EPC list to device
- `checkoutCompleted(transactionNo, epcs)` — complete a POS checkout transaction
- `sendDeviceConfig(RfidDeviceConfig)` — push configuration in lean format (`config` command)
- `updateDeviceConfig(RfidDeviceConfig)` — push configuration in full format (`update_config` command)
- `getReadingStatus()` — query reader active/inactive state
- `getHealthInfo()` — request device CPU, memory, temperature diagnostics
- `onTagDetected(epc, antenna)` — fires per EPC during active scanning
- `onReadingPaused()`, `onReadingStopped()`, `onCheckoutConfirmed()`, `onConfigUpdated()` callbacks
- `onHealthInfoReceived(JSONObject)`, `onReaderStatusReceived(boolean)` callbacks

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
- `toAe03Json()` — lean `config` payload for AXL FLAT devices

**SDK Configuration (`SdkConfig.Builder`)**
- Configurable command ACK timeout, baud rate, auto-reconnect, debug logging
- `reconfigure(SdkConfig)` — change transport settings at runtime

**Logging & Diagnostics**
- Built-in ring-buffer logger with five levels: VERBOSE, DEBUG, INFO, WARN, ERROR
- `setDebugLogging(boolean)` — toggle at runtime
- `getDiagnosticReport()` — formatted log snapshot for support
- `onDeviceLogReceived(level, message, timestamp)` — real-time firmware log stream

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
