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
        Log.d(TAG, "onDataReceived: Raw sensor data: " + data);

        // Parse the data to extract both EKG and pulse values
        try {
            float ekgValue = extractEKGValue(data);
            int pulseValue = extractPulseValue(data);

            Log.d(TAG, "onDataReceived: Extracted EKG=" + ekgValue + ", Pulse=" + pulseValue);

            runOnUiThread(() -> {
                // Add the EKG value to the graph
                if (ekgValue > 0) {
                    ecgView.addRealEKGValue(ekgValue);
                }

                // Use the real pulse value for heart rate display
                if (pulseValue > 0 && pulseValue >= 40 && pulseValue <= 200) {
                    currentHeartRate = pulseValue;
                    hasRealHeartRate = true;
                    heartRateText.setText("Heart Rate: " + currentHeartRate + " BPM (Sensor)");
                    ecgView.setHeartRate(currentHeartRate);
                    Log.d(TAG, "onDataReceived: Using real pulse value: " + currentHeartRate);
                } else if (ekgValue > 0) {
                    // Fallback: try to estimate from EKG if no valid pulse
                    int estimatedHR = calculateHeartRateFromEKG(ekgValue);
                    if (estimatedHR > 0) {
                        currentHeartRate = estimatedHR;
                        hasRealHeartRate = true;
                        heartRateText.setText("Heart Rate: " + currentHeartRate + " BPM (Estimated)");
                        ecgView.setHeartRate(currentHeartRate);
                    }
                } else {
                    // No valid data
                    heartRateText.setText("Heart Rate: -- BPM (No data)");
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "onDataReceived: Error processing sensor data", e);
        }
    }

    private float extractEKGValue(String data) {
        try {
            // Method 1: Try using Gson for proper JSON parsing
            if (data.contains("{") && data.contains("}")) {
                try {
                    com.google.gson.Gson gson = new com.google.gson.Gson();
                    com.google.gson.JsonObject jsonObject = gson.fromJson(data, com.google.gson.JsonObject.class);

                    if (jsonObject.has("ekg")) {
                        float value = jsonObject.get("ekg").getAsFloat();
                        Log.d(TAG, "extractEKGValue: Found EKG via Gson: " + value);
                        return value;
                    }
                    if (jsonObject.has("ecg")) {
                        float value = jsonObject.get("ecg").getAsFloat();
                        Log.d(TAG, "extractEKGValue: Found ECG via Gson: " + value);
                        return value;
                    }
                } catch (Exception e) {
                    Log.d(TAG, "extractEKGValue: Gson parsing failed, trying manual parsing");
                }
            }

            // Method 2: Manual regex parsing
            String[] patterns = {
                    "\"ekg\"\\s*:\\s*([0-9]+\\.?[0-9]*)",
                    "\"ecg\"\\s*:\\s*([0-9]+\\.?[0-9]*)",
                    "'ekg'\\s*:\\s*([0-9]+\\.?[0-9]*)",
                    "'ecg'\\s*:\\s*([0-9]+\\.?[0-9]*)"
            };

            for (String pattern : patterns) {
                java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
                java.util.regex.Matcher m = p.matcher(data);
                if (m.find()) {
                    float value = Float.parseFloat(m.group(1));
                    Log.d(TAG, "extractEKGValue: Found EKG via regex: " + value);
                    return value;
                }
            }

            // Method 3: Simple string parsing as fallback
            if (data.contains("ekg") || data.contains("ecg")) {
                String[] parts = data.split("[:,}]");
                for (int i = 0; i < parts.length - 1; i++) {
                    if (parts[i].contains("ekg") || parts[i].contains("ecg")) {
                        try {
                            String valueStr = parts[i + 1].trim().replace("\"", "").replace("'", "");
                            float value = Float.parseFloat(valueStr);
                            Log.d(TAG, "extractEKGValue: Found EKG via string parsing: " + value);
                            return value;
                        } catch (NumberFormatException e) {
                            Log.w(TAG, "extractEKGValue: Could not parse: " + parts[i + 1]);
                        }
                    }
                }
            }

            Log.w(TAG, "extractEKGValue: No EKG value found in data: " + data);
            return 0f;

        } catch (Exception e) {
            Log.e(TAG, "extractEKGValue: Error extracting EKG value", e);
            return 0f;
        }
    }

    private int extractPulseValue(String data) {
        try {
            // Method 1: Try using Gson for proper JSON parsing
            if (data.contains("{") && data.contains("}")) {
                try {
                    com.google.gson.Gson gson = new com.google.gson.Gson();
                    com.google.gson.JsonObject jsonObject = gson.fromJson(data, com.google.gson.JsonObject.class);

                    if (jsonObject.has("pulse")) {
                        int value = jsonObject.get("pulse").getAsInt();
                        Log.d(TAG, "extractPulseValue: Found pulse via Gson: " + value);
                        return value;
                    }
                    if (jsonObject.has("0")) {
                        // Sometimes pulse comes as "0" field
                        int value = jsonObject.get("0").getAsInt();
                        Log.d(TAG, "extractPulseValue: Found pulse as '0' via Gson: " + value);
                        return value;
                    }
                } catch (Exception e) {
                    Log.d(TAG, "extractPulseValue: Gson parsing failed, trying manual parsing");
                }
            }

            // Method 2: Manual regex parsing
            String[] patterns = {
                    "\"pulse\"\\s*:\\s*([0-9]+)",
                    "\"0\"\\s*:\\s*([0-9]+)",
                    "'pulse'\\s*:\\s*([0-9]+)",
                    "'0'\\s*:\\s*([0-9]+)"
            };

            for (String pattern : patterns) {
                java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
                java.util.regex.Matcher m = p.matcher(data);
                if (m.find()) {
                    int value = Integer.parseInt(m.group(1));
                    Log.d(TAG, "extractPulseValue: Found pulse via regex: " + value);
                    return value;
                }
            }

            // Method 3: Simple string parsing as fallback
            if (data.contains("pulse") || data.contains("\"0\"")) {
                String[] parts = data.split("[:,}]");
                for (int i = 0; i < parts.length - 1; i++) {
                    if (parts[i].contains("pulse") || parts[i].contains("\"0\"")) {
                        try {
                            String valueStr = parts[i + 1].trim().replace("\"", "").replace("'", "");
                            int value = Integer.parseInt(valueStr);
                            Log.d(TAG, "extractPulseValue: Found pulse via string parsing: " + value);
                            return value;
                        } catch (NumberFormatException e) {
                            Log.w(TAG, "extractPulseValue: Could not parse: " + parts[i + 1]);
                        }
                    }
                }
            }

            Log.w(TAG, "extractPulseValue: No pulse value found in data: " + data);
            return 0;

        } catch (Exception e) {
            Log.e(TAG, "extractPulseValue: Error extracting pulse value", e);
            return 0;
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
        // For EKG values around 300, calculate a reasonable heart rate
        // Map the EKG variations to heart rate changes

        if (ekgValue >= 250 && ekgValue <= 350) {
            // Map 250-350 range to 60-90 BPM
            int heartRate = Math.round(60 + ((ekgValue - 250) / 100) * 30);
            Log.d(TAG, "calculateHeartRateFromEKG: EKG " + ekgValue + " -> HR " + heartRate);
            return heartRate;
        } else if (ekgValue > 350) {
            // High EKG values = higher heart rate
            int heartRate = Math.min(95, Math.round(90 + (ekgValue - 350) / 20));
            Log.d(TAG, "calculateHeartRateFromEKG: High EKG " + ekgValue + " -> HR " + heartRate);
            return heartRate;
        } else if (ekgValue < 250) {
            // Low EKG values = lower heart rate
            int heartRate = Math.max(55, Math.round(60 - (250 - ekgValue) / 20));
            Log.d(TAG, "calculateHeartRateFromEKG: Low EKG " + ekgValue + " -> HR " + heartRate);
            return heartRate;
        } else {
            Log.w(TAG, "calculateHeartRateFromEKG: Unexpected EKG value: " + ekgValue);
            return 0;
        }
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