package com.example.medicarenow;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.RequiresPermission;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class HealthDataActivity extends AppCompatActivity implements BluetoothService.BluetoothDataListener {

    private TextView pulseTextView, tempTextView, humidityTextView, ekgTextView, statusTextView;
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
    private static final float MAX_EKG = 200.0f;
    private static final float MIN_EKG = 50.0f;

    // Bluetooth service
    private BluetoothService bluetoothService;
    private boolean isBound = false;
    private boolean isBluetoothSupported = false;

    // Hardcoded Bluetooth device address - ARDUINO MAC ADDRESS
    private static final String ARDUINO_BLUETOOTH_ADDRESS = "58:56:00:00:2C:BE"; // Arduino MAC address

    // Permission request code
    private static final int BLUETOOTH_PERMISSION_REQUEST_CODE = 1001;

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            try {
                Log.d(TAG, "onServiceConnected: Bluetooth service connected");
                BluetoothService.LocalBinder binder = (BluetoothService.LocalBinder) service;
                bluetoothService = binder.getService();

                if (bluetoothService != null) {
                    bluetoothService.setDataListener(HealthDataActivity.this);
                    isBound = true;

                    // Check permissions before connecting
                    if (checkBluetoothPermissions()) {
                        connectToArduinoDevice();
                    } else {
                        requestBluetoothPermissions();
                    }
                } else {
                    Log.e(TAG, "onServiceConnected: BluetoothService is null");
                    statusTextView.setText("Status: Bluetooth service error");
                }
            } catch (Exception e) {
                Log.e(TAG, "onServiceConnected: Error connecting to service", e);
                statusTextView.setText("Status: Service connection error");
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "onServiceDisconnected: Bluetooth service disconnected");
            isBound = false;
            bluetoothService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate: Starting HealthDataActivity");

        try {
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

            // Check if Bluetooth is supported
            checkBluetoothSupport();

            // Start and bind to BluetoothService only if Bluetooth is supported
            if (isBluetoothSupported) {
                startBluetoothService();
            } else {
                // Show dummy data if Bluetooth is not supported
                showDummyData();
            }

            Log.d(TAG, "onCreate: HealthDataActivity initialized successfully");

        } catch (Exception e) {
            Log.e(TAG, "onCreate: Critical error during initialization", e);
            Toast.makeText(this, "Error initializing app: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void checkBluetoothSupport() {
        try {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            isBluetoothSupported = bluetoothAdapter != null;

            if (!isBluetoothSupported) {
                Log.w(TAG, "checkBluetoothSupport: Bluetooth not supported on this device");
                statusTextView.setText("Status: Bluetooth not supported - using demo mode");
            } else {
                Log.d(TAG, "checkBluetoothSupport: Bluetooth is supported");
            }
        } catch (Exception e) {
            Log.e(TAG, "checkBluetoothSupport: Error checking Bluetooth support", e);
            isBluetoothSupported = false;
            statusTextView.setText("Status: Bluetooth check failed - using demo mode");
        }
    }

    private boolean checkBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this,
                            Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this,
                    new String[] { Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN },
                    BLUETOOTH_PERMISSION_REQUEST_CODE);
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[] { Manifest.permission.ACCESS_FINE_LOCATION },
                    BLUETOOTH_PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == BLUETOOTH_PERMISSION_REQUEST_CODE) {
            boolean allPermissionsGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false;
                    break;
                }
            }

            if (allPermissionsGranted) {
                Log.d(TAG, "onRequestPermissionsResult: Bluetooth permissions granted");
                connectToArduinoDevice();
            } else {
                Log.w(TAG, "onRequestPermissionsResult: Bluetooth permissions denied");
                statusTextView.setText("Status: Bluetooth permissions denied - using demo mode");
                showDummyData();
            }
        }
    }

    private void startBluetoothService() {
        try {
            Log.d(TAG, "startBluetoothService: Starting Bluetooth service");
            Intent bluetoothIntent = new Intent(this, BluetoothService.class);
            startService(bluetoothIntent);
            bindService(bluetoothIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            Log.e(TAG, "startBluetoothService: Error starting Bluetooth service", e);
            statusTextView.setText("Status: Bluetooth service error - using demo mode");
            showDummyData();
        }
    }

    private void showDummyData() {
        Log.d(TAG, "showDummyData: Showing dummy health data");

        currentHealthData = new HealthData();
        currentHealthData.pulse = 75;
        currentHealthData.temperature = 36.8f;
        currentHealthData.humidity = 45.2f;
        currentHealthData.ekg = 120.5f;

        updateUI(currentHealthData);
        statusTextView.setText("Status: Demo mode - using sample data");
    }

    private void initializeUI() {
        Log.d(TAG, "initializeUI: Initializing UI elements");

        try {
            pulseTextView = findViewById(R.id.pulseTextView);
            tempTextView = findViewById(R.id.tempTextView);
            humidityTextView = findViewById(R.id.humidityTextView);
            ekgTextView = findViewById(R.id.ekgTextView);
            statusTextView = findViewById(R.id.statusTextView);
            recommendationsButton = findViewById(R.id.recommendationsButton);
            saveDataButton = findViewById(R.id.saveDataButton);
            ecgButton = findViewById(R.id.ecgButton);

            // Set initial status
            statusTextView.setText("Status: Initializing...");

            // Initialize save button
            saveDataButton.setOnClickListener(v -> {
                Log.d(TAG, "Save data button clicked");
                try {
                    if (currentHealthData != null) {
                        saveToDatabase(currentHealthData);
                        Toast.makeText(this, "Data saved successfully", Toast.LENGTH_SHORT).show();
                    } else {
                        Log.w(TAG, "No data to save");
                        Toast.makeText(this, "No data to save", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error saving data", e);
                    Toast.makeText(this, "Error saving data: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });

            ecgButton.setOnClickListener(v -> {
                Log.d(TAG, "ECG button clicked");
                try {
                    Intent intent = new Intent(HealthDataActivity.this, ECGMonitoringActivity.class);
                    startActivity(intent);
                } catch (Exception e) {
                    Log.e(TAG, "Error opening ECG activity", e);
                    Toast.makeText(this, "Error opening ECG monitor", Toast.LENGTH_SHORT).show();
                }
            });

            recommendationsButton.setOnClickListener(v -> {
                Log.d(TAG, "Recommendations button clicked");
                try {
                    Intent intent = new Intent(HealthDataActivity.this, RecommendationsActivity.class);
                    startActivity(intent);
                } catch (Exception e) {
                    Log.e(TAG, "Error opening recommendations activity", e);
                    Toast.makeText(this, "Error opening recommendations", Toast.LENGTH_SHORT).show();
                }
            });

            Log.d(TAG, "initializeUI: UI elements initialized successfully");

        } catch (Exception e) {
            Log.e(TAG, "initializeUI: Error initializing UI", e);
            Toast.makeText(this, "UI initialization error", Toast.LENGTH_SHORT).show();
        }
    }

    private void connectToArduinoDevice() {
        if (!isBluetoothSupported || bluetoothService == null) {
            Log.w(TAG, "connectToArduinoDevice: Bluetooth not supported or service not available");
            return;
        }

        try {
            Log.d(TAG, "connectToArduinoDevice: Attempting to connect to Arduino: " + ARDUINO_BLUETOOTH_ADDRESS);

            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (bluetoothAdapter == null) {
                Log.e(TAG, "connectToArduinoDevice: Bluetooth adapter is null");
                statusTextView.setText("Status: Bluetooth not available");
                return;
            }

            if (!bluetoothAdapter.isEnabled()) {
                Log.w(TAG, "connectToArduinoDevice: Bluetooth not enabled");
                statusTextView.setText("Status: Please enable Bluetooth");
                return;
            }

            if (checkBluetoothPermissions()) {
                BluetoothDevice device = bluetoothAdapter.getRemoteDevice(ARDUINO_BLUETOOTH_ADDRESS);
                if (device != null && bluetoothService != null) {
                    Log.d(TAG, "connectToArduinoDevice: Connecting to device");
                    bluetoothService.connectToDevice(device);
                    statusTextView.setText("Status: Connecting to Arduino...");
                } else {
                    Log.e(TAG, "connectToArduinoDevice: Device not found or service not available");
                    statusTextView.setText("Status: Arduino device not found");
                }
            } else {
                Log.w(TAG, "connectToArduinoDevice: Missing Bluetooth permissions");
                statusTextView.setText("Status: Bluetooth permissions required");
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "connectToArduinoDevice: Invalid MAC address: " + ARDUINO_BLUETOOTH_ADDRESS, e);
            statusTextView.setText("Status: Invalid Arduino MAC address");
        } catch (SecurityException e) {
            Log.e(TAG, "connectToArduinoDevice: Security exception", e);
            statusTextView.setText("Status: Bluetooth permission denied");
        } catch (Exception e) {
            Log.e(TAG, "connectToArduinoDevice: Unexpected error", e);
            statusTextView.setText("Status: Connection error");
        }
    }

    private void updateUI(HealthData data) {
        if (data == null) {
            Log.w(TAG, "updateUI: HealthData is null");
            return;
        }

        try {
            Log.d(TAG,
                    "updateUI: Updating UI with pulse: " + data.pulse + ", temp: " + data.temperature + ", humidity: "
                            + data.humidity + ", ekg: " + data.ekg);

            runOnUiThread(() -> {
                try {
                    if (pulseTextView != null) {
                        pulseTextView.setText(String.format(Locale.getDefault(), "Puls: %d bpm", data.pulse));
                    }
                    if (tempTextView != null) {
                        tempTextView
                                .setText(String.format(Locale.getDefault(), "Temperatură: %.1f°C", data.temperature));
                    }
                    if (humidityTextView != null) {
                        humidityTextView
                                .setText(String.format(Locale.getDefault(), "Umiditate: %.1f%%", data.humidity));
                    }
                    if (ekgTextView != null) {
                        ekgTextView.setText(String.format(Locale.getDefault(), "EKG: %.1f mV", data.ekg));
                    }
                } catch (Exception e) {
                    Log.e(TAG, "updateUI: Error updating UI elements", e);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "updateUI: Error in updateUI", e);
        }
    }

    private void checkThresholds(HealthData data) {
        if (data == null) {
            Log.w(TAG, "checkThresholds: HealthData is null");
            return;
        }

        try {
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

            if (data.ekg > MAX_EKG) {
                alertMessage.append("High EKG! ");
                Log.w(TAG, "checkThresholds: High EKG detected: " + data.ekg);
            } else if (data.ekg < MIN_EKG) {
                alertMessage.append("Low EKG! ");
                Log.w(TAG, "checkThresholds: Low EKG detected: " + data.ekg);
            }

            if (alertMessage.length() > 0) {
                Toast.makeText(this, alertMessage.toString(), Toast.LENGTH_LONG).show();
                if (statusTextView != null) {
                    statusTextView.setText("ALERT: " + alertMessage.toString());
                }
                Log.i(TAG, "checkThresholds: Alert triggered: " + alertMessage.toString());
            } else {
                if (statusTextView != null) {
                    statusTextView.setText("Status: All values normal - Connected to Arduino");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "checkThresholds: Error checking thresholds", e);
        }
    }

    private void saveToDatabase(HealthData data) {
        if (data == null) {
            Log.w(TAG, "saveToDatabase: HealthData is null");
            return;
        }

        if (currentUserId.isEmpty()) {
            Log.e(TAG, "saveToDatabase: No user ID available");
            return;
        }

        try {
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

            // Save EKG data
            Map<String, Object> ekgRecord = new HashMap<>();
            ekgRecord.put("valoare", data.ekg);
            ekgRecord.put("timestamp", timestamp);
            ekgRecord.put("pacientID", currentUserId);

            db.collection("ekg")
                    .add(ekgRecord)
                    .addOnSuccessListener(documentReference -> {
                        Log.d(TAG, "saveToDatabase: EKG data saved with ID: " + documentReference.getId());
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "saveToDatabase: Error saving EKG data", e);
                    });

            // Save complete health data
            Map<String, Object> healthRecord = new HashMap<>();
            healthRecord.put("temperatura", data.temperature);
            healthRecord.put("puls", data.pulse);
            healthRecord.put("umiditate", data.humidity);
            healthRecord.put("ekg", data.ekg);
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

        } catch (Exception e) {
            Log.e(TAG, "saveToDatabase: Error saving to database", e);
            Toast.makeText(this, "Error saving to database: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // BLUETOOTH DATA LISTENER METHODS
    @Override
    public void onDataReceived(String data) {
        if (data == null || data.trim().isEmpty()) {
            Log.w(TAG, "onDataReceived: Received null or empty data");
            return;
        }

        Log.d(TAG, "onDataReceived: Received data from Arduino: " + data);
        try {
            // Try to parse JSON data from Arduino
            currentHealthData = new Gson().fromJson(data, HealthData.class);

            if (currentHealthData != null) {
                updateUI(currentHealthData);
                checkThresholds(currentHealthData);
                Log.d(TAG, "onDataReceived: UI updated with new Arduino data");
            } else {
                Log.w(TAG, "onDataReceived: Parsed data is null");
            }
        } catch (JsonSyntaxException e) {
            Log.e(TAG, "onDataReceived: JSON parsing error for data: " + data, e);
            runOnUiThread(() -> {
                if (statusTextView != null) {
                    statusTextView.setText("Status: Data format error from Arduino");
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "onDataReceived: Unexpected error processing data", e);
        }
    }

    @Override
    public void onConnectionStatusChanged(boolean isConnected) {
        Log.d(TAG, "onConnectionStatusChanged: Connection status changed to: " + isConnected);
        try {
            runOnUiThread(() -> {
                try {
                    if (statusTextView != null) {
                        if (isConnected) {
                            statusTextView.setText("Status: Connected to Arduino device");
                            Log.i(TAG, "onConnectionStatusChanged: Successfully connected to Arduino");
                        } else {
                            statusTextView.setText("Status: Disconnected - attempting to reconnect");
                            Log.w(TAG, "onConnectionStatusChanged: Disconnected from Arduino, attempting reconnect");
                            // Attempt to reconnect
                            if (bluetoothService != null && checkBluetoothPermissions()) {
                                connectToArduinoDevice();
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "onConnectionStatusChanged: Error updating UI", e);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "onConnectionStatusChanged: Error handling connection status change", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            Log.d(TAG, "onDestroy: Cleaning up Bluetooth service connection");
            // Unbind from the service
            if (isBound && serviceConnection != null) {
                unbindService(serviceConnection);
                isBound = false;
            }
        } catch (Exception e) {
            Log.e(TAG, "onDestroy: Error during cleanup", e);
        }
    }

    // Data model class for Arduino JSON data
    private static class HealthData {
        int pulse = 0;
        float temperature = 0.0f;
        float humidity = 0.0f;
        float ekg = 0.0f;
    }
}