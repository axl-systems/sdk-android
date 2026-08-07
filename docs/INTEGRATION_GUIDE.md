# AXL SDK - POS Integration Guide

SDK version: **26.2.9**

---

## Table of Contents

1. [Setup](#1-setup)
2. [Initialization](#2-initialization)
3. [Connecting via USB](#3-connecting-via-usb)
4. [Connecting via Bluetooth](#4-connecting-via-bluetooth)
5. [Implementing SdkListener](#5-implementing-sdklistener)
6. [Device Config Loaded on Connect](#6-device-config-loaded-on-connect)
7. [Scanning](#7-scanning)
8. [Checkout](#8-checkout)
9. [Updating Device Config](#9-updating-device-config)
10. [Health & Status](#10-health--status)
11. [Disconnecting](#11-disconnecting)
12. [SDK Modes](#12-sdk-modes)
13. [Error Codes](#13-error-codes)
14. [USB Auto-Launch](#14-usb-auto-launch)
15. [Production Diagnostics](#15-production-diagnostics)
16. [Settings Apply Rules](#16-settings-apply-rules)

---

## 1. Setup

### Add the AAR

Copy `axlsdk.aar` into `app/libs/`.

> The SDK depends on `usb-serial-for-android` hosted on JitPack. Add the repository and declare both dependencies as shown below.

**Kotlin DSL**

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

// app/build.gradle.kts
dependencies {
    implementation(files("libs/axlsdk.aar"))
    implementation("com.github.mik3y:usb-serial-for-android:3.9.0")
}
```

**Groovy DSL**

```groovy
// build.gradle (project-level)
allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

// app/build.gradle
dependencies {
    implementation files('libs/axlsdk.aar')
    implementation 'com.github.mik3y:usb-serial-for-android:3.9.0'
}
```

### AndroidManifest.xml

```xml
<uses-feature android:name="android.hardware.usb.host" android:required="false" />

<!-- Bluetooth LE (API 31+) -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<!-- Bluetooth LE (API < 31) -->
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />

<activity android:name=".MainActivity">
    <intent-filter>
        <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
    </intent-filter>
    <meta-data
        android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
        android:resource="@xml/device_filter" />
</activity>
```

`res/xml/device_filter.xml` (Serial Adapter Filter - default VID/PID):

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <usb-device vendor-id="1155" product-id="22336" />
</resources>
```

> VID `1155` = `0x0483` (Embedded controller), PID `22336` = `0x5740` (Serial Adapter Filter).

---

## 2. Initialization

Call `initialize()` once before any other SDK method. `Activity.onCreate()` is the typical place.

```kotlin
val sdk = Sdk.getInstance()
sdk.initialize(context)     // uses sensible defaults
sdk.setListener(this)       // register before connect()
```

### Custom SdkConfig

```kotlin
val config = SdkConfig.Builder()
    .baudRate(115200)              // serial baud rate (default: 115200)
    .commandTimeoutMs(5000)        // ms to wait for device ACK (default: 5000)
    .debugLogging(false)           // true = verbose raw-JSON logs
    .checkoutBatchSize(20)         // EPCs per checkout_complete batch (default: 20)
    .build()

sdk.initialize(context, config)
```

| Option | Default | Notes |
|---|---|---|
| `baudRate` | `115200` | Must match AXL device firmware |
| `commandTimeoutMs` | `5000` | ms before `E003 COMMAND_TIMEOUT` |
| `debugLogging` | `false` | Enable in development; disable in production |
| `checkoutBatchSize` | `20` | EPCs per `checkout_complete`; `0` = no batching |

> `initialize()` is idempotent - duplicate calls are silently ignored.

---

## 3. Connecting via USB

```kotlin
sdk.connect()
```

Internally this:
1. Opens the USB serial transport and asserts DTR (3 s settle)
2. Sends `{"type":"SYS","cmd":"connection_sync"}` wrapped in a PacketFramer frame
3. Waits for `ack_connection_sync` (device identity: `build_version`, `sku`, `device_type`, `usb`)
4. Immediately fetches device configuration via a separate `device_config` command
5. Fires `onConnected()` on success, or `onError()` on failure

> **POS cold-boot:** POS's USB controller cuts VBUS when the serial port is closed, cold-booting the STM32 firmware (19–24 s to read config from flash). `connect()` transparently retries `connection_sync` up to **12 times** covering ~30 s, so the user never needs to unplug and replug the cable.

> **ack_connection_sync change (SDK 26.2.9+):** The handshake response no longer includes `config_data`. Device configuration is now fetched automatically via a separate `device_config` / `ack_device_config` exchange immediately after the handshake. `onDeviceConfigLoaded()` still fires after `onConnected()` — the timing is unchanged from the app's perspective.

After `onConnected()` the SDK also fires, in order:
- `onDeviceIdentified(DeviceInfo)` — device name, SKU, type, firmware build
- `onDeviceConfigLoaded(JSONObject)` — device's current hardware configuration (from the auto-fetched `device_config`)

```kotlin
override fun onConnected() {
    // USB full access - read, checkout, and config all available
}

override fun onDeviceIdentified(deviceInfo: DeviceInfo) {
    val sku   = deviceInfo.sku           // e.g. "A120IAB"
    val type  = deviceInfo.deviceType    // e.g. "AXL_FLAT" (internal type code)
    val build = deviceInfo.buildVersion  // e.g. "26.2.3"; empty string on older hardware

    // For a human-readable USB label use sdk.getConnectedDeviceName() — this returns
    // the iProduct descriptor string from the USB hardware (e.g. "AXL Flat RFID Reader").
    // Fall back to deviceType when the USB name is unavailable.
    val usbLabel = sdk.getConnectedDeviceName()?.takeIf { it.isNotBlank() } ?: type
}

override fun onDeviceConfigLoaded(config: JSONObject) {
    // Pre-populate settings UI with live device values
    val region = config.optString("region")
    val ssid   = config.optJSONObject("network_settings")
                       ?.optJSONObject("wifi")?.optString("ssid")
}
```

---

## 4. Connecting via Bluetooth

> **Important:** Bluetooth connectivity is for **configuration updates only**. When the AXL device has an active USB host (another tablet connected via USB cable), the BLE-connected tablet operates in **config-only mode** - reading (RFID, Barcode, NFC) and checkout are blocked. Only `sendDeviceConfig()` is permitted.

### Pairing

Before connecting via BLE, the AXL device must be paired in Android Bluetooth Settings. Press and hold the button on the AXL device until it appears in the Bluetooth scan list, then pair it. AXL devices are identifiable by the `AXL` prefix in the device name.

### Scanning for devices

```kotlin
// Get already-paired AXL devices instantly (no scan needed)
val paired: List<BleDeviceInfo> = sdk.getBondedBleDevices()

// Or scan for nearby devices
sdk.startBleScan()
// â†’ onBleDeviceFound(device) fires for each device found
// â†’ onBleScanComplete(devices) fires when scan ends

sdk.stopBleScan()   // stop scan early
```

### Connecting

```kotlin
// Connect to a device by MAC address
sdk.connectBle("AA:BB:CC:DD:EE:FF")

// The SDK reconfigures to BLE transport internally, then calls connect()
// Fires the same onConnected() / onDeviceIdentified() / onDeviceConfigLoaded() callbacks as USB
```

### USB lock state

After `onConnected()` fires over BLE, check whether the device already has a USB host active:

```kotlin
override fun onConnected() {
    if (sdk.isUsbLockedByRemote) {
        // Device is in use via USB - config updates only
        showConfigOnlyMode()
    }
}

override fun onUsbLocked() {
    // A USB host connected to the device while BLE was active
    showConfigOnlyMode()
}

override fun onUsbUnlocked() {
    // USB host disconnected - full access restored
    showFullAccessMode()
}
```

### What changes in config-only mode

| Command | USB | BLE (no USB) | BLE (USB active) |
|---|---|---|---|
| `startReading()` | - | - | X blocked |
| `pauseReading()` | - | - | X blocked |
| `stopReading()` | - | - | X blocked |
| `checkoutCompleted()` | - | - | X blocked |
| `startBarcodeReading()` | - | - | X blocked |
| `startNfcReading()` | - | - | X blocked |
| `sendDeviceConfig()` | - | - | - allowed |

Blocked commands dispatch `onError("Device locked by USB host - config updates only")`.

---

## 5. Implementing SdkListener

Register with `sdk.setListener(listener)`. All callbacks are delivered on the **main (UI) thread**.

```kotlin
class MainActivity : AppCompatActivity(), SdkListener {

    // ── Connection ────────────────────────────────────────────────────────────

    override fun onConnected() { /* transport ready - safe to call commands */ }

    override fun onDisconnected() { /* transport dropped or disconnect() called */ }

    override fun onDeviceIdentified(deviceInfo: DeviceInfo) {
        // deviceInfo.deviceName  — human-readable name (falls back to deviceType if absent)
        // deviceInfo.deviceType  — internal type code, e.g. "AXL_FLAT"
        // deviceInfo.sku         — SKU string, e.g. "A120IAB"
        // deviceInfo.buildVersion — firmware build, e.g. "26.2.3"; empty on older hardware
    }

    override fun onDeviceConfigLoaded(config: JSONObject) {
        // Device's current hardware config - use to pre-populate Settings dialog
    }

    // ── USB lock (BLE transport only) ────────────────────────────────────────â”€

    override fun onUsbLocked() {
        // Device has an active USB host - BLE is config-only
    }

    override fun onUsbUnlocked() {
        // USB host disconnected - full BLE access restored
    }

    // ── RFID ──────────────────────────────────────────────────────────────────

    override fun onTagDetected(epc: String) {
        // Called many times per second during active scanning
    }

    override fun onModuleTemperatureReceived(tempCelsius: Int) {
        // Optional — fires once per tag_detected batch when firmware reports module_temp.
        // Firmware >= 26.2.3 includes this field. Old hardware never invokes this callback.
    }

    override fun onCommandAcknowledged(cmd: String) {
        // cmd = "read_start" | "read_pause" | "read_stop" | "checkout_complete" | etc.
    }

    override fun onReadingPaused() { /* device acknowledged pauseReading() */ }

    override fun onReadingStopped() { /* device acknowledged stopReading() */ }

    override fun onCheckoutConfirmed(transactionNo: String) {
        // All checkout batches ACK'd - transaction complete
    }

    override fun onConfigUpdated() { /* device acknowledged sendDeviceConfig() */ }

    override fun onReaderStatusReceived(isActive: Boolean) { /* response to getReadingStatus() */ }

    override fun onHealthInfoReceived(data: JSONObject) {
        val temp = data.optInt("module_temperature", -1)
    }

    override fun onDeviceLogReceived(level: String, message: String, timestamp: String) {
        // Real-time log from device firmware
    }

    // ── Barcode ──────────────────────────────────────────────────────────────â”€

    override fun onBarcodeTagDetected(data: String) { /* scanned barcode string */ }

    override fun onBarcodeReadingStopped() { }

    // ── NFC ──────────────────────────────────────────────────────────────────â”€

    override fun onNfcTagDetected(uid: String) { /* NFC tag UID */ }

    override fun onNfcRawDataReceived(uid: String, tech: String, rawData: org.json.JSONObject) {
        // Optional — only fires on new firmware that sends tech/raw_data in card_detected.
        // rawData keys: "nfca", "isodep", etc. Use rawData.optJSONObject("nfca").
        // Old hardware never invokes this callback.
    }

    override fun onNfcReadingStopped() { }

    // ── BLE scan ──────────────────────────────────────────────────────────────

    override fun onBleDeviceFound(device: BleDeviceInfo) {
        // device.name, .address, .rssi, .bonded
        // Only called during startBleScan()
    }

    override fun onBleScanComplete(devices: List<BleDeviceInfo>) {
        // Scan ended - full result list
    }

    // ── Errors ────────────────────────────────────────────────────────────────

    override fun onError(error: String) {
        // Always starts with [Exxx], e.g. "[E003] COMMAND_TIMEOUT: ..."
    }
}
```

All methods except `onConnected`, `onDisconnected`, `onCommandAcknowledged`, `onTagDetected`, and `onError` have default no-op implementations - override only what you need.

---

## 6. Device Config Loaded on Connect

On every successful connection the SDK fires `onDeviceConfigLoaded(JSONObject)` shortly after `onConnected()`. The config is fetched via an automatic `device_config` / `ack_device_config` exchange that follows the `connection_sync` handshake — it is no longer bundled inside `ack_connection_sync` . Use this to pre-populate your Settings dialog so the operator always sees the actual device state.

You can also fetch fresh config at any time with:
```kotlin
sdk.getDeviceConfig()   // → fires onDeviceConfigLoaded(JSONObject)
```

Example config JSON:
```json
{
  "region": "NA",
  "protocol": "GEN2",
  "read_on_frequency": 500,
  "read_off_frequency": 500,
  "antenna": {
    "count": 4,
    "items": [
      {"id": 1, "active": true,  "read_power": 2400, "write_power": 0},
      {"id": 2, "active": false, "read_power": 2400, "write_power": 0},
      {"id": 3, "active": false, "read_power": 2400, "write_power": 0},
      {"id": 4, "active": false, "read_power": 2400, "write_power": 0}
    ]
  },
  "network_settings": {
    "lan": false,
    "wifi": {"ssid": "MyNetwork", "password": "secret", "security": "WPA2", "status": true}
  },
  "hop_time": 200,
  "hop_frequency": [915250]
}
```

Use this to pre-populate your Settings dialog so the operator always sees the actual device state - not cached/default values.

---

## 7. Scanning

> **BLE restriction:** Scanning commands are blocked when `sdk.isUsbLockedByRemote` is `true`.

```kotlin
sdk.startReading()            // â†’ onCommandAcknowledged("read_start") + onTagDetected() stream
sdk.pauseReading()            // â†’ onCommandAcknowledged("read_pause") + onReadingPaused()
sdk.stopReading(collectedEpcs) // â†’ onCommandAcknowledged("read_stop") + onReadingStopped()
```

`onTagDetected(epc)` fires for every EPC detected. It can fire hundreds of times per second. Deduplicate in your own layer:

```kotlin
override fun onTagDetected(epc: String) {
    if (scannedEpcs.add(epc)) {   // LinkedHashSet for ordered dedup
        // process new tag
    }
}
```

---

## 8. Checkout

> **BLE restriction:** Checkout is blocked when `sdk.isUsbLockedByRemote` is `true`.

Call after the operator confirms the transaction. The SDK automatically batches the EPC list and sends multiple `checkout_complete` commands sequentially if needed.

```kotlin
sdk.checkoutCompleted(transactionNo = "#TX847263", epcs = collectedEpcs)
// â†’ onCommandAcknowledged("checkout_complete") fires per batch
// â†’ onCheckoutConfirmed("#TX847263") fires once after ALL batches complete
```

**Batching** protects the STM device from memory pressure on large reads. The default batch size is **20 EPCs per command** (configurable via `SdkConfig.Builder().checkoutBatchSize(n)`).

Example - 45 EPCs, batch size 20:
```
Batch 1/3 → onCommandAcknowledged("checkout_complete") ✓
Batch 2/3 → onCommandAcknowledged("checkout_complete") ✓
Batch 3/3 → onCommandAcknowledged("checkout_complete") ✓
â†’ onCheckoutConfirmed("#TX847263")  â† fires once
```

If any batch times out, `onError(E003)` fires and remaining batches are cancelled.

Validation:
- `transactionNo` must be non-null and non-empty â†’ `onError(E007)` otherwise
- `epcs` must be non-empty â†’ `onError(E007)` otherwise

---

## 9. Updating Device Config

Push hardware settings to the device at runtime. **Available over both USB and BLE.**

```kotlin
val config = RfidDeviceConfig.Builder()
    .region("ID")                        // RFID region code
    .protocol("GEN2")
    .antenna(1, true,  2400)             // (port, active, readPower mdBm)
    .antenna(2, false, 2400)
    .antenna(3, false, 2400)
    .antenna(4, false, 2400)
    .readOnFrequency(500)                // ms; accepted: 0, 500, 1000
    .readOffFrequency(500)
    .hopTime(200)
    .hopFrequencyKhz("915250")
    .networkWifi("MySSID", "WPA2", "password")   // or .networkLan()
    .build()

sdk.sendDeviceConfig(config)
// â†’ fires onConfigUpdated() on success
```

### Network options

```kotlin
// LAN
.networkLan()

// WiFi
.networkWifi(
    ssid     = "MyNetwork",
    security = "WPA2",        // "WPA2", "WPA3", "None", or ""
    password = "secret"
)
```

When `network_settings.wifi.status = true` and `lan = false` â†’ device uses WiFi.  
When `lan = true` â†’ device uses LAN; WiFi is disabled.

### Region codes

`NA`, `IN`, `JP3`, `PRC`, `EU3`, `EU4`, `KR2`, `AU`, `NZ`, `IS`, `MY`, `ID`, `PH`, `TW`, `RU`, `SG`, `VN`, `TH`, `HK`, `open`

---

## 10. Health & Status

```kotlin
sdk.getHealthInfo()       // → onHealthInfoReceived(JSONObject)
sdk.getReadingStatus()    // → onReaderStatusReceived(Boolean)
sdk.getDeviceConfig()     // → onDeviceConfigLoaded(JSONObject)  — fetch live device config on demand
```

`onHealthInfoReceived` delivers:

| Field | Type | Notes |
|---|---|---|
| `module_temperature` | Int | RFID module temperature in °C — always present |
| `sd_total_mb` | Int | SD card total capacity in MB — new firmware only; absent on old hardware |
| `sd_used_mb` | Int | SD card used space in MB — new firmware only |
| `sd_free_mb` | Int | SD card free space in MB — new firmware only |

SD card fields are optional. Use `data.optInt("sd_total_mb", -1)` — a value of `-1` means the field was not present in the payload.

---

## 11. Disconnecting

### Normal disconnect (user-initiated)

```kotlin
sdk.disconnect()
```

Asynchronous — submits work to an internal executor and returns immediately. `onDisconnected()` fires on the main thread when complete. If the device is unreachable the disconnect still proceeds. Use this for all normal flows (button tap, settings change, etc.).

> **Disconnect while scanning:** if `disconnect()` is called when the reader is active (mode=SCANNING), the SDK automatically sends `read_stop` (up to 3 attempts, 400 ms gaps) before `disconnect_sync`. Skipping this step buries `disconnect_sync` in the firmware's tag-burst TX queue and causes an E003 timeout. No app-side change is needed — the SDK handles it.

### App close / swipe from recents — `disconnectBlocking()`

`Activity.onDestroy()` is not guaranteed to run when the user swipes the app from the recents screen — Android can kill the process first. Use a `Service` with `android:stopWithTask="false"` to receive `onTaskRemoved()` reliably, and call `disconnectBlocking()` inside it:

```kotlin
// SdkCleanupService.kt
class SdkCleanupService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        // disconnectBlocking() runs disconnect_sync on THIS thread — no executor.
        // onTaskRemoved() does not return until the USB write completes, so the
        // process stays alive long enough for the firmware to receive the command.
        try { Sdk.getInstance().disconnectBlocking() } catch (e: Exception) { }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }
}
```

Register in `AndroidManifest.xml`:

```xml
<service
    android:name=".SdkCleanupService"
    android:exported="false"
    android:stopWithTask="false" />
```

Arm / disarm the service from your `SdkListener`:

```kotlin
override fun onConnected() {
    startService(Intent(this, SdkCleanupService::class.java))   // arm
}

override fun onDisconnected() {
    stopService(Intent(this, SdkCleanupService::class.java))    // disarm — already disconnected
}
```

> **Why `disconnectBlocking()` and not `disconnect()` + sleep?**
> `disconnect()` posts work to `connectExecutor` and returns in ~0 ms. Android kills the process after `onTaskRemoved()` returns before the executor runs. `disconnectBlocking()` sends `read_stop` (up to 3 attempts, 400 ms gaps) then `disconnect_sync` synchronously on the calling thread — the method cannot return until the USB writes complete. Does not fire `SdkListener` callbacks.

> **Why `onStop(isFinishing=true)` and not `onDestroy()`?**
> `onStop()` runs while the USB port is guaranteed open. By the time `onDestroy()` runs, Android may have already released the USB port at the kernel level, making writes silently fail. `isFinishing` is `false` on a Home press so scanning is not interrupted by normal backgrounding — only swipe-from-recents and Back trigger the disconnect.

> **Why the `SdkCleanupService` on top of `onStop()`?**
> On some OEM devices `onStop()` does not receive `isFinishing=true` on task removal. `SdkCleanupService.onTaskRemoved()` provides a second guarantee via a path that is independent of Activity lifecycle.

### releasePort()

Call `sdk.releasePort()` after `disconnectBlocking()` in `onStop()`:

```kotlin
override fun onStop() {
    super.onStop()
    if (isFinishing) {
        sdk.takeIf { it.isConnected }?.disconnectBlocking()
        sdk.releasePort()
    }
}
```

The SDK's keep-alive optimisation intentionally leaves the USB port open between same-session reconnects (avoids a 3 s DTR settle penalty on each reconnect). Without an explicit `releasePort()` call when the app closes, a second app opening afterward will race against the lingering `UsbDeviceConnection` and get a `COMMAND_TIMEOUT` on `connection_sync`. `releasePort()` is a no-op for BLE/WiFi transports.

### Switching transport at runtime

```kotlin
sdk.reconfigure(newConfig)   // tears down existing connection
sdk.connect()                // then reconnect
```

Or use the convenience method for BLE:

```kotlin
sdk.connectBle("AA:BB:CC:DD:EE:FF")   // reconfigure + connect in one call
```

---

## 12. SDK Modes

Query with `sdk.currentMode` at any time.

| Mode | Meaning |
|---|---|
| `IDLE` | Initialized, not connected |
| `CONNECTED` | Transport open, handshake complete - ready for commands |
| `SCANNING` | `startReading()` sent, device streaming EPCs |
| `PAUSED` | `pauseReading()` sent, device connected but not streaming |
| `CHECKOUT_PENDING` | `checkoutCompleted()` sent, waiting for all batch ACKs |
| `DISCONNECTED` | Session cleanly closed |

---

## 13. Error Codes

All errors arrive via `onError(String error)`. The string always starts with `[Exxx]`.

| Code | Name | Cause |
|---|---|---|
| `E001` | `DEVICE_NOT_CONNECTED` | Command called before `connect()` succeeds |
| `E002` | `USB_PERMISSION_DENIED` | User denied the USB permission dialog |
| `E003` | `COMMAND_TIMEOUT` | Device did not ACK within `commandTimeoutMs` |
| `E004` | `INVALID_JSON` | Device sent a message that is not valid JSON |
| `E005` | `UNSUPPORTED_COMMAND` | Device sent an unrecognized `cmd` value |
| `E006` | `SDK_NOT_INITIALIZED` | `initialize()` was never called |
| `E007` | `INVALID_PAYLOAD` | Required field missing (e.g. empty EPC list) |

---

## 14. USB Auto-Launch

With the manifest setup from Â§1, Android automatically launches the POS app when the AXL device is plugged in via USB. The `USB_DEVICE_ATTACHED` intent is delivered to `MainActivity` and a USB permission dialog is shown automatically.

To check current connection state on resume:

```kotlin
override fun onResume() {
    super.onResume()
    if (!sdk.isConnected) {
        sdk.connect()
    }
}
```

---

## 15. Production Diagnostics

The SDK has a built-in logging system with three channels. Understanding which channel is useful when lets you diagnose issues without physical access to the device.

> **Imports** — `Logger` is an ambiguous name (Android Studio will also suggest `java.util.logging.Logger`). Always use the SDK's class explicitly:
> ```kotlin
> import com.axlsystem.axlsdk.util.Logger
> import com.axlsystem.axlsdk.util.LogLevel   // needed for LogLevel.WARN in Pattern 2
> ```

### How SDK logs work

| Channel | What it does | Available in production? |
|---|---|---|
| **Logcat** (`adb logcat`) | All SDK logs tagged `Sdk/<Component>` | No — requires USB + ADB |
| **In-memory ring buffer** | Last 200 log entries, survives while process is alive | Yes |
| **LogListener callback** | Real-time event hook — forward logs anywhere you want | Yes, if wired up |
| **`onDeviceLogReceived`** | Firmware-side events (USB RX, RFID batch, ALERT, ERROR) | Yes, if wired up |

By default, only `INFO`, `WARN`, and `ERROR` entries are captured. `DEBUG` and `VERBOSE` (including raw JSON frames) are suppressed unless debug mode is enabled.

### Pattern 1 — Dump on error (minimal effort)

Wire this up in production and you automatically capture the last 200 SDK events leading up to any failure:

```kotlin
override fun onError(errorMessage: String) {
    val report = Logger.getDiagnosticReport()
    // Write to a file, upload to your support server, or send via email
    writeToSupportFile(report)
}
```

`getDiagnosticReport()` returns a formatted, human-readable string with timestamps, thread names, severity levels, and full exception stack traces. No tooling needed — paste it into a support ticket.

### Pattern 2 — Forward WARN/ERROR to a file (recommended for production)

```kotlin
// In Application.onCreate() or Activity.onCreate(), before sdk.connect()
Logger.setLogListener { entry ->
    if (entry.level.priority >= LogLevel.WARN.priority) {
        appendToSupportLog(entry.toString())   // your own file-write helper
    }
}

// In Activity.onDestroy()
Logger.clearLogListener()
```

This writes only warnings and errors continuously, keeping the file small. When a store calls support, they use a "Send Logs" button or ADB pull to retrieve the file.

### Pattern 3 — Full verbose logging during a support session

If a store is having intermittent issues and you need deep visibility remotely:

```kotlin
Logger.setDebugEnabled(true)   // enables VERBOSE + DEBUG + raw JSON frames
```

Toggle this via a hidden settings flag, a remote config value, or a support-mode screen. Disable it after the session — verbose logging generates significant output.

### Device-side events via `onDeviceLogReceived`

The STM32 firmware pushes its own log events upstream. These are separate from SDK-internal logs and cover things the SDK itself cannot see (USB receive chunks, firmware alerts, RFID batch summaries):

```kotlin
override fun onDeviceLogReceived(level: String, message: String, timestamp: String) {
    // level: "USB_RX", "RFID", "ALERT", "ERROR"
    appendToSupportLog("DEVICE [$level] $message")
}
```

### Summary — what to ship in a production integration

```kotlin
// 1. Dump on any SDK error
override fun onError(errorMessage: String) {
    val report = Logger.getDiagnosticReport()
    writeToSupportFile(report)
}

// 2. Forward warnings to a rolling log file
Logger.setLogListener { entry ->
    if (entry.level.priority >= LogLevel.WARN.priority) {
        appendToSupportLog(entry.toString())
    }
}

// 3. Capture device-side events
override fun onDeviceLogReceived(level: String, message: String, timestamp: String) {
    appendToSupportLog("DEVICE [$level] $message")
}
```

With these three in place, any field failure produces a file you can retrieve without physical access to the device.

---

## 16. Settings Apply Rules

| Setting | How to apply | Transport |
|---|---|---|
| **Baudrate** | `sdk.reconfigure(newConfig)` then `sdk.connect()` - cannot change on live connection | USB only |
| **Region, Protocol, Antennas, Read Power** | `sdk.sendDeviceConfig(config)` - safe while `CONNECTED` or `PAUSED` | USB + BLE |
| **WiFi / LAN** | Included in `sendDeviceConfig(config)` via `.networkWifi()` or `.networkLan()` | USB + BLE |
| **Checkout Batch Size** | Set in `SdkConfig.Builder().checkoutBatchSize(n)` before `initialize()`, or via Settings dialog | SDK only |

> When `SCANNING` is active, defer `sendDeviceConfig()` until `stopReading()` completes.

