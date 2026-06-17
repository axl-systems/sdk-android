# axl SDK — Integration Guide

> **SDK version:** 26.2.1 · **Min Android SDK:** 26 (Android 8.0) · **Language:** Java / Kotlin

---

## Table of Contents

1. [Setup](#1-setup)
2. [Initialization](#2-initialization)
3. [Connecting to the Device](#3-connecting-to-the-device)
4. [Implementing SdkListener](#4-implementing-sdklistener)
5. [RFID Scanning](#5-rfid-scanning)
6. [Checkout](#6-checkout)
7. [Device Configuration](#7-device-configuration)
8. [Barcode Reading](#8-barcode-reading)
9. [NFC Reading](#9-nfc-reading)
10. [Health & Status](#10-health--status)
11. [Disconnecting](#11-disconnecting)
12. [SDK Modes](#12-sdk-modes)
13. [Error Codes](#13-error-codes)
14. [USB Auto-Launch](#14-usb-auto-launch)
15. [Settings Apply Rules](#15-settings-apply-rules)

---

## 1. Setup

### 1.1 Add the SDK to Your Project

**Option A — Local AAR file (recommended for most projects)**

Download `axlsdk.aar` and place it in your project:

```
your-pos-app/
└── app/
    └── libs/
        └── axlsdk.aar
```

In `app/build.gradle`:

```groovy
dependencies {
    implementation fileTree(dir: 'libs', include: ['*.aar'])
    implementation 'com.github.mik3y:usb-serial-for-android:3.8.1'
}
```

> When a new SDK version is released, replace `axlsdk.aar` in `app/libs/` and rebuild.

**Option B — Maven / JitPack**

```groovy
dependencies {
    implementation 'com.axlsystem:axlsdk:26.2.1'
}
```

---

### 1.2 USB Permission

Add to `AndroidManifest.xml`:

```xml
<uses-feature android:name="android.hardware.usb.host" />
<uses-permission android:name="android.permission.USB_PERMISSION" />

<activity android:name=".MainActivity">
    <intent-filter>
        <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
    </intent-filter>
    <meta-data
        android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
        android:resource="@xml/device_filter" />
</activity>
```

Create `res/xml/device_filter.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <usb-device vendor-id="1155" product-id="22336" />
</resources>
```

> `vendor-id="1155"` = `0x0483` (STMicroelectronics), `product-id="22336"` = `0x5740` (STM32 CDC).
> Update these values if your hardware uses different IDs.

---

## 2. Initialization

Call `initialize()` **once**, before any other SDK method. `Application.onCreate()` or `Activity.onCreate()` are both suitable.

**Kotlin**
```kotlin
val sdk = Sdk.getInstance()
sdk.initialize(context)
sdk.setListener(this)    // register before connect()
```

**Java**
```java
Sdk sdk = Sdk.getInstance();
sdk.initialize(context);
sdk.setListener(this);
```

> `initialize()` is idempotent — duplicate calls are silently ignored.

---

### Custom SdkConfig

Use `SdkConfig.Builder` to tune timeouts or enable debug logging:

```kotlin
val config = SdkConfig.Builder()
    .commandTimeoutMs(5000)   // ms to wait for device ACK before error E003
    .autoReconnect(true)      // reconnect automatically on transport drop
    .debugLogging(false)      // true = verbose raw-JSON logs (development only)
    .baudRate(115200)         // must match device firmware
    .build()

sdk.initialize(context, config)
```

| Option | Default | Notes |
|---|---|---|
| `commandTimeoutMs` | `5000` | Increase if the device is slow to respond |
| `autoReconnect` | `true` | Reconnects automatically on USB drop |
| `debugLogging` | `false` | Enable in development, disable in production |
| `baudRate` | `115200` | Must match the RFID device firmware setting |

---

## 3. Connecting to the Device

```kotlin
sdk.connect()
```

This opens the USB transport and performs a `connection_sync` handshake. The result arrives asynchronously via `SdkListener`:

- **Success** → `onConnected()` fires, followed by `onDeviceIdentified(deviceInfo)` and `onAntennasDetected(antennas)`
- **Failure** → `onError(error)` fires with code `E001`, `E002`, or `E003`

```kotlin
override fun onConnected() {
    // Safe to call startReading(), sendDeviceConfig(), etc.
}

override fun onDeviceIdentified(deviceInfo: DeviceInfo) {
    // deviceInfo.deviceName  → e.g. "AXL FLAT"
    // deviceInfo.deviceType  → e.g. "AE03A001"
    Log.i(TAG, "Device: ${deviceInfo.deviceName} (${deviceInfo.deviceType})")
}

override fun onAntennasDetected(antennas: List<Int>) {
    // e.g. [1, 2, 3, 4] — use to populate antenna selection UI
}
```

---

## 4. Implementing SdkListener

Register with `sdk.setListener(listener)`. **All callbacks are delivered on the main (UI) thread** — no `runOnUiThread()` needed.

```kotlin
class MainActivity : AppCompatActivity(), SdkListener {

    // ── Connection ──────────────────────────────────────────────────────────

    override fun onConnected() {
        // Device handshake complete — enable your UI
    }

    override fun onDisconnected() {
        // Transport dropped or disconnect() was called
    }

    override fun onDeviceIdentified(deviceInfo: DeviceInfo) {
        // Fired right after onConnected() — has SKU and display name
    }

    override fun onAntennasDetected(antennas: List<Int>) {
        // Hardware-reported antenna port list
    }

    // ── RFID ────────────────────────────────────────────────────────────────

    override fun onTagDetected(epc: String, antenna: Int) {
        // Fires many times per second during scanning — deduplicate in your layer
    }

    override fun onCommandAcknowledged(cmd: String) {
        // cmd = "read_start" | "read_pause" | "read_stop" |
        //       "checkout_complete" | "config" | "update_config" | etc.
    }

    override fun onReadingPaused() {
        // Device acknowledged pauseReading()
    }

    override fun onReadingStopped() {
        // Device acknowledged stopReading()
    }

    override fun onCheckoutConfirmed(transactionNo: String) {
        // Checkout accepted by device
    }

    override fun onConfigUpdated() {
        // Device acknowledged sendDeviceConfig() or updateDeviceConfig()
    }

    override fun onReaderStatusReceived(isActive: Boolean) {
        // Response to getReadingStatus()
    }

    override fun onHealthInfoReceived(data: JSONObject) {
        val cpu   = data.optDouble("cpu_percent")
        val mem   = data.optDouble("memory_percent")
        val memMb = data.optInt("memory_used_mb")
        val total = data.optInt("memory_total_mb")
        val temp  = data.optString("temperature", "N/A")
    }

    override fun onDeviceLogReceived(level: String, message: String, timestamp: String) {
        // Real-time log stream from device firmware (only when device debug is enabled)
    }

    // ── Barcode ─────────────────────────────────────────────────────────────

    override fun onBarcodeTagDetected(data: String) {
        // A barcode string was scanned
    }

    override fun onBarcodeCommandAcknowledged(cmd: String) {
        // cmd = "read_start" | "read_stop"
    }

    override fun onBarcodeReadingStopped() {
        // Device acknowledged stopBarcodeReading()
    }

    // ── NFC ─────────────────────────────────────────────────────────────────

    override fun onNfcTagDetected(uid: String, antenna: Int) {
        // An NFC tag UID was detected
    }

    override fun onNfcCommandAcknowledged(cmd: String) {
        // cmd = "read_start" | "read_stop"
    }

    override fun onNfcReadingStopped() {
        // Device acknowledged stopNfcReading()
    }

    // ── Errors ───────────────────────────────────────────────────────────────

    override fun onError(error: String) {
        // Format: "[E003] COMMAND_TIMEOUT: Command acknowledgment timed out"
        Log.e(TAG, "SDK error: $error")
    }
}
```

> All methods except `onConnected`, `onDisconnected`, `onTagDetected`, `onCommandAcknowledged`, and `onError` have default no-op implementations — override only those your app uses.

---

## 5. RFID Scanning

### Start and Pause

```kotlin
sdk.startReading()   // device begins streaming tag events → onTagDetected()
sdk.pauseReading()   // device pauses streaming            → onReadingPaused()
sdk.startReading()   // resume from paused state
```

### Stop

```kotlin
sdk.stopReading(epcs)   // send full EPC list to device, return to CONNECTED state
                         // → onReadingStopped()
```

### Tag Deduplication

`onTagDetected()` can fire hundreds of times per second. Always deduplicate in your own layer:

```kotlin
private val scannedEpcs = mutableSetOf<String>()

override fun onTagDetected(epc: String, antenna: Int) {
    if (scannedEpcs.add(epc)) {
        // First time seeing this EPC in this session
        runOnUiThread { updateTagList(epc, antenna) }
    }
}
```

### Stop and Reset for a Fresh Scan

```kotlin
sdk.stopReading(scannedEpcs.toList())
scannedEpcs.clear()
// Next startReading() begins a fresh session
```

---

## 6. Checkout

Call after the operator confirms the transaction. The SDK sends the EPC list and transaction ID to the device and waits for acknowledgment.

```kotlin
sdk.checkoutCompleted(
    transactionNo = "#TX001",
    epcs = scannedEpcs.toList()
)
// → fires onCheckoutConfirmed("#TX001") when the device acknowledges
```

**Validation rules:**
- `transactionNo` must be non-null and non-empty — fires `onError(E007)` otherwise
- `epcs` must be non-empty — fires `onError(E007)` otherwise

The SDK enters `CHECKOUT_PENDING` mode while waiting for the device ACK and returns to `CONNECTED` once confirmed.

---

## 7. Device Configuration

Use `RfidDeviceConfig.Builder` to build the configuration, then send it to the device.

### Which method to use

| Device | Method |
|---|---|
| **(AXL FLAT)** | `sdk.sendDeviceConfig(config)` — sends a lean `config` command |
| **All other devices** | `sdk.updateDeviceConfig(config)` — sends the full `update_config` command |

Both fire `onConfigUpdated()` on success.

---

### Building the Configuration

```kotlin
val config = RfidDeviceConfig.Builder()
    .region("ID")                    // RFID frequency region
    .protocol("GEN2")                // Air protocol
    .readPower(1800)                 // Global read power in mdBm
    .antenna(1, true,  1800)         // (port, active, readPowerMdBm)
    .antenna(2, true,  1800)
    .antenna(3, false, 1800)
    .antenna(4, false, 1800)
    .readOnFrequency(500)            // ms: 0, 500, or 1000
    .readOffFrequency(500)           // ms: 0, 500, or 1000
    .hopTime(200)                    // ms
    .hopFrequencyKhz("903250")       // comma-separated kHz
    .networkLan()                    // wired LAN
    .build()

// AE03A001 (AXL FLAT)
sdk.sendDeviceConfig(config)

// Other devices
sdk.updateDeviceConfig(config)
```

### Network Options

```kotlin
// Wired LAN (default)
.networkLan()

// Wi-Fi
.networkWifi(
    ssid     = "MyNetwork",
    security = "WPA2",      // "WPA2", "WPA3", "None", or ""
    password = "secret"
)
```

### Supported Region Codes

`NA` `IN` `JP3` `PRC` `EU3` `EU4` `KR2` `AU` `NZ` `IS` `MY` `ID` `PH` `TW` `RU` `SG` `VN` `TH` `HK` `open`

### Adding Custom Fields

```kotlin
RfidDeviceConfig.Builder()
    .put("future_setting", true)
    .build()
```

---

## 8. Barcode Reading

```kotlin
sdk.startBarcodeReading()
// → each scan fires onBarcodeTagDetected(data)

sdk.stopBarcodeReading()
// → fires onBarcodeReadingStopped()
```

```kotlin
override fun onBarcodeTagDetected(data: String) {
    Log.i(TAG, "Barcode: $data")
}
```

---

## 9. NFC Reading

```kotlin
sdk.startNfcReading()
// → each tap fires onNfcTagDetected(uid, antenna)

sdk.stopNfcReading()
// → fires onNfcReadingStopped()
```

```kotlin
override fun onNfcTagDetected(uid: String, antenna: Int) {
    Log.i(TAG, "NFC UID: $uid on antenna $antenna")
}
```

---

## 10. Health & Status

```kotlin
sdk.getHealthInfo()       // → onHealthInfoReceived(JSONObject)
sdk.getReadingStatus()    // → onReaderStatusReceived(Boolean)
```

Fields in `onHealthInfoReceived`:

| Field | Type | Description |
|---|---|---|
| `cpu_percent` | Double | CPU usage 0–100 |
| `memory_percent` | Double | RAM usage 0–100 |
| `memory_used_mb` | Int | Used RAM in MB |
| `memory_total_mb` | Int | Total RAM in MB |
| `temperature` | String | CPU temperature — may be absent on some hardware |

---

## 11. Disconnecting

```kotlin
sdk.disconnect()
// → onDisconnected() always fires, even if the device is unreachable
```

To change transport settings at runtime, use `reconfigure()` — transport type and baud rate cannot be changed on a live connection:

```kotlin
val newConfig = SdkConfig.Builder()
    .baudRate(9600)
    .build()

sdk.reconfigure(newConfig)  // tears down existing connection
sdk.connect()               // reconnect with new config
```

---

## 12. SDK Modes

Query the current mode at any time with `sdk.currentMode`.

| Mode | Meaning |
|---|---|
| `IDLE` | Initialized, not yet connected |
| `CONNECTED` | Transport open, handshake complete — ready for commands |
| `SCANNING` | `startReading()` acknowledged, device is streaming EPCs |
| `PAUSED` | `pauseReading()` acknowledged, device is idle but connected |
| `CHECKOUT_PENDING` | `checkoutCompleted()` sent, waiting for device ACK |
| `DISCONNECTED` | Session cleanly closed via `disconnect()` |

---

## 13. Error Codes

All errors arrive in `onError(String)`. The string always starts with `[Exxx]`.

| Code | Name | Cause |
|---|---|---|
| `E001` | `DEVICE_NOT_CONNECTED` | A command was called before `connect()` succeeded |
| `E002` | `USB_PERMISSION_DENIED` | User denied the USB permission dialog |
| `E003` | `COMMAND_TIMEOUT` | Device did not ACK within `commandTimeoutMs` |
| `E004` | `INVALID_JSON` | Device sent a message that could not be parsed |
| `E005` | `UNSUPPORTED_COMMAND` | Device sent an unrecognized command |
| `E006` | `SDK_NOT_INITIALIZED` | A method was called before `initialize()` |
| `E007` | `INVALID_PAYLOAD` | Required field missing (e.g. empty EPC list or transaction ID) |

---

## 14. USB Auto-Launch

With the manifest setup from [§1](#1-setup), Android automatically launches the POS app when the RFID device is plugged in and the user selects the app from the prompt.

Handle the intent in `onNewIntent` to avoid double-connect when the app is already running:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    sdk.initialize(this)
    sdk.setListener(this)
    handleUsbIntent(intent)
}

override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleUsbIntent(intent)
}

private fun handleUsbIntent(intent: Intent) {
    if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
        if (!sdk.isConnected) sdk.connect()
    }
}
```

To resync when the app returns to the foreground after being backgrounded:

```kotlin
override fun onResume() {
    super.onResume()
    if (!sdk.isConnected) sdk.connect()
}
```

---

## 15. Settings Apply Rules

Not all settings take effect the same way. Use this table when the user saves settings in your app.

| Setting changed | How to apply |
|---|---|
| **Baud rate** | Call `sdk.reconfigure(newSdkConfig)` then `sdk.connect()` — cannot change on a live connection |
| **Region, Protocol, Antennas, Read Power, Frequencies** | Call `sdk.sendDeviceConfig(config)` (AE03A001) or `sdk.updateDeviceConfig(config)` (other devices) — safe while `CONNECTED` or `PAUSED` |
| **Wi-Fi credentials / LAN toggle** | Pass via `.networkWifi()` or `.networkLan()` inside `sendDeviceConfig()` or `updateDeviceConfig()` |
| **Debug logging** | Call `sdk.setDebugLogging(enabled)` — takes effect immediately, no reconnect needed |

---

## Complete Example

```kotlin
class MainActivity : AppCompatActivity(), SdkListener {

    private val sdk = Sdk.getInstance()
    private val scannedEpcs = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sdk.initialize(this)
        sdk.setListener(this)

        btnConnect.setOnClickListener { sdk.connect() }
        btnStart.setOnClickListener   { sdk.startReading() }
        btnPause.setOnClickListener   { sdk.pauseReading() }
        btnStop.setOnClickListener    {
            sdk.stopReading(scannedEpcs.toList())
            scannedEpcs.clear()
        }
        btnCheckout.setOnClickListener {
            sdk.checkoutCompleted("#TX001", scannedEpcs.toList())
        }
    }

    // Connection
    override fun onConnected()                              { /* enable UI */ }
    override fun onDisconnected()                           { /* reset UI */ }
    override fun onDeviceIdentified(d: DeviceInfo)          { Log.i(TAG, d.deviceName) }
    override fun onAntennasDetected(antennas: List<Int>)    { /* update antenna UI */ }

    // RFID
    override fun onTagDetected(epc: String, antenna: Int) {
        if (scannedEpcs.add(epc)) updateTagList(epc)
    }
    override fun onCommandAcknowledged(cmd: String)         { Log.i(TAG, "ACK: $cmd") }
    override fun onReadingPaused()                          { /* update button state */ }
    override fun onReadingStopped()                         { /* update button state */ }
    override fun onCheckoutConfirmed(txnNo: String)         { showSuccess(txnNo) }
    override fun onConfigUpdated()                          { Log.i(TAG, "Config applied") }

    // Errors
    override fun onError(error: String)                     { Log.e(TAG, error) }
}
```

---

## Version Check

```kotlin
Log.i(TAG, "axl SDK ${SdkVersion.NAME}")   // "26.2.1"

if (SdkVersion.CODE >= 260201) {
    // features added in 26.2.1
}
```
