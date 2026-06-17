# axl SDK

Android SDK for integrating AXL RFID POS devices over USB or Bluetooth.

**Version:** 26.2.4 &nbsp;·&nbsp; **Min SDK:** Android 5.0 (API 21) &nbsp;·&nbsp; **Language:** Java 11 &nbsp;·&nbsp; **License:** Apache 2.0

---

## Overview

axl SDK provides a clean Android API to communicate with AXL RFID hardware over **USB** or **Bluetooth LE**. It handles the connection lifecycle, device handshake protocol, RFID tag scanning, barcode reading, NFC reading, device configuration, and checkout transactions — so your app only needs to respond to events.

> **Bluetooth note:** When connected via Bluetooth, the SDK operates in **configuration-only mode**. Reading (RFID, Barcode, NFC) and checkout commands are blocked. Only `sendDeviceConfig()` is permitted over BLE. This is enforced automatically when the device reports an active USB host connection.

---

## Requirements

- Android 5.0+ (API 21)
- USB OTG support on the Android device
- AXL RFID hardware (AXL FLAT, AXL BIN, or AXL GATE)
- Bluetooth 4.0+ for BLE connectivity (optional)

---

## Installation

Copy `axlsdk.aar` into `app/libs/`.

Download `axlsdk.aar` from the [latest release](https://github.com/axl-systems/sdk-android/releases/latest)
and copy it into `app/libs/`.


> The SDK depends on `usb-serial-for-android` which is hosted on JitPack. Add the JitPack repository and declare both dependencies as shown below.

### Kotlin DSL

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

### Groovy DSL

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

---

## Permissions

`AndroidManifest.xml`:

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

`res/xml/device_filter.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- FTDI FT232R  VID=0x0403  PID=0x6001 -->
    <usb-device vendor-id="1027"  product-id="24577" />
    <!-- FTDI FT2232H VID=0x0403  PID=0x6010 -->
    <usb-device vendor-id="1027"  product-id="24592" />
    <!-- FTDI FT4232H VID=0x0403  PID=0x6011 -->
    <usb-device vendor-id="1027"  product-id="24593" />
    <!-- STM32 USB CDC VID=0x0483  PID=0x5740 -->
    <usb-device vendor-id="1155"  product-id="22336" />
    <!-- STM32 USB CDC VID=0x0483  PID=0x5741 (alt PID) -->
    <usb-device vendor-id="1155"  product-id="22337" />
    <!-- Silicon Labs CP210x VID=0x10C4  PID=0xEA60 -->
    <usb-device vendor-id="4292"  product-id="60000" />
    <!-- Prolific PL2303 VID=0x067B  PID=0x2303 -->
    <usb-device vendor-id="1659"  product-id="8963"  />
    <!-- CH340 / CH341  VID=0x1A86  PID=0x7523 -->
    <usb-device vendor-id="6790"  product-id="29987" />
    <!-- CH9102 (CH343) VID=0x1A86  PID=0x55D4 -->
    <usb-device vendor-id="6790"  product-id="21972" />
</resources>
```

---

## Quick Start

### USB

```java
Sdk sdk = Sdk.getInstance();
sdk.initialize(context);
sdk.setListener(listener);
sdk.connect();             // USB — full access (read, checkout, config)
```

### Bluetooth (config updates only)

```java
// 1. Scan for nearby AXL devices
sdk.startBleScan();        // fires onBleDeviceFound() for each device found

// 2. Connect to selected device
sdk.connectBle("AA:BB:CC:DD:EE:FF");   // fires onConnected() after handshake

// 3. Only sendDeviceConfig() is permitted over BLE
sdk.sendDeviceConfig(config);

// 4. Disconnect
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
| `connect()` | Open transport connection and perform device handshake |
| `disconnect()` | Gracefully close the connection |
| `reconfigure(SdkConfig)` | Change transport at runtime (disconnects first) |

**State**

| Method | Returns |
|---|---|
| `isInitialized()` | `true` after `initialize()` |
| `isConnected()` | `true` when device handshake is complete |
| `getCurrentMode()` | Current `SdkMode` enum value |
| `getDeviceInfo()` | Device SKU, type and display name from handshake |
| `getConnectedAntennas()` | List of detected antenna port numbers |
| `getConnectedDeviceName()` | Human-readable USB device name |
| `isBluetoothTransport()` | `true` when active transport is BLE |
| `isUsbLockedByRemote()` | `true` when BLE-connected and device reports USB host active |

**Diagnostics**

| Method | Description |
|---|---|
| `setDebugLogging(boolean)` | Toggle verbose logging at runtime |
| `getDiagnosticReport()` | Get formatted log buffer snapshot |

---

### RFID Commands

> **BLE restriction:** All read and checkout commands below are blocked when `isUsbLockedByRemote()` is true. Only `sendDeviceConfig()` is permitted over BLE when a USB host is active on the device.

| Method | Description |
|---|---|
| `startReading()` | Start scanning for RFID tags |
| `pauseReading()` | Pause scanning (collected tags preserved) |
| `stopReading(List<String> epcs)` | Stop scanning and send EPC list to device |
| `checkoutCompleted(txnId, epcs)` | Complete a POS checkout transaction (auto-batched) |
| `sendDeviceConfig(RfidDeviceConfig)` | Push device configuration — **allowed over BLE** |
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

### Bluetooth Commands

| Method | Description |
|---|---|
| `connectBle(macAddress)` | Connect to a BLE device by MAC address |
| `startBleScan()` | Scan for nearby BLE devices (fires `onBleDeviceFound` per device) |
| `stopBleScan()` | Stop the active BLE scan |
| `getBondedBleDevices()` | Return already-paired BLE devices immediately (no scan needed) |

---

## Event Callbacks (`SdkListener`)

All callbacks are dispatched on the **main (UI) thread**.

### Connection

| Callback | When fired |
|---|---|
| `onConnected()` | Transport connected and device handshake complete |
| `onDeviceIdentified(DeviceInfo)` | Device SKU, type and display name from handshake |
| `onAntennasDetected(List<Integer>)` | Antenna ports reported by device hardware |
| `onDeviceConfigLoaded(JSONObject)` | Device's current configuration received on connect |
| `onDisconnected()` | Device disconnected (user-initiated or unexpected) |
| `onError(String)` | Any SDK or transport error |

### USB Lock (BLE transport)

| Callback | When fired |
|---|---|
| `onUsbLocked()` | Device reports a USB host is active — BLE is config-only |
| `onUsbUnlocked()` | USB host disconnected — full BLE access restored |

### RFID

| Callback | When fired |
|---|---|
| `onTagDetected(epc, antenna)` | Single EPC detected during active scanning |
| `onCommandAcknowledged(cmd)` | Device acknowledged a sent command |
| `onReadingPaused()` | Scanning paused successfully |
| `onReadingStopped()` | Scanning stopped and EPC list delivered to device |
| `onCheckoutConfirmed(txnId)` | All checkout batches acknowledged — transaction complete |
| `onConfigUpdated()` | Device config update acknowledged |
| `onReaderStatusReceived(boolean)` | Reader active/inactive status response |
| `onHealthInfoReceived(JSONObject)` | Device module temperature (`module_temperature`, Integer °C) |
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

### Bluetooth Scan

| Callback | When fired |
|---|---|
| `onBleDeviceFound(BleDeviceInfo)` | One device found during active scan |
| `onBleScanComplete(List<BleDeviceInfo>)` | Scan ended — full result list provided |

---

## Configuration

### `SdkConfig` — SDK-level settings

```java
SdkConfig config = new SdkConfig.Builder()
    .commandTimeoutMs(5000)       // ACK wait timeout (default: 5000 ms)
    .autoReconnect(true)          // Reconnect on unexpected disconnect (default: true)
    .debugLogging(false)          // Verbose SDK logging
    .baudRate(115200)             // Serial baud rate (default: 115200)
    .checkoutBatchSize(20)        // EPCs per checkout_complete batch (default: 20)
    .build();

sdk.initialize(context, config);
```

**Checkout batching** — `checkoutBatchSize` splits large EPC lists across multiple sequential `checkout_complete` commands. Each batch waits for the device ACK before the next is sent. `onCheckoutConfirmed` fires once after all batches complete. Default `20` protects the STM device from memory pressure on large reads. Set to `0` to disable batching.

**BLE transport:**

```java
SdkConfig bleConfig = new SdkConfig.Builder()
    .transportType(TransportType.BLUETOOTH)
    .bleDeviceAddress("AA:BB:CC:DD:EE:FF")
    .build();
```

Or use the convenience method which handles reconfiguration automatically:

```java
sdk.connectBle("AA:BB:CC:DD:EE:FF");
```

### `RfidDeviceConfig` — Device hardware settings

```java
RfidDeviceConfig config = new RfidDeviceConfig.Builder()
    .region("ID")
    .protocol("GEN2")
    .antenna(1, true,  1800)   // (port, active, readPowerMdBm)
    .antenna(2, true,  1800)
    .antenna(3, false, 1800)
    .antenna(4, false, 1800)
    .networkWifi("MySSID", "WPA2", "password")  // or .networkLan()
    .hopTime(200)
    .readOnFrequency(500)
    .readOffFrequency(500)
    .hopFrequencyKhz("903250")
    .build();

sdk.sendDeviceConfig(config);   // allowed over both USB and BLE
```

---

## Device Config on Connect

After every successful connection the SDK fires `onDeviceConfigLoaded(JSONObject)` with the device's current hardware configuration. Use this to pre-populate your Settings dialog without a separate fetch command.

The config object shape:

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
  "hop_frequency": [903250]
}
```

---

## Bluetooth — Config-Only Mode

When connected via BLE the SDK checks whether the device already has a USB host active:

| Scenario | SDK mode | Allowed commands |
|---|---|---|
| BLE only (no USB tablet) | Full access | All commands |
| BLE + USB tablet connected | Config-only | `sendDeviceConfig()` only |

When the USB tablet disconnects, the SDK detects this automatically and fires `onUsbUnlocked()`, restoring full access.

Attempting blocked commands while USB-locked dispatches `onError("Device locked by USB host")`.

---

## Device State Machine

```
IDLE ─── connect() ──────────► CONNECTED
                                   │
                       startReading() ▼         (USB only)
                               SCANNING ◄─────────────┐
                                   │                  │
                       pauseReading() ▼    startReading() │
                               PAUSED  ──────────────►─┘
                                   │
                       stopReading() ▼
                               CONNECTED
                                   │
              checkoutCompleted() ▼              (USB only)
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

| Display Name | Device Type | SKU | Constant |
|---|---|---|---|
| AXL FLAT STM | `AXL_FLAT` | `A120IAB` | `DeviceInfo.DEVICE_TYPE_AXL_FLAT` |
| AXL BIN | `AXL_BIN` | — | `DeviceInfo.DEVICE_TYPE_AXL_BIN` |
| AXL GATE | `AXL_GATE` | — | `DeviceInfo.DEVICE_TYPE_AXL_GATE` |

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

## License

Copyright 2024 AXL System.  
Licensed under the [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt).
