package com.axlsystem.axlsdk.sample;

import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.axlsystem.axlsdk.Sdk;
import com.axlsystem.axlsdk.config.SdkConfig;
import com.axlsystem.axlsdk.listener.SdkListener;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Sample POS Activity demonstrating full axl SDK integration.
 * Use this as a reference when integrating the SDK into your own POS app.
 */
public class MainActivity extends AppCompatActivity implements SdkListener {

    private Sdk sdk;
    private final List<String> scannedEpcs = new ArrayList<>();

    private TextView tvStatus;
    private TextView tvEpcCount;
    private TextView tvEpcList;
    private Button btnConnect;
    private Button btnStartScan;
    private Button btnPause;
    private Button btnCheckout;
    private Button btnDisconnect;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus    = findViewById(R.id.tvStatus);
        tvEpcCount  = findViewById(R.id.tvEpcCount);
        tvEpcList   = findViewById(R.id.tvEpcList);
        btnConnect    = findViewById(R.id.btnConnect);
        btnStartScan  = findViewById(R.id.btnStartScan);
        btnPause      = findViewById(R.id.btnPause);
        btnCheckout   = findViewById(R.id.btnCheckout);
        btnDisconnect = findViewById(R.id.btnDisconnect);

        sdk = Sdk.getInstance();

        SdkConfig config = new SdkConfig.Builder()
                .commandTimeoutMs(5000)
                .autoReconnect(true)
                .debugLogging(true)
                .build();

        sdk.initialize(this, config);
        sdk.setListener(this);

        // connect() sends connection_sync handshake; onConnected() fires on success
        btnConnect.setOnClickListener(v -> sdk.connect());

        btnStartScan.setOnClickListener(v -> {
            scannedEpcs.clear();
            updateEpcList();
            sdk.startReading();
        });

        btnPause.setOnClickListener(v -> sdk.pauseReading());

        btnCheckout.setOnClickListener(v -> {
            if (scannedEpcs.isEmpty()) {
                Toast.makeText(this, "No items scanned yet", Toast.LENGTH_SHORT).show();
                return;
            }
            String txId = "TX-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            sdk.checkoutCompleted(txId, new ArrayList<>(scannedEpcs));
        });

        btnDisconnect.setOnClickListener(v -> sdk.disconnect());

        setButtonStates(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sdk != null && sdk.isConnected()) sdk.disconnect();
    }

    // =========================================================================
    // SdkListener — all callbacks arrive on the main thread
    // =========================================================================

    @Override
    public void onConnected() {
        setStatus("Connected — ready to scan");
        setButtonStates(true);
    }

    @Override
    public void onDisconnected() {
        setStatus("Disconnected");
        setButtonStates(false);
    }

    @Override
    public void onCommandAcknowledged(String cmd) {
        switch (cmd) {
            case "read_start":
                setStatus("Scanning active...");
                btnStartScan.setEnabled(false);
                btnPause.setEnabled(true);
                break;
            case "read_pause":
                setStatus("Paused — " + scannedEpcs.size() + " item(s) in basket");
                btnStartScan.setEnabled(true);
                btnPause.setEnabled(false);
                break;
            case "checkout_complete":
                setStatus("Checkout sent — awaiting confirmation");
                break;
        }
    }

    @Override
    public void onTagDetected(String epc, int antenna) {
        if (!scannedEpcs.contains(epc)) {
            scannedEpcs.add(epc);
            updateEpcList();
        }
    }

    @Override
    public void onReadingPaused() {
        setStatus("Paused — " + scannedEpcs.size() + " item(s)");
    }

    @Override
    public void onCheckoutConfirmed(String transactionNo) {
        Toast.makeText(this, "Checkout complete: " + transactionNo, Toast.LENGTH_LONG).show();
        scannedEpcs.clear();
        updateEpcList();
        setStatus("Ready — next customer");
        btnStartScan.setEnabled(true);
        btnPause.setEnabled(false);
    }

    @Override
    public void onHealthInfoReceived(JSONObject data) {
        double cpu = data.optDouble("cpu_percent", -1);
        double mem = data.optDouble("memory_percent", -1);
        setStatus(String.format("Health — CPU: %.1f%%  MEM: %.1f%%", cpu, mem));
    }

    @Override
    public void onConfigUpdated() {
        setStatus("Device configuration updated");
        Toast.makeText(this, "Config updated successfully", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onError(String error) {
        Toast.makeText(this, "SDK Error: " + error, Toast.LENGTH_SHORT).show();
        setStatus("Error: " + error);
    }

    // =========================================================================

    private void setStatus(String msg) { tvStatus.setText("Status: " + msg); }

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
        btnCheckout.setEnabled(connected);
        btnDisconnect.setEnabled(connected);
    }
}
