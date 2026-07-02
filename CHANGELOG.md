# Changelog

All notable changes to axl SDK are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
Versioning follows [Semantic Versioning](https://semver.org/).

---

## [26.2.6] — 2026-07-02

### Added
- **`module_temp` optional field in `tag_detected`** — new firmware versions include a `"module_temp"` key in `tag_detected` payloads (e.g. `{"type":"RFID","cmd":"tag_detected","epc":[...],"module_temp":45}`). Old hardware omits the key entirely; the SDK handles both cases without error. When present, the temperature is surfaced via the new `SdkListener.onModuleTemperatureReceived(int tempCelsius)` default callback, fired once per batch after all `onTagDetected()` calls for that batch. The callback is never invoked on old hardware. `TagEvent` exposes `getModuleTemp()` returning `-1` when absent.
- **Optional SD card fields in `health_info` response** — new firmware versions include `sd_total_mb`, `sd_used_mb`, and `sd_free_mb` in the `"data"` block of `health_info` payloads. Old hardware sends only `module_temperature`; the new keys are absent and no error occurs. All three fields are documented in `SdkListener.onHealthInfoReceived()` with `optInt(key, -1)` usage guidance. No SDK parse change required — the full `data` JSONObject is already forwarded to the listener as-is.
- **NFC `tech` and `raw_data` optional fields + `onNfcRawDataReceived` callback** — new firmware includes `"tech"` (NFC technology string, e.g. `"NFC-A"`) and `"raw_data"` (tech-specific protocol block with sub-objects such as `"nfca"` and `"isodep"`) in `card_detected` payloads. Old hardware omits both keys; `onNfcRawDataReceived` is never invoked in that case. When either key is present, the SDK fires `onNfcRawDataReceived(uid, tech, rawData)` once immediately after `onNfcTagDetected(uid)`. Use `rawData.optJSONObject("nfca")` etc. — never hard-get — to remain forward compatible with future NFC technology additions.
- **`Sdk.setTargetUsbDevice(UsbDevice)`** — pins the SDK to a specific USB device path for the next `connect()` call. Call this from the `ACTION_USB_DEVICE_ATTACHED` intent handler with the `UsbDevice` delivered by the OS. On Android hosts with multiple USB ports, this ensures the SDK connects to the correct port instead of auto-selecting the first serial device found. The path is updated on every attach event so reconnect after cable re-plug also targets the correct port. Internally, the path is written to both `SdkConfig` (for future `initialize()` calls) and directly to `UsbTransport.targetDevicePath` (for the current session) — the transport's `config` field is final after `initialize()` so replacing `SdkConfig` alone has no effect on `findDevice()`.
- **`SdkConfig.Builder` copy constructor** — `new SdkConfig.Builder(existingConfig)` copies all settings from an existing `SdkConfig`, making it straightforward to update a single field (e.g. `usbDevicePath`) without re-specifying all other options.

### Changed
- **NFC `antenna` field removed from `card_detected`** — device firmware no longer sends `"antenna"` in NFC card detection payloads. `SdkListener.onNfcTagDetected` signature changed from `onNfcTagDetected(String uid, int antenna)` to `onNfcTagDetected(String uid)`. `EventDispatcher.dispatchNfcTagDetected` updated accordingly. Any app overriding the old two-parameter signature must be updated.

### Fixed
- **Checkout confirmation callback fires twice**: After all `checkout_complete` batches were ACK'd, `setSuppressCheckoutConfirmed(false)` was called and `onCheckoutConfirmed` was dispatched. If the device then sent a late duplicate `ack_checkout_complete` (firmware retry or buffering), `suppressCheckoutConfirmed` was still `false` and `pendingTransactionNo` was still set, causing `CommandProcessor` to dispatch a second `onCheckoutConfirmed`. Fixed by immediately re-setting the suppress flag to `true` after the SDK dispatches the callback, so any subsequent duplicate ACKs are silently discarded.
- **Reverted: `read_stop` sent after `checkout_complete`**: A temporary workaround that sent a `read_stop` command after all checkout batches were ACK'd (intended to halt device scanning, which firmware was not doing automatically) has been removed. The device firmware handles stopping on `checkout_complete` correctly; the extra command was unnecessary.
- **USB priority lock via `usb` flag in session handshake** — implements the USB-over-BLE priority rule: when a USB client is connected, any BLE client that connects is restricted to configuration updates only (read, pause, stop, and checkout are all blocked). Four changes across `Sdk`, `SessionManager`, and `CommandProcessor`:
  - **USB `connection_sync` now carries `"usb":true`** — `Sdk.connect()` on USB transport sends `{"type":"SYS","cmd":"connection_sync","usb":true}`. The device stores this flag and echoes it back in every subsequent `ack_connection_sync`, including to BLE clients that connect while USB is active.
  - **USB `disconnect_sync` now carries `"usb":false`** — `Sdk.disconnect()` on USB transport sends `{"type":"SYS","cmd":"disconnect_sync","usb":false}`, clearing the stored flag on the device so BLE clients are unlocked after the USB client disconnects.
  - **Auto-reconnect also sends `"usb":true`** — `SessionManager.onTransportReconnected()` (triggered after a USB cable re-plug) now sends `"usb":true` in the reconnect `connection_sync` so the flag is correctly restored on the device after an unexpected disconnect.
  - **BLE client reads and applies the flag** — `CommandProcessor.parseUsbLockState()` was previously commented out (firmware was sending `usb:true` unconditionally based on USB power, not USB host activity, making the value unreliable). Now that the flag is driven entirely by the SDK, `parseUsbLockState()` is active. It only runs on `BLUETOOTH` transport — USB clients skip it entirely to avoid locking themselves. On `"usb":true`, `usbLockedByRemote` is set and `onUsbLocked()` is dispatched; on `"usb":false`, `usbLockedByRemote` is cleared and `onUsbUnlocked()` is dispatched. The existing `requireNotUsbLocked()` guard in `Sdk` already blocks read/checkout/stop commands when locked — no further changes needed there.
- **`connection_sync` timeout on fresh connect — DTR pulse triggers STM32 CDC reinitialization** — on every fresh port open the SDK was pulsing DTR low → 300 ms → high to ensure a clean modem signal state. On STM32 USB CDC firmware (AXL Flat, VID=0x0483 PID=0x5740), this pulse triggers a USB CDC stack reinitialization that takes over 3 seconds; `connection_sync` arrived during this window and was silently discarded, causing the handshake to time out. Fixed by removing the DTR false→true pulse entirely — DTR is asserted `true` immediately on port open with no preceding low pulse. `PORT_SETTLE_MS` (3 000 ms) gives the firmware's CDC receive path time to initialize before the first command is sent. The port reuse path (kept-alive from a prior session) skips both DTR assertion and the settle delay since the CDC stack is already running.
- **Zombie-scan firmware state causes `connection_sync` to be buried on reconnect** — if the previous session involved tag scanning, the AXL Flat firmware continues scanning after `SessionManager.stop()`. On the next `connect()`, the reused kept-alive port is flooded with `tag_detected` messages that bury the outgoing `connection_sync`, causing the handshake to time out. Two complementary mechanisms address this:
  - **Port-activity peek** — after the read loop shuts down in `disconnect()`, the port is read for up to 450 ms. If any data arrives (firmware still scanning), the port is closed unconditionally so the next `connect()` does a full fresh open instead of reusing the live port. If no data arrives within 450 ms the port is kept alive for fast reconnect.
  - **`notifySessionHadScanData()` fast-close path** — `SessionManager.stop()` checks `receiveBuffer.length()` at teardown; if > 0 it calls `transport.notifySessionHadScanData()` before `disconnect()`. `UsbTransport` sets `sessionHadScanData = true` and closes the port immediately in `disconnect()` without waiting for the 450 ms peek. This handles cases where the firmware scans at < 1 Hz and the peek window would miss a burst between pulses. `Transport` interface gains a `default void notifySessionHadScanData()` method; `UsbTransport` overrides it and resets the flag at the start of each `disconnect()` call.
- **Stale port reused for wrong device in multi-candidate USB retry** — when multiple USB serial devices are enumerated and the first candidate fails the `connection_sync` handshake, `openDevice()` on the next candidate found `serialPort != null` and reused the kept-alive port from the previous candidate rather than opening the correct device. Added a device-name guard at the top of `openDevice()`: if `serialPort` belongs to a different `UsbDevice` than the current target, `closePort()` is called before proceeding to a fresh open.
- **Merged JSON objects in USB buffer discarded silently** — when the device sends two `tag_detected` messages back-to-back in the same USB bulk transfer with no newline separator between them, `processBuffer()` treated the merged content as a single invalid line and discarded it. Fixed with a marker-scan recovery in `extractAndDispatch()` — when a newline-delimited segment fails JSON validation, the segment is scanned for every occurrence of `{"type":` and `findMatchingCloseBrace()` is called fresh at each position. This avoids string-state corruption that a brace-depth walk would accumulate from a truncated first message; the second (complete) message is always recovered and dispatched. The truncated first half is silently skipped — no tag data is lost because every `tag_detected` is a full snapshot of all visible tags repeated every ~200 ms.
- **Auto-reconnect fails when device re-enumerates at a new USB path after reset** — when a device briefly disconnects due to a firmware-triggered USB reset (e.g. on `read_start`), Android re-enumerates it and may assign a different device path (e.g. `/dev/bus/usb/001/002` → `/dev/bus/usb/001/003`). The reconnect loop in `UsbTransport.findDevice()` was locked to the original pinned path and could not find the device, looping indefinitely with `No device at path:` warnings. Fixed by adding a VID/PID fallback: when the pinned path is absent from the device list, `findDevice()` checks whether a device with the same VID/PID as the previously connected device (`usbDevice`) is present at any path and is supported by a registered driver. On match, `targetDevicePath` is updated to the new path and the device is returned. Exact path match still wins when the device re-enumerates at the same path.
- **Device RFID error responses treated as invalid JSON** — the device sends `{"type":"RFID","status":"error","msg":"..."}` (no `"cmd"` field) when a command is rejected (e.g. a config update attempted while the reader is scanning). This message failed `JsonProtocol.isValid()` and was silently dropped or reported as `E004 INVALID_JSON`, hiding the actual device error message. `CommandProcessor` now intercepts these in `routeRfidError()` before the `isValid()` check, releases any pending ack latch immediately (avoiding an unnecessary `COMMAND_TIMEOUT` wait), and dispatches the device message via `onError()`.

---

## [26.2.5] — 2026-06-18

### Added
- **Hop frequency channel mapping for AXL_FLAT devices** (`HopFrequencyTable`): New SDK-internal utility class that maps channel indices 1–20 to the corresponding hardware frequencies and back. The application works with simple channel numbers; the SDK resolves them to the correct frequency values before sending to the device and reverses the mapping when loading the device configuration on connect. Wire format and firmware are unchanged.

### Fixed
- **Checkout batch size default raised from 15 to 20** — `SdkConfig.DEFAULT_CHECKOUT_BATCH_SIZE` is now `20`; apps not setting `checkoutBatchSize()` explicitly will send up to 20 EPCs per `checkout_complete` command instead of 15
- **Auto-reconnect after power-cycle fails to initialise device**: `onTransportReconnected()` was dispatching `onConnected()` directly without performing the `connection_sync` handshake. After a power-cycle the device reboots into its initial state and requires a fresh handshake. Auto-reconnect now sends `connection_sync` and waits for the ACK before dispatching `onConnected()`. If the device is not yet ready (handshake times out), the port is closed and another reconnect attempt is scheduled automatically.
- **USB permission crash on Android 14+ (API 34+)**: `UsbTransport.requestPermission()` was creating a `PendingIntent` with `FLAG_MUTABLE`, which Android 14 blocks for implicit intents. Changed to `FLAG_IMMUTABLE` — the USB permission system does not require a mutable intent and works correctly with this flag on all supported Android versions.
- **Spurious disconnect/connect cycling during auto-reconnect**: `openDevice()` was calling `onTransportDisconnected()` on every failed reconnect attempt (no driver, `usbManager.openDevice()` returned null, port config error). Each failure fired `onDisconnected()` in the app, producing visible connect/disconnect cycling while the cable was unplugged. Failures during the reconnect path are now handled silently; `onTransportDisconnected()` is only fired when the initial `connect()` call fails.
- **Permission receiver ignored reconnect context**: The USB permission broadcast receiver always called `openDevice(device, false)`, which suppressed the `onTransportReconnected()` notification even when permission was requested during auto-reconnect. The receiver now passes the correct reconnect context flag so `onConnected()` fires correctly. It also schedules a retry if permission is denied during auto-reconnect instead of silently stopping the loop.
- **Auto-reconnect loops indefinitely on devices that report zero antennas in `ack_connection_sync`**: A previous fix added an antenna-count guard to `SessionManager.onTransportReconnected()` — if `ack_connection_sync` contained zero antennas the handshake was retried, on the assumption that zero antennas meant the RFID module was not yet ready. The guard caused the retry loop to exhaust all five attempts on every reconnect and left the device permanently disconnected. The antenna-count check has been removed. `onConnected()` is now dispatched as soon as a valid `ack_connection_sync` ACK is received; the retry loop fires only when the handshake ACK does not arrive within the configured timeout window.
- **`connection_sync` sent before device UART is ready after USB re-enumeration**: After the serial port was opened on reconnect, `connection_sync` was sent immediately — before the CDC/ACM UART receive buffer had stabilised and before any DTR-triggered device reset had completed. On some firmware configurations asserting DTR causes a brief MCU reset; sending a command during that window meant the device never received it, causing the handshake to time out unnecessarily. A 500 ms settle delay (`PORT_SETTLE_MS`) has been added in `UsbTransport.openDevice()` between port open and the first handshake command on the reconnect path. Initial `connect()` calls are unaffected.

---

## [26.2.4] — 2026-06-10

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
- `DeviceInfo.SKU_AXL_FLAT = "A120IAB"` — SKU constant for AXL FLAT device
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
