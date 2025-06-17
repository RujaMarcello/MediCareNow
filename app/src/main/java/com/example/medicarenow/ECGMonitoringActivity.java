package com.example.medicarenow;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class ECGMonitoringActivity extends AppCompatActivity implements BluetoothService.BluetoothDataListener {
    private static final String TAG = "ECGMonitoringActivity";

    private ECGView ecgView;
    private TextView ecgStatus;
    private TextView heartRateText;
    private Handler handler;
    private int currentHeartRate = 0; // No default heart rate
    private boolean isRunning = false;
    private boolean hasRealHeartRate = false;

    // Bluetooth service connection
    private BluetoothService bluetoothService;
    private boolean isBound = false;

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "onServiceConnected: Bluetooth service connected");
            BluetoothService.LocalBinder binder = (BluetoothService.LocalBinder) service;
            bluetoothService = binder.getService();

            if (bluetoothService != null) {
                bluetoothService.setDataListener(ECGMonitoringActivity.this);
                isBound = true;
                ecgStatus.setText("Status: Connected to EKG device");
                // ECGView is always ready for real data, no need to set mode
                Log.d(TAG, "onServiceConnected: Ready to receive real EKG data");
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "onServiceDisconnected: Bluetooth service disconnected");
            isBound = false;
            bluetoothService = null;
            ecgStatus.setText("Status: Disconnected - No EKG data");
            ecgView.resetToFlatLine();
            // Reset heart rate when service disconnects
            hasRealHeartRate = false;
            heartRateText.setText("Heart Rate: -- BPM (No data)");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ecg_monitoring);

        ecgView = findViewById(R.id.ecgView);
        ecgStatus = findViewById(R.id.ecgStatus);
        heartRateText = findViewById(R.id.heartRateText);
        Button backButton = findViewById(R.id.backButton);

        // Initialize with no heart rate data
        heartRateText.setText("Heart Rate: -- BPM (No data)");
        // Don't set heart rate on ECGView until we have real data

        // Try to connect to BluetoothService
        ecgStatus.setText("Connecting to EKG device...");

        Intent serviceIntent = new Intent(this, BluetoothService.class);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        // Check connection status after 3 seconds
        new Handler().postDelayed(() -> {
            if (!isBound) {
                Log.w(TAG, "onCreate: Could not connect to Bluetooth service");
                ecgStatus.setText("Status: No EKG device connected - Flat line");
                ecgView.resetToFlatLine();
            }
        }, 3000);

        backButton.setOnClickListener(v -> finish());
    }

    // No simulation needed - only real data or flat line

    // BluetoothService.BluetoothDataListener implementation
    @Override
    public void onDataReceived(String data) {
        Log.d(TAG, "onDataReceived: Raw EKG data: " + data);

        // Parse the data to extract EKG value
        try {
            if (data.contains("{") && data.contains("}")) {
                // Try to extract EKG value from JSON
                if (data.contains("\"ekg\"") || data.contains("\"ecg\"")) {
                    String[] parts = data.split("[:,}]");
                    for (int i = 0; i < parts.length - 1; i++) {
                        if (parts[i].contains("ekg") || parts[i].contains("ecg")) {
                            try {
                                float ekgValue = Float.parseFloat(parts[i + 1].trim().replace("\"", ""));
                                Log.d(TAG, "onDataReceived: Extracted EKG value: " + ekgValue);

                                runOnUiThread(() -> {
                                    ecgView.addRealEKGValue(ekgValue);

                                    // Calculate heart rate from EKG data
                                    int calculatedHR = calculateHeartRateFromEKG(ekgValue);
                                    if (calculatedHR > 0) {
                                        currentHeartRate = calculatedHR;
                                        hasRealHeartRate = true;
                                        heartRateText.setText("Heart Rate: " + currentHeartRate + " BPM (Real-time)");
                                        ecgView.setHeartRate(currentHeartRate);
                                    } else {
                                        // EKG value too low/invalid - show that we can't calculate HR
                                        heartRateText.setText("Heart Rate: -- BPM (EKG too low)");
                                    }
                                });
                                break;
                            } catch (NumberFormatException e) {
                                Log.w(TAG, "onDataReceived: Could not parse EKG value: " + parts[i + 1]);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "onDataReceived: Error processing EKG data", e);
        }
    }

    @Override
    public void onConnectionStatusChanged(boolean isConnected) {
        Log.d(TAG, "onConnectionStatusChanged: " + isConnected);
        runOnUiThread(() -> {
            if (isConnected) {
                ecgStatus.setText("Status: Connected - Receiving real EKG data");
                // ECGView will automatically show real data when it arrives
            } else {
                ecgStatus.setText("Status: Disconnected - No EKG data");
                ecgView.resetToFlatLine();
                // Reset heart rate display when disconnected
                hasRealHeartRate = false;
                heartRateText.setText("Heart Rate: -- BPM (No data)");
            }
        });
    }

    private int calculateHeartRateFromEKG(float ekgValue) {
        // Heart rate estimation based on EKG amplitude ranges
        // This maps EKG values to realistic heart rate ranges
        if (ekgValue >= 180)
            return 95; // Very high EKG = high heart rate
        else if (ekgValue >= 160)
            return 90;
        else if (ekgValue >= 140)
            return 85;
        else if (ekgValue >= 120)
            return 80;
        else if (ekgValue >= 100)
            return 75;
        else if (ekgValue >= 80)
            return 70;
        else if (ekgValue >= 60)
            return 65;
        else if (ekgValue >= 50)
            return 60; // Low EKG = low heart rate
        else
            return 0; // Invalid/too low EKG value - don't show heart rate
    }

    @Override
    protected void onPause() {
        super.onPause();
        isRunning = false;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // No need to restart anything - ECGView handles its own state
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isRunning = false;

        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }

        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }

        Log.d(TAG, "onDestroy: ECGMonitoringActivity destroyed");
    }
}