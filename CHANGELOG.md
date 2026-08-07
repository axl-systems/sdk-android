# Changelog

All notable changes to axl SDK are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
Versioning follows [Semantic Versioning](https://semver.org/).

---

## [26.2.9] — 2026-08-07

### Added
- **AXL ECU USB device support** — the SDK now recognises the AXLSYSTEMS AXLECU hardware (VID `0x061F` / PID `0x3D38`). A custom `buildProber()` method extends the default usb-serial-for-android probe table with the AXLECU VID/PID mapped to `CdcAcmSerialDriver`. `KNOWN_DEVICES` updated so the device appears as `"AXLSYSTEMS AXLECU"` in connection logs. The SDK AAR's `device_filter.xml` now includes the AXLECU entry so Android delivers USB attach intents to the app. Previously the AXLECU's custom vendor ID was unknown to both the default prober and the OS intent filter, causing `findDevice()` to return null and the connection to fail immediately with `"No supported USB serial device found"`.

### Fixed
- **Stale port causes connection failure after USB cable replug (AXL ECU and AXL FLAT, ~1–2 in 15 attempts)** — when the previous session had no scan activity, `disconnect()` kept the USB serial port alive (keep-alive optimisation). If the cable was then physically unplugged and replugged, the Android USB stack re-enumerated the device and invalidated the old port file descriptor. On the next `connect()`, the reuse path in `openDevice()` attempted to flush the stale port, caught an `IOException` (`USB get_status request failed`), logged it as non-fatal, and continued to start the read loop on the dead handle. The read loop hit 20 consecutive errors and declared disconnect — before `connection_sync` was ever sent — producing `[E001] DEVICE_NOT_CONNECTED`. Fixed: a flush `IOException` in the reuse path now calls `closePort()` and falls through to the fresh-open path (full port open + DTR + `PORT_SETTLE_MS` settle), identical to a first-ever connect. Affects all device types; failure rate drops to zero.

---

## [26.2.8] — 2026-07-16

### Added
- **`Sdk.releasePort()` — explicit USB port release for app-close path** — the SDK's keep-alive optimisation intentionally leaves the USB port open between same-app disconnects and reconnects (avoids a 3 s DTR settle delay). When the app closes after a disconnect without scanning, the port remained open in the process; a second app (or a fresh launch) calling `connect()` then raced against the lingering `UsbDeviceConnection`, causing `ack_connection_sync` to be captured by the old endpoint queue and the new app to time out. `releasePort()` closes the port unconditionally — call it from `Activity.onStop()` when `isFinishing()=true` after any `disconnectBlocking()`. No-op for BLE/WiFi transports. Also added as `Transport.releasePort()` default method.
- **`Sdk.getDeviceConfig()` — explicit device config fetch** — sends `{"type":"SYS","cmd":"device_config"}` and fires `SdkListener.onDeviceConfigLoaded(JSONObject)` with the full `config_data` block (region, protocol, antenna items with id/active/read_power/write_power, network_settings, hop_time, hop_frequency, etc.). Call this from the Settings gear icon tap handler to populate the Settings dialog with live device values. Uses `TIMEOUT_CONFIG_MS = 2 000 ms`.
- **`Command.CMD_DEVICE_CONFIG = "device_config"` and `CMD_ACK_DEVICE_CONFIG = "ack_device_config"`** — new SYS command constants.
- **`PacketFramer` — reliable packet transport layer** — all USB communication is now wrapped in the `reliable_packeter_t` frame format (matching the firmware's `packet_serial.c`): `[0xAA][0xBB][MSG_ID][CHUNK_IDX][FLAGS][LEN][PAYLOAD ≤120 B][CRC16_H][CRC16_L][0xAB]`. CRC16-CCITT (poly=0x1021, init=0xFFFF). Large messages are split into ≤120-byte chunks; each chunk is ACK'd by the receiver (FLAG_ACK=0x02) or NACK'd on CRC failure (FLAG_NACK=0x04) and retried up to 3×. FLAG_LAST_CHUNK=0x01 marks the final chunk; the receiver fires the message callback only when the last chunk is ACK'd and reassembled. `PacketFramer` is transparent to the public SDK API — all existing method signatures, callback names, and parameters are unchanged. Falls back to raw writes if the framer is unavailable (should not occur in normal operation).

### Changed
- **`MAX_PAYLOAD` reduced from 120 → 64 bytes** — USB Full Speed bulk transfers are naturally bounded to 64 bytes per packet; splitting chunks at 120 bytes caused each chunk to be fragmented into two USB packets by the driver, doubling the round-trip ACK latency on every chunk and increasing the probability of a lost ACK under firmware CDC TX load. Aligning `MAX_PAYLOAD` to 64 bytes means each chunk fits in exactly one USB packet, halving the expected chunk-ACK latency and making framing behaviour consistent with the USB wire protocol. **The firmware `reliable_packeter_t` must update `MAX_PAYLOAD` to 64 on the STM32 side to match.**
- **`ack_connection_sync` no longer contains `config_data`** — the firmware team simplified the handshake response to only basic identification fields (`type`, `cmd`, `build_version`, `sku`, `device_type`, `usb`). Config data is now fetched via the new `device_config` / `ack_device_config` command instead. `onDeviceConfigLoaded()` no longer fires as a side effect of `connect()` directly — it fires from the auto device_config fetch that follows immediately after. SDK handles both old (with config_data) and new (without) `ack_connection_sync` gracefully — no breaking change.
- **`RF_INIT_TAIL_MS` reduced from 55 000 ms to 15 000 ms** — the tail-wait extension was raised to 55 s to cover the ~44 s RF hardware initialization that blocked mid-`ack_connection_sync` on cold starts. With the new simplified `ack_connection_sync` the RF hardware query no longer happens during the handshake, so the tail only needs to cover transient delays. 15 s is sufficient; total worst-case wait is now 5 s + 15 s = 20 s before giving up.

### Fixed
- **`connect()` wastes 12 × 1 s retries when USB transport dies mid-handshake** — when the USB link drops during a `connection_sync` retry loop (e.g. cable moved to a different port, read error on POS), `transport.send()` throws `TransportException(DEVICE_NOT_CONNECTED)`. The inner catch in the retry loop was treating this identically to a PacketFramer chunk-ACK timeout and sleeping 1 s before each of the remaining attempts, burning up to 12 s before dispatching the error. Fixed: when the caught `TransportException` has error code `DEVICE_NOT_CONNECTED`, the loop re-throws immediately instead of sleeping — so the error reaches `onError()` in < 100 ms rather than 12 s.
- **Second connect within the same session fails immediately (< 2 s) — `[E003]`** — after a plain connect→disconnect (no scanning), the SDK's 450 ms "peek" read in `UsbTransport.disconnect()` detected normal firmware health-alert or status bytes arriving after `ack_disconnect_sync` and incorrectly treated them as ongoing scan activity, closing the port. On POS closing the port cuts USB VBUS, cold-booting the STM32. On the very next `connect()` call, `findDevice()` ran while the device was still re-enumerating → could not open the device → `TransportException` dispatched immediately, before the retry loop even started. Two-part fix: (1) the 450 ms peek is removed from `disconnect()` — the port-close decision is now based solely on whether actual tag data was dispatched this session; (2) `SessionManager.stop()` replaced the coarse `rxBytes > 0` heuristic (any bytes in the receive buffer, including health alerts, triggered a port close) with an accurate `CommandProcessor.hadScanData()` flag that is set only when `handleTagDetected()`, `handleBarcodeTagDetected()`, or `handleNfcTagDetected()` actually dispatches a tag event. Any residual firmware bytes from a non-scanning session are drained by the existing 400 ms flush in `openDevice()`'s reuse path on the next connect.
- **`connect()` always fails after closing and reopening the same app (POS)** — closing the serial port on POS cuts USB VBUS, cold-booting the STM32 firmware. The SDK's retry loop in `connect()` retried only when `sendAndAwaitAck()` returned `false` (command-level timeout), but PacketFramer's per-chunk ACK timeout (1.5 s) fires first and throws `TransportException` instead of returning false — this exception was caught *outside* the retry loop and dispatched as an error immediately, with no retries. Fix: inner try-catch around `sendAndAwaitAck()` inside the loop converts `TransportException` into a retry; `MAX_CONNECT_RETRIES` increased from 2 to 12, covering a ~30-second cold-boot window (12 × 2.5 s per cycle). Also fixed an orphaned `CountDownLatch` left in `CommandProcessor.pendingLatches` when `transport.send()` threw — now removed on exception.
- **App crash when any SDK method is called after a packet ACK timeout** — `requireConnected()` and `requireNotUsbLocked()` threw `IllegalStateException` when the device was not connected. On the main thread (e.g. tapping NFC Start after a prior command timed out and the session was incorrectly closed) this uncaught exception crashed the app. Both helpers now return `boolean` instead of throwing; all 13 public API callers updated to `if (!requireConnected()) return;` pattern. Error is still dispatched via `onError()` — no behavioral change for healthy sessions.
- **Session incorrectly torn down on PacketFramer chunk-ACK timeout** — when the firmware didn't ACK a packet chunk within the timeout (observed during rapid command sequences or back-to-back large checkout batches), `UsbTransport.send()` called `closePort()` + `connected.set(false)` before throwing `TransportException`. The device was still physically present and the connection was valid; closing the port was unnecessary and left the SDK in a disconnected state requiring a full reconnect. Port teardown on packet-ACK timeout removed — the connection now survives a framer timeout, and the caller's `onError()` fires as a soft command-timeout (E003) with the session intact.
- **PacketFramer chunk-ACK timeout too short (200 ms) — spurious failures during rapid commands** — the firmware's USB CDC TX task can be briefly occupied sending a prior response (e.g. `ack_read_start`, `ack_checkout_complete`) when the next command chunk arrives, delaying the packet-ACK by up to ~400 ms. With the previous 200 ms per-chunk timeout (3 retries = 600 ms total) this caused false failures on rapid Pause→Start→Pause sequences and back-to-back checkout batches. Increased to 500 ms per-chunk (3 retries = 1 500 ms total) to cover the observed firmware CDC TX latency.
- **`TIMEOUT_HEALTH_MS` corrected from 600 ms to 5 000 ms** — `health_info` firmware response gathers CPU/memory/temperature diagnostics which can take 2–5 s on the STM32; the previous 600 ms timeout caused it to always fail immediately on loaded hardware. Corrected to 5 000 ms to match the actual firmware response window.
- **`getReadingStatus()`, `getHealthInfo()`, `getDeviceConfig()` — one-retry on timeout** — these three informational queries previously dispatched `onError()` immediately on any timeout. During an active RFID scan burst the firmware's serial-receive task can be briefly backlogged by outgoing `tag_detected` frames, causing a spurious timeout even though the device is healthy. All three now silently retry once after a 300 ms pause (the same pattern already used by `pauseReading()` and `stopReading()`), dispatching `onError()` only if the second attempt also fails. Implemented via a new private `dispatchCommandWithRetry()` helper; queued on `commandExecutor` (not `urgentExecutor` — these are informational queries, not scan-control commands). Public API, callback names, and callback signatures are unchanged.
- **Removed `read_stop` zombie-scan guard before `connect_sync`** — `connect()` was sending `read_stop` (up to 2 attempts × 600 ms + 300 ms settle + buffer flush) before every `connection_sync` on fresh opens. On multi-port devices such as POS this interfered with the firmware's port state and was a suspected cause of intermittent connectivity failures. The `SdkCleanupService` already handles the zombie-scan case (sends `read_stop`/`disconnect_sync` from `onTaskRemoved`), and the force-close on failed `connection_sync` ensures a clean slate on the next attempt. Removing the guard saves ~1.5 s on every fresh-open connect and eliminates the multi-port interference.
- **`connection_sync` times out on POS POS devices (cold-start flash read)** — the POS USB controller cuts VBUS on port close, so the RFID module cold-boots on every connect (unlike consumer tablets that keep VBUS high and cache config in RAM). On cold boot the firmware sends the first ~254 bytes of `ack_connection_sync` immediately, then blocks for **19–24 s** reading `config_data` from flash before sending the rest. Observed on both Android 11 (POS, silence ~24 000 ms) and Android 12 (POS Qualcomm, silence ~19 000 ms). `TIMEOUT_HANDSHAKE_MS` raised from 25 000 ms to **40 000 ms**, giving 16 s headroom over the 24 s worst-case cold-read. First-attempt success is now possible on all tested POS variants without relying on the retry. Worst-case connect time on failure: ~86 s (3 s settle + 40 s attempt-1 + 3 s delay + 40 s attempt-2).
- **Stale partial `ack_connection_sync` bytes corrupt retry attempt** — after a `connection_sync` timeout the receive buffer held the partial response (~218 bytes, mid-JSON) from the failed attempt. On retry, new `ack_connection_sync` bytes were appended to this stale fragment; `processBuffer` could never assemble a valid message, so the retry always timed out even when the full response arrived in time. Fixed by calling `SessionManager.flushReceiveBuffer()` after the inter-attempt delay (`CONNECT_RETRY_DELAY_MS`) so each retry starts with a clean buffer.
- **Port kept alive after failed `connection_sync` causes next connect to receive zero bytes** — when both `connection_sync` attempts failed and the receive buffer was empty at session stop (`bufferLen = 0`), `sessionHadScanData` remained false and the USB port was kept alive. On the next `connect()` the reused port had the firmware's CDC TX queue in an unknown or stuck state — the device sent zero bytes in response to any command (`device silent for >> 1118181ms` observed on L1400). Fixed by calling `transport.notifySessionHadScanData()` before `sessionManager.stop()` when all `connection_sync` attempts fail, unconditionally closing the port and forcing a fresh open with DTR reassertion and `PORT_SETTLE_MS` settle on the next attempt.

---

## [26.2.7] — 2026-07-13

### Added
- **`build_version` field in `DeviceInfo`** — `DeviceInfo` gains a `buildVersion` field populated from the `"build_version"` key in `ack_connection_sync` (e.g. `"26.2.3"`). Accessible via `DeviceInfo.getBuildVersion()`. Defaults to `"26.2.2"` when the key is absent (older hardware that does not report it). A new 4-argument constructor `DeviceInfo(name, deviceType, sku, buildVersion)` is added; existing 2- and 3-argument constructors delegate to it unchanged. `CommandProcessor.parseAndDispatchDeviceInfo()` passes the parsed value; the `DeviceInfo.toString()` output now includes `buildVersion` when non-empty.
- **`Sdk.disconnectBlocking()` — synchronous disconnect for `onStop(isFinishing)` / `onTaskRemoved()` paths** — sends `read_stop` (with one retry) followed by `disconnect_sync`, both on the calling thread with no executor. `read_stop` is required before `disconnect_sync` because the firmware keeps the RF module active after `ack_connection_sync` and does not halt it on `disconnect_sync` alone; in normal user flow the reader is already stopped before disconnect is called, but in `onStop(isFinishing=true)` it may still be scanning. Identical to `disconnect()` but runs the handshake entirely on the calling thread instead of submitting to `connectExecutor`. This is required when the Android process is about to die: `disconnect()` is asynchronous — Android kills the process before `connectExecutor` has a chance to run. Does not fire `SdkListener` callbacks — callers in a shutdown path have no UI to update.

### Changed
- **`TIMEOUT_HANDSHAKE_MS` raised to 5 000 ms** — `connection_sync` handshake timeout increased from 3 000 ms to 5 000 ms. On Android 12–14 the USB CDC ACM host driver delivers `ack_connection_sync` (a ~700-byte JSON object) in 1-byte bulk fragments; reassembly at USB Full Speed has been observed to take 4–5 s on Android 14, causing both retry attempts to time out before the ACK arrived. All other timeouts unchanged: `TIMEOUT_CONFIG_MS = 2 000`, `TIMEOUT_HEALTH_MS = TIMEOUT_STATUS_MS = TIMEOUT_COMMAND_MS = 600`. The inter-attempt delay (`CONNECT_RETRY_DELAY_MS`) is also raised from 2 000 ms to 3 000 ms to give the driver additional time to finish fragment reassembly between attempts. Worst-case connect time on failure: ~16 s (3 s settle + 5 s attempt-1 + 3 s delay + 5 s attempt-2).
- **`handleTagDetected` — single atomic batch log line** — replaced 4–5 separate `Logger.i` calls per `tag_detected` batch with a single `dispatchDeviceLog("RFID", ...)` call. Under high tag-flood rates, multiple Logger calls were interleaved or silently truncated by the logging buffer; a single call is atomic. Format: `[RFID] tag_detected — N tags  [EPC1, EPC2, ...]` with optional `  temp: 42°C` suffix when `module_temp` is present.


### Fixed
- **`connection_sync` times out on first connect after abnormal app exit (zombie scan — stale config responses)** — even after the `read_stop` zombie guard drained the ongoing scan burst, stale firmware responses queued from the previous session (e.g. `ack_antenna_config`, `ack_network_config`, health alerts) remained in the CDC TX buffer. These arrived fragmented during port settle and the read_stop window, creating a broken head fragment in the receive buffer (`processBuffer: incomplete message [5555ms, 606 chars]`). The fragment blocked the receive pipeline until the stale-discard timer fired (500 ms), but by then `ack_connection_sync` had already timed out. Fixed by adding a 300 ms settle pause after the `read_stop` guard (to let remaining queued bytes arrive), then calling `SessionManager.flushReceiveBuffer()` to discard all accumulated stale data before sending `connection_sync`. The new `flushReceiveBuffer()` method clears `receiveBuffer` and resets `incompleteHeadSince`.
- **`connection_sync` times out on first connect after abnormal app exit (zombie scan — scan frames)** — when a previous session ended without a clean `disconnect_sync` (process killed, app crash), the firmware continued scanning. On the next fresh port open the USB CDC TX queue was occupied by ongoing `tag_detected` frames; `ack_connection_sync` (~700 bytes) was delivered only partially (~256 bytes) before the scan task preempted the TX, causing a `COMMAND_TIMEOUT` even though the device was physically healthy. Fixed by sending `read_stop` (2 attempts, 400 ms gap) before `connection_sync` on fresh opens only. Port reuse (clean prior disconnect) skips the guard entirely — no overhead on normal reconnect. `Transport.wasLastOpenReuse()` added (default `true` for non-USB transports); `UsbTransport` sets it in `openDevice()`.
- **`disconnect()` times out on `disconnect_sync` when called while scanning** — when the user tapped Disconnect while the RFID reader was active (mode=SCANNING), `disconnect()` sent `disconnect_sync` immediately without first stopping the reader. The firmware's STM32 serial-receive task was saturated by outgoing `tag_detected` frames and could not process `disconnect_sync` within the 600 ms window, producing an `E003 COMMAND_TIMEOUT` error and a forced disconnect (port closed, next connect required a full fresh open with 3 s settle). Fixed by sending `read_stop` (up to 3 attempts, 400 ms gaps) before `disconnect_sync` when mode is SCANNING, matching the pattern already used by `disconnectBlocking()`. Mode CONNECTED (scanner already idle) skips the `read_stop` step.
- **`disconnectBlocking()` fails to stop reader on 3rd+ app-close attempts** — `read_stop` inside `disconnectBlocking()` was retried once (600 ms + 300 ms wait + 600 ms). After two or more sessions with active scanning the firmware's STM32 serial-receive task accumulates a larger TX backlog of `tag_detected` frames; FreeRTOS does not reschedule the receive task within the 1 500 ms window, so both attempts time out and the RF module stays active. Increased to 3 attempts with 400 ms gaps between each (3 × 600 ms + 2 × 400 ms = 2 600 ms worst-case for `read_stop` alone; total `disconnectBlocking()` max ~3 200 ms — well within Android's 5 s ANR limit from `onStop()`).
- **`pauseReading()` times out when called during a tag burst** — `pauseReading()` used a single `sendAndAwaitAck` with no retry. When the firmware's STM32 serial-receive task was saturated by outgoing `tag_detected` frames, the `read_pause` command was delayed in the receive queue past the 600 ms window, firing an error to the listener even though the device was still healthy. Applied the same retry pattern already used by `stopReading()`: suppress the error on the first attempt; if it times out, wait 300 ms (gives FreeRTOS time to drain the tag burst and reschedule the receive task) then send once more with error dispatch enabled.
- **Stale port not cleaned up after write failure (`rc=-1`)** — when `UsbTransport.openDevice()` returned early via the "already connected — skipping" guard (`connected.get() == true`) and the underlying USB connection had been silently invalidated (device reset, re-enumeration, cable wiggle), `serialPort.write()` immediately threw `IOException` with `rc=-1`. The port and `connected` flag were left unchanged, so every subsequent `connect()` attempt hit the same guard and failed identically, making the connection permanently unrecoverable without an app restart. Fixed by calling `closePort()` and `connected.set(false)` inside the `send()` IOException handler before rethrowing `TransportException`. The read loop's `while` condition checks `connected.get()` and exits cleanly within its next poll interval; the next `connect()` call then takes the full fresh-open path.
- **Stale incomplete message permanently blocks `processBuffer()`** — when the firmware sent a JSON fragment truncated mid-transfer (observed: `bufferLen >> 6073` with ACKs arriving in USB_RX bytes but never dispatched to the app), `processBuffer()` waited indefinitely for the closing `}`, blocking all subsequently received complete messages. Fixed by tracking `incompleteHeadSince` timestamp: if the head of the receive buffer remains an open (unclosed) JSON object for ≥ 500 ms the fragment is discarded and the parser seeks the next `{` to resume. The same discard also fires immediately on buffer overflow (> 32 768 chars) regardless of elapsed time. `incompleteHeadSince` is reset to `0` in `stop()` and `onTransportDisconnected()`.

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
