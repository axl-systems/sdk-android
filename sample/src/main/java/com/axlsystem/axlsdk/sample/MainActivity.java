package com.axlsystem.axlsdk.sample;

import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.axlsystem.axlsdk.Sdk;
import com.axlsystem.axlsdk.config.SdkConfig;
import com.axlsystem.axlsdk.listener.SdkListener;
import com.axlsystem.axlsdk.model.BleDeviceInfo;
import com.axlsystem.axlsdk.model.DeviceInfo;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Sample POS Activity demonstrating full axl SDK integration.
 *
 * Covers:
 *  - USB connection + device handshake
 *  - USB device attach handling (onNewIntent) for correct port targeting on replug
 *  - Device info and config loaded on connect
 *  - RFID start / pause / stop / checkout (auto-batched)
 *  - USB lock callbacks for BLE config-only mode
 *  - Error handling
 *
 * Use this as a reference when integrating the SDK into your own POS app.
 */
public class MainActivity extends AppCompatActivity implements SdkListener {

    private Sdk sdk;
    private final List<String> scannedEpcs = new ArrayList<>();

    private TextView tvStatus;
    private TextView tvDeviceInfo;
    private TextView tvEpcCount;
    private TextView tvEpcList;
    private Button   btnConnect;
    private Button   btnStartScan;
    private Button   btnPause;
    private Button   btnStop;
    private Button   btnCheckout;
    private Button   btnDisconnect;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus     = findViewById(R.id.tvStatus);
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo);
        tvEpcCount   = findViewById(R.id.tvEpcCount);
        tvEpcList    = findViewById(R.id.tvEpcList);
        btnConnect    = findViewById(R.id.btnConnect);
        btnStartScan  = findViewById(R.id.btnStartScan);
        btnPause      = findViewById(R.id.btnPause);
        btnStop       = findViewById(R.id.btnStop);
        btnCheckout   = findViewById(R.id.btnCheckout);
        btnDisconnect = findViewById(R.id.btnDisconnect);

        sdk = Sdk.getInstance();

        SdkConfig config = new SdkConfig.Builder()
                .commandTimeoutMs(5000)
                .debugLogging(true)
                .checkoutBatchSize(20)   // max 20 EPCs per checkout_complete command
                .build();

        sdk.initialize(this, config);
        sdk.setListener(this);

        // Pin the USB device delivered by the launch intent (if the activity was opened
        // by the OS in response to the cable being plugged in).
        handleUsbAttachIntent(getIntent());

        // connect() sends connection_sync handshake; onConnected() fires on success
        btnConnect.setOnClickListener(v -> sdk.connect());

        btnStartScan.setOnClickListener(v -> {
            scannedEpcs.clear();
            updateEpcList();
            sdk.startReading();
        });

        btnPause.setOnClickListener(v -> sdk.pauseReading());

        btnStop.setOnClickListener(v -> sdk.stopReading(new ArrayList<>(scannedEpcs)));

        btnCheckout.setOnClickListener(v -> {
            if (scannedEpcs.isEmpty()) {
                Toast.makeText(this, "No items scanned yet", Toast.LENGTH_SHORT).show();
                return;
            }
            // Generate a unique transaction ID
            String txId = "TX-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            // SDK auto-batches EPCs into groups of checkoutBatchSize (default 20)
            // onCheckoutConfirmed fires once after all batches are ACK'd
            sdk.checkoutCompleted(txId, new ArrayList<>(scannedEpcs));
        });

        btnDisconnect.setOnClickListener(v -> sdk.disconnect());

        setButtonStates(false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Fired when the cable is replugged while the activity is already running.
        // Pins the new device path so connect() targets the correct port even if
        // the OS assigned a different path after re-enumeration.
        handleUsbAttachIntent(intent);
    }

    private void handleUsbAttachIntent(Intent intent) {
        if (!UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) return;
        UsbDevice device;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        } else {
            device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        }
        if (device == null) return;
        sdk.setTargetUsbDevice(device);
        setStatus("USB device attached — tap Connect");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sdk != null && sdk.isConnected()) sdk.disconnect();
    }

    // =========================================================================
    // SdkListener — all callbacks arrive on the main thread
    // =========================================================================

    // ── Connection ────────────────────────────────────────────────────────────

    @Override
    public void onConnected() {
        setStatus("Connected — ready to scan");
        setButtonStates(true);
    }

    @Override
    public void onDeviceIdentified(DeviceInfo info) {
        // Fired after onConnected() with device name, type, and SKU
        tvDeviceInfo.setText(info.getDeviceName() + "  ·  SKU: " + info.getSku()
                + "  ·  " + info.getDeviceType());
    }

    @Override
    public void onDeviceConfigLoaded(JSONObject config) {
        // Fired after onConnected() with the device's current hardware configuration.
        // Use this to pre-populate your Settings dialog without a separate fetch command.
        String region = config.optString("region", "?");
        JSONObject network = config.optJSONObject("network_settings");
        boolean wifiOn = network != null
                && !network.optBoolean("lan", true)
                && network.optJSONObject("wifi") != null
                && network.optJSONObject("wifi").optBoolean("status", false);
        setStatus("Connected — region=" + region + "  network=" + (wifiOn ? "WiFi" : "LAN"));
    }

    @Override
    public void onDisconnected() {
        // SDK does not auto-reconnect. Replug the cable and tap Connect.
        setStatus("Disconnected — replug cable and tap Connect");
        tvDeviceInfo.setText("");
        setButtonStates(false);
    }

    // ── USB lock — BLE config-only mode ──────────────────────────────────────

    @Override
    public void onUsbLocked() {
        // Fired on the BLE-connected tablet when the device reports an active USB host.
        // Only sendDeviceConfig() is permitted — reading and checkout are blocked.
        setStatus("BLE — Config Only (USB host is active)");
        btnStartScan.setEnabled(false);
        btnPause.setEnabled(false);
        btnStop.setEnabled(false);
        btnCheckout.setEnabled(false);
        Toast.makeText(this, "Config updates only — device in use via USB", Toast.LENGTH_LONG).show();
    }

    @Override
    public void onUsbUnlocked() {
        // Fired when the USB host disconnects — full access restored.
        setStatus("BLE — Full access restored");
        btnStartScan.setEnabled(true);
        btnCheckout.setEnabled(true);
    }

    // ── RFID ──────────────────────────────────────────────────────────────────

    @Override
    public void onCommandAcknowledged(String cmd) {
        switch (cmd) {
            case "read_start":
                setStatus("Scanning active...");
                btnStartScan.setEnabled(false);
                btnPause.setEnabled(true);
                btnStop.setEnabled(true);
                break;
            case "read_pause":
                setStatus("Paused — " + scannedEpcs.size() + " item(s) in basket");
                btnStartScan.setEnabled(true);
                btnPause.setEnabled(false);
                break;
            case "read_stop":
                setStatus("Stopped — " + scannedEpcs.size() + " item(s)");
                btnStartScan.setEnabled(true);
                btnPause.setEnabled(false);
                btnStop.setEnabled(false);
                break;
            case "checkout_complete":
                setStatus("Checkout batch sent — awaiting confirmation...");
                break;
        }
    }

    @Override
    public void onTagDetected(String epc) {
        if (!scannedEpcs.contains(epc)) {
            scannedEpcs.add(epc);
            updateEpcList();
        }
    }

    @Override
    public void onModuleTemperatureReceived(int tempCelsius) {
        // Optional — only fires on new firmware that reports module_temp in tag_detected.
        setStatus("Scanning — Module temp: " + tempCelsius + "°C");
    }

    @Override
    public void onNfcTagDetected(String uid) {
        setStatus("NFC — UID: " + uid);
    }

    @Override
    public void onNfcRawDataReceived(String uid, String tech, JSONObject rawData) {
        // Optional — only fires on new firmware. Use rawData.optJSONObject("nfca") etc.
        setStatus("NFC — UID: " + uid + "  tech: " + tech);
    }

    @Override
    public void onReadingPaused() {
        setStatus("Paused — " + scannedEpcs.size() + " item(s)");
    }

    @Override
    public void onReadingStopped() {
        setStatus("Stopped — " + scannedEpcs.size() + " item(s) ready for checkout");
    }

    @Override
    public void onCheckoutConfirmed(String transactionNo) {
        // Fired ONCE after all checkout batches are acknowledged by the device.
        Toast.makeText(this, "Checkout complete: " + transactionNo, Toast.LENGTH_LONG).show();
        scannedEpcs.clear();
        updateEpcList();
        setStatus("Ready — next customer");
        btnStartScan.setEnabled(true);
        btnPause.setEnabled(false);
        btnStop.setEnabled(false);
    }

    @Override
    public void onConfigUpdated() {
        setStatus("Device configuration updated");
        Toast.makeText(this, "Config updated successfully", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onHealthInfoReceived(JSONObject data) {
        double temp    = data.optDouble("module_temperature", Double.NaN);
        int sdTotal    = data.optInt("sd_total_mb", -1);
        int sdUsed     = data.optInt("sd_used_mb", -1);
        int sdFree     = data.optInt("sd_free_mb", -1);

        String tempStr = Double.isNaN(temp) ? "N/A" : String.format("%.1f°C", temp);
        String sdStr   = sdTotal >= 0
                ? "  SD: " + sdUsed + "MB used / " + sdTotal + "MB (" + sdFree + "MB free)"
                : "";
        setStatus("Health — Temp: " + tempStr + sdStr);
    }

    @Override
    public void onDeviceLogReceived(String level, String message, String timestamp) {
        // Real-time log stream from device firmware — handle as needed
    }

    // ── BLE scan (optional) ───────────────────────────────────────────────────

    @Override
    public void onBleDeviceFound(BleDeviceInfo device) {
        // Fires per device during sdk.startBleScan()
        // Show device.name and device.address in a picker dialog
    }

    @Override
    public void onBleScanComplete(List<BleDeviceInfo> devices) {
        // Scan ended — show final list if needed
    }

    // ── Errors ────────────────────────────────────────────────────────────────

    @Override
    public void onError(String error) {
        Toast.makeText(this, "SDK Error: " + error, Toast.LENGTH_SHORT).show();
        setStatus("Error: " + error);
    }

    // =========================================================================

    private void setStatus(String msg) {
        tvStatus.setText("Status: " + msg);
    }

    private void updateEpcList() {
        tvEpcCount.setText("EPCs detected: " + scannedEpcs.size());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < scannedEpcs.size(); i++) {
            sb.append(i + 1).append(". ").append(scannedEpcs.get(i)).append("\n");
        }
        tvEpcList.setText(sb.toString());
    }

    private void setButtonStates(boolean connected) {
        btnConnect.setEnabled(!connected);
        btnStartScan.setEnabled(connected);
        btnPause.setEnabled(false);
        btnStop.setEnabled(false);
        btnCheckout.setEnabled(connected);
        btnDisconnect.setEnabled(connected);
    }
}
