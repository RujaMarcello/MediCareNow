package com.example.medicarenow;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.RequiresPermission;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class HealthDataActivity extends AppCompatActivity implements BluetoothService.BluetoothDataListener {

    private TextView pulseTextView, tempTextView, humidityTextView, statusTextView;
    private FirebaseFirestore db;
    private Button saveDataButton, recommendationsButton, ecgButton;
    private HealthData currentHealthData;
    private String currentUserId;
    private static final String TAG = "HealthDataActivity";

    // Threshold values for alerts
    private static final int MAX_PULSE = 100;
    private static final int MIN_PULSE = 60;
    private static final float MAX_TEMP = 37.5f;
    private static final float MIN_TEMP = 36.0f;
    private static final float MAX_HUMIDITY = 70.0f;
    private static final float MIN_HUMIDITY = 30.0f;

    // Bluetooth service
    private BluetoothService bluetoothService;
    private boolean isBound = false;

    // Hardcoded Bluetooth device address - ARDUINO MAC ADDRESS
    private static final String ARDUINO_BLUETOOTH_ADDRESS = "58:56:00:00:2C:BE"; // Arduino MAC address

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "onServiceConnected: Bluetooth service connected");
            BluetoothService.LocalBinder binder = (BluetoothService.LocalBinder) service;
            bluetoothService = binder.getService();
            bluetoothService.setDataListener(HealthDataActivity.this);
            isBound = true;

            // Connect to the Arduino device
            connectToArduinoDevice();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "onServiceDisconnected: Bluetooth service disconnected");
            isBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate: Starting HealthDataActivity");
        setContentView(R.layout.activity_health_data);

        db = FirebaseFirestore.getInstance();
        Log.d(TAG, "onCreate: Firestore instance initialized");

        // Check user session
        SharedPreferences prefs = getSharedPreferences("MediCareNow", MODE_PRIVATE);
        currentUserId = prefs.getString("user_id", "");
        String userEmail = prefs.getString("user_email", "");

        Log.d(TAG, "onCreate: Current user ID: " + currentUserId + ", email: " + userEmail);

        if (currentUserId.isEmpty()) {
            Log.w(TAG, "onCreate: No user session found, redirecting to login");
            Toast.makeText(this, "Please login first", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(HealthDataActivity.this, LoginActivity.class));
            finish();
            return;
        }

        // Initialize UI elements
        initializeUI();

        // Start and bind to BluetoothService
        Log.d(TAG, "onCreate: Starting Bluetooth service");
        Intent bluetoothIntent = new Intent(this, BluetoothService.class);
        startService(bluetoothIntent);
        bindService(bluetoothIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        Log.d(TAG, "onCreate: HealthDataActivity initialized successfully");
    }

    private void initializeUI() {
        Log.d(TAG, "initializeUI: Initializing UI elements");

        pulseTextView = findViewById(R.id.pulseTextView);
        tempTextView = findViewById(R.id.tempTextView);
        humidityTextView = findViewById(R.id.humidityTextView);
        statusTextView = findViewById(R.id.statusTextView);
        recommendationsButton = findViewById(R.id.recommendationsButton);
        saveDataButton = findViewById(R.id.saveDataButton);
        ecgButton = findViewById(R.id.ecgButton);

        // Set initial status
        statusTextView.setText("Status: Initializing Bluetooth connection...");

        // Initialize save button
        saveDataButton.setOnClickListener(v -> {
            Log.d(TAG, "Save data button clicked");
            if (currentHealthData != null) {
                saveToDatabase(currentHealthData);
                Toast.makeText(this, "Data saved successfully", Toast.LENGTH_SHORT).show();
            } else {
                Log.w(TAG, "No data to save");
                Toast.makeText(this, "No data to save", Toast.LENGTH_SHORT).show();
            }
        });

        ecgButton.setOnClickListener(v -> {
            Log.d(TAG, "ECG button clicked");
            Intent intent = new Intent(HealthDataActivity.this, ECGMonitoringActivity.class);
            startActivity(intent);
        });

        recommendationsButton.setOnClickListener(v -> {
            Log.d(TAG, "Recommendations button clicked");
            Intent intent = new Intent(HealthDataActivity.this, RecommendationsActivity.class);
            startActivity(intent);
        });

        Log.d(TAG, "initializeUI: UI elements initialized successfully");
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private void connectToArduinoDevice() {
        Log.d(TAG, "connectToArduinoDevice: Attempting to connect to Arduino: " + ARDUINO_BLUETOOTH_ADDRESS);

        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Log.e(TAG, "connectToArduinoDevice: Bluetooth not supported");
            statusTextView.setText("Status: Bluetooth not supported");
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Log.w(TAG, "connectToArduinoDevice: Bluetooth not enabled");
            statusTextView.setText("Status: Please enable Bluetooth");
            return;
        }

        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(ARDUINO_BLUETOOTH_ADDRESS);
            if (device != null && bluetoothService != null) {
                Log.d(TAG, "connectToArduinoDevice: Connecting to device: " + device.getName());
                bluetoothService.connectToDevice(device);
                statusTextView.setText("Status: Connecting to Arduino...");
            } else {
                Log.e(TAG, "connectToArduinoDevice: Device not found or service not available");
                statusTextView.setText("Status: Arduino device not found");
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "connectToArduinoDevice: Invalid MAC address: " + ARDUINO_BLUETOOTH_ADDRESS, e);
            statusTextView.setText("Status: Invalid Arduino MAC address");
        }
    }

    private void updateUI(HealthData data) {
        Log.d(TAG, "updateUI: Updating UI with pulse: " + data.pulse + ", temp: " + data.temperature + ", humidity: "
                + data.humidity);

        pulseTextView.setText(String.format(Locale.getDefault(), "Puls: %d bpm", data.pulse));
        tempTextView.setText(String.format(Locale.getDefault(), "Temperatură: %.1f°C", data.temperature));
        humidityTextView.setText(String.format(Locale.getDefault(), "Umiditate: %.1f%%", data.humidity));
    }

    private void checkThresholds(HealthData data) {
        StringBuilder alertMessage = new StringBuilder();

        if (data.pulse > MAX_PULSE) {
            alertMessage.append("High pulse! ");
            Log.w(TAG, "checkThresholds: High pulse detected: " + data.pulse);
        } else if (data.pulse < MIN_PULSE) {
            alertMessage.append("Low pulse! ");
            Log.w(TAG, "checkThresholds: Low pulse detected: " + data.pulse);
        }

        if (data.temperature > MAX_TEMP) {
            alertMessage.append("High temperature! ");
            Log.w(TAG, "checkThresholds: High temperature detected: " + data.temperature);
        } else if (data.temperature < MIN_TEMP) {
            alertMessage.append("Low temperature! ");
            Log.w(TAG, "checkThresholds: Low temperature detected: " + data.temperature);
        }

        if (data.humidity > MAX_HUMIDITY) {
            alertMessage.append("High humidity! ");
            Log.w(TAG, "checkThresholds: High humidity detected: " + data.humidity);
        } else if (data.humidity < MIN_HUMIDITY) {
            alertMessage.append("Low humidity! ");
            Log.w(TAG, "checkThresholds: Low humidity detected: " + data.humidity);
        }

        if (alertMessage.length() > 0) {
            Toast.makeText(this, alertMessage.toString(), Toast.LENGTH_LONG).show();
            statusTextView.setText("ALERT: " + alertMessage.toString());
            Log.i(TAG, "checkThresholds: Alert triggered: " + alertMessage.toString());
        } else {
            statusTextView.setText("Status: All values normal - Connected to Arduino");
        }
    }

    private void saveToDatabase(HealthData data) {
        if (currentUserId.isEmpty()) {
            Log.e(TAG, "saveToDatabase: No user ID available");
            return;
        }

        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        Log.d(TAG, "saveToDatabase: Saving data for user: " + currentUserId + " at timestamp: " + timestamp);

        // Save pulse data
        Map<String, Object> pulseRecord = new HashMap<>();
        pulseRecord.put("valoare", data.pulse);
        pulseRecord.put("timestamp", timestamp);
        pulseRecord.put("pacientID", currentUserId);

        db.collection("puls")
                .add(pulseRecord)
                .addOnSuccessListener(documentReference -> {
                    Log.d(TAG, "saveToDatabase: Pulse data saved with ID: " + documentReference.getId());
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "saveToDatabase: Error saving pulse data", e);
                });

        // Save humidity data
        Map<String, Object> humidityRecord = new HashMap<>();
        humidityRecord.put("valoare", data.humidity);
        humidityRecord.put("timestamp", timestamp);
        humidityRecord.put("pacientID", currentUserId);

        db.collection("umiditate")
                .add(humidityRecord)
                .addOnSuccessListener(documentReference -> {
                    Log.d(TAG, "saveToDatabase: Humidity data saved with ID: " + documentReference.getId());
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "saveToDatabase: Error saving humidity data", e);
                });

        // Save complete health data
        Map<String, Object> healthRecord = new HashMap<>();
        healthRecord.put("temperatura", data.temperature);
        healthRecord.put("puls", data.pulse);
        healthRecord.put("umiditate", data.humidity);
        healthRecord.put("timestamp", timestamp);
        healthRecord.put("pacientID", currentUserId);

        db.collection("valori_normale")
                .add(healthRecord)
                .addOnSuccessListener(documentReference -> {
                    Log.d(TAG, "saveToDatabase: Complete health data saved with ID: " + documentReference.getId());
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "saveToDatabase: Error saving complete health data", e);
                });
    }

    // BLUETOOTH DATA LISTENER METHODS
    @Override
    public void onDataReceived(String data) {
        Log.d(TAG, "onDataReceived: Received data from Arduino: " + data);
        try {
            // Try to parse JSON data from Arduino
            currentHealthData = new Gson().fromJson(data, HealthData.class);
            runOnUiThread(() -> {
                updateUI(currentHealthData);
                checkThresholds(currentHealthData);
                Log.d(TAG, "onDataReceived: UI updated with new Arduino data");
            });
        } catch (JsonSyntaxException e) {
            Log.e(TAG, "onDataReceived: JSON parsing error for data: " + data, e);
            runOnUiThread(() -> statusTextView.setText("Status: Data format error from Arduino"));
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @Override
    public void onConnectionStatusChanged(boolean isConnected) {
        Log.d(TAG, "onConnectionStatusChanged: Connection status changed to: " + isConnected);
        runOnUiThread(() -> {
            if (isConnected) {
                statusTextView.setText("Status: Connected to Arduino device");
                Log.i(TAG, "onConnectionStatusChanged: Successfully connected to Arduino");
            } else {
                statusTextView.setText("Status: Disconnected - attempting to reconnect");
                Log.w(TAG, "onConnectionStatusChanged: Disconnected from Arduino, attempting reconnect");
                // Attempt to reconnect
                if (bluetoothService != null) {
                    connectToArduinoDevice();
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy: Cleaning up Bluetooth service connection");
        // Unbind from the service
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
    }

    // Data model class for Arduino JSON data
    private static class HealthData {
        int pulse;
        float temperature;
        float humidity;
    }
}