# axl SDK

Android SDK for integrating AXL RFID POS devices over USB.

**Version:** 26.2.1 &nbsp;·&nbsp; **Min SDK:** Android 8.0 (API 26) &nbsp;·&nbsp; **Language:** Java 11 &nbsp;·&nbsp; **License:** Apache 2.0

---

## Overview

axl SDK provides a clean Android API to communicate with AXL RFID hardware over USB. It handles the USB connection lifecycle, device handshake protocol, RFID tag scanning, barcode reading, NFC reading, device configuration, and checkout transactions — so your app only needs to respond to events.

---

## Requirements

- Android 8.0+ (API 26)
- USB OTG support on the Android device
- AXL RFID hardware (AE03A001 / AXL FLAT, AXL BIN, or AXL GATE)

---

## Installation

### Option A — Local AAR

1. Copy `axlsdk.aar` into `app/libs/`.
2. In `app/build.gradle`:

```groovy
dependencies {
    implementation fileTree(dir: 'libs', include: ['*.aar'])
    implementation 'com.github.mik3y:usb-serial-for-android:3.8.1'
}
```

### Option B — Module reference (monorepo)

```groovy
// settings.gradle
include ':axlsdk'

// app/build.gradle
dependencies {
    implementation project(':axlsdk')
}
```

---

## Permissions

`AndroidManifest.xml`:

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

`res/xml/device_filter.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <usb-device vendor-id="1155" product-id="22336" />
</resources>
```

---

## Quick Start

```java
// 1. Initialize (once, e.g. in Activity.onCreate)
Sdk sdk = Sdk.getInstance();
sdk.initialize(context);

// 2. Register listener
sdk.setListener(new SdkListener() {

    @Override
    public void onConnected() {
        // Device ready — enable your UI
    }

    @Override
    public void onDeviceIdentified(DeviceInfo info) {
        Log.i(TAG, "Connected: " + info.getDeviceName());
    }

    @Override
    public void onTagDetected(String epc, int antenna) {
        // Add EPC to your list
    }

    @Override
    public void onCommandAcknowledged(String cmd) {
        Log.i(TAG, "ACK: " + cmd);
    }

    @Override
    public void onDisconnected() {
        // Update UI to disconnected state
    }

    @Override
    public void onError(String error) {
        Log.e(TAG, "Error: " + error);
    }
});

// 3. Connect
sdk.connect();

// 4. Scan
sdk.startReading();
sdk.pauseReading();
sdk.stopReading(collectedEpcs);

// 5. Checkout
sdk.checkoutCompleted("#TX001", collectedEpcs);

// 6. Disconnect
sdk.disconnect();
```

---

## API Reference

### `Sdk` — Main entry point (singleton)

**Lifecycle**

| Method | Description |
|---|---|
| `initialize(context)` | Initialize with default configuration |
| `initialize(context, SdkConfig)` | Initialize with custom configuration |
| `setListener(SdkListener)` | Register event listener (all callbacks on main thread) |
| `connect()` | Open USB connection and perform device handshake |
| `disconnect()` | Gracefully close the connection |

**State**

| Method | Returns |
|---|---|
| `isInitialized()` | `true` after `initialize()` |
| `isConnected()` | `true` when device handshake is complete |
| `getCurrentMode()` | Current `SdkMode` enum value |
| `getDeviceInfo()` | Device SKU and display name |
| `getConnectedAntennas()` | List of detected antenna port numbers |
| `getConnectedDeviceName()` | Human-readable USB device name |

**Diagnostics**

| Method | Description |
|---|---|
| `setDebugLogging(boolean)` | Toggle verbose logging at runtime |
| `getDiagnosticReport()` | Get formatted log buffer snapshot |

---

### RFID Commands

| Method | Description |
|---|---|
| `startReading()` | Start scanning for RFID tags |
| `pauseReading()` | Pause scanning (collected tags preserved) |
| `stopReading(List<String> epcs)` | Stop scanning and send EPC list to device |
| `checkoutCompleted(txnId, epcs)` | Complete a POS checkout transaction |
| `sendDeviceConfig(RfidDeviceConfig)` | Push device configuration (AE03A001 format) |
| `updateDeviceConfig(RfidDeviceConfig)` | Push device configuration (full format) |
| `getReadingStatus()` | Query whether the reader is currently active |
| `getHealthInfo()` | Request device health diagnostics |

### Barcode Commands

| Method | Description |
|---|---|
| `startBarcodeReading()` | Start barcode scanner |
| `stopBarcodeReading()` | Stop barcode scanner |

### NFC Commands

| Method | Description |
|---|---|
| `startNfcReading()` | Start NFC reader |
| `stopNfcReading()` | Stop NFC reader |

---

## Event Callbacks (`SdkListener`)

All callbacks are dispatched on the **main (UI) thread**.

### Connection

| Callback | When fired |
|---|---|
| `onConnected()` | USB open and device handshake complete — ready for commands |
| `onDeviceIdentified(DeviceInfo)` | Device SKU and display name parsed from handshake |
| `onAntennasDetected(List<Integer>)` | Antenna ports reported by device hardware |
| `onDisconnected()` | Device disconnected (user-initiated or unexpected) |
| `onError(String)` | Any SDK or transport error |

### RFID

| Callback | When fired |
|---|---|
| `onTagDetected(epc, antenna)` | Single EPC detected during active scanning |
| `onCommandAcknowledged(cmd)` | Device acknowledged a sent command |
| `onReadingPaused()` | Scanning paused successfully |
| `onReadingStopped()` | Scanning stopped and EPC list delivered to device |
| `onCheckoutConfirmed(txnId)` | Checkout transaction acknowledged by device |
| `onConfigUpdated()` | Device config update acknowledged |
| `onReaderStatusReceived(boolean)` | Reader active/inactive status response |
| `onHealthInfoReceived(JSONObject)` | Device CPU, memory, and temperature metrics |
| `onDeviceLogReceived(level, msg, ts)` | Log entry streamed from device firmware |

### Barcode

| Callback | When fired |
|---|---|
| `onBarcodeTagDetected(data)` | Barcode string scanned |
| `onBarcodeCommandAcknowledged(cmd)` | Barcode command acknowledged |
| `onBarcodeReadingStopped()` | Barcode reading stopped |

### NFC

| Callback | When fired |
|---|---|
| `onNfcTagDetected(uid, antenna)` | NFC tag UID detected |
| `onNfcCommandAcknowledged(cmd)` | NFC command acknowledged |
| `onNfcReadingStopped()` | NFC reading stopped |

---

## Configuration

### `SdkConfig` — SDK-level settings

```java
SdkConfig config = new SdkConfig.Builder()
    .commandTimeoutMs(5000)   // ACK wait timeout (default: 5000ms)
    .autoReconnect(true)      // Reconnect on unexpected disconnect (default: true)
    .debugLogging(false)      // Verbose SDK logging
    .baudRate(115200)         // Serial baud rate (default: 115200)
    .build();

sdk.initialize(context, config);
```

### `RfidDeviceConfig` — Device hardware settings

```java
RfidDeviceConfig config = new RfidDeviceConfig.Builder()
    .region("ID")
    .protocol("GEN2")
    .readPower(1800)
    .antenna(1, true,  1800)   // (port, active, readPowerMdBm)
    .antenna(2, true,  1800)
    .antenna(3, false, 1800)
    .antenna(4, false, 1800)
    .networkLan()              // or .networkWifi(ssid, security, password)
    .hopTime(200)
    .readOnFrequency(500)
    .readOffFrequency(500)
    .hopFrequencyKhz("903250")
    .build();

// AE03A001 devices
sdk.sendDeviceConfig(config);

// Other devices
sdk.updateDeviceConfig(config);
```

---

## Device State Machine

```
IDLE ─── connect() ──────────► CONNECTED
                                   │
                       startReading() ▼
                               SCANNING ◄─────────────┐
                                   │                  │
                       pauseReading() ▼    startReading() │
                               PAUSED  ──────────────►─┘
                                   │
                       stopReading() ▼
                               CONNECTED
                                   │
              checkoutCompleted() ▼
                       CHECKOUT_PENDING ──► CONNECTED
                                   │
                       disconnect() ▼
                            DISCONNECTED
```

---

## Error Codes

| Code | Constant | Description |
|---|---|---|
| E001 | `DEVICE_NOT_CONNECTED` | No device connected |
| E002 | `USB_PERMISSION_DENIED` | User denied USB permission |
| E003 | `COMMAND_TIMEOUT` | No ACK received within timeout window |
| E004 | `INVALID_JSON` | Payload could not be parsed as JSON |
| E005 | `UNSUPPORTED_COMMAND` | Unknown command type received |
| E006 | `SDK_NOT_INITIALIZED` | `initialize()` was not called |
| E007 | `INVALID_PAYLOAD` | Required field missing in payload |

---

## Supported Devices

| Display Name | SKU | Constant |
|---|---|---|
| AXL FLAT | AE03A001 | `DeviceInfo.DEVICE_TYPE_AXL_FLAT` |
| AXL BIN | — | `DeviceInfo.DEVICE_TYPE_AXL_BIN` |
| AXL GATE | — | `DeviceInfo.DEVICE_TYPE_AXL_GATE` |

---

## Build from Source

```bash
cd RFIDSDK
./gradlew clean :axlsdk:assembleRelease
# Output: axlsdk/build/outputs/aar/axlsdk-release.aar
```

Copy to consuming app:

```bash
cp axlsdk/build/outputs/aar/axlsdk-release.aar ../andriod/app/libs/axlsdk.aar
```

---

## Repository Structure

```
RFIDSDK/
├── axlsdk/         ← SDK library source (builds axlsdk-release.aar)
├── sample/         ← Reference POS integration app
├── jitpack.yml     ← JitPack build config
└── LICENSE
```

---

## Version

```java
Log.i(TAG, "axl SDK " + SdkVersion.NAME);  // "26.2.1"

if (SdkVersion.CODE >= 260201) {
    // features added in 26.2.1
}
```

---

## License

Copyright 2024 AXL System.  
Licensed under the [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt).
