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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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

    // Data buffer system for rapid incoming data
    private StringBuilder dataBuffer = new StringBuilder();
    private Handler bufferHandler = new Handler(Looper.getMainLooper());
    private Runnable bufferProcessor;
    private static final int BUFFER_DELAY_MS = 50; // Process buffer every 50ms - ULTRA FAST!
    private static final int MAX_BUFFER_SIZE = 1024; // Max buffer size in characters
    private long lastUpdateTime = 0;
    private static final long MIN_UPDATE_INTERVAL_MS = 50; // Minimum 50ms between UI updates - INSTANT!

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
            Log.d(TAG, "onCreate: Layout set successfully");

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

            // Initialize UI elements FIRST
            initializeUI();
            Log.d(TAG, "onCreate: UI initialized successfully");

            // Check if Bluetooth is supported
            checkBluetoothSupport();
            Log.d(TAG, "onCreate: Bluetooth support check completed. Supported: " + isBluetoothSupported);

            // Always show demo data initially
            Log.d(TAG, "onCreate: Showing initial demo data");
            showDummyData();

            // Start and bind to BluetoothService only if Bluetooth is supported
            if (isBluetoothSupported) {
                Log.d(TAG, "onCreate: Starting Bluetooth service");
                startBluetoothService();
            } else {
                Log.d(TAG, "onCreate: Bluetooth not supported, staying in demo mode");
            }

            Log.d(TAG, "onCreate: HealthDataActivity initialized successfully");

        } catch (Exception e) {
            Log.e(TAG, "onCreate: Critical error during initialization", e);
            Toast.makeText(this, "Error initializing app: " + e.getMessage(), Toast.LENGTH_LONG).show();
            // Don't finish, try to show demo data
            try {
                initializeUI();
                showDummyData();
            } catch (Exception e2) {
                Log.e(TAG, "onCreate: Failed to show demo data", e2);
                finish();
            }
        }
    }

    private void checkBluetoothSupport() {
        try {
            Log.d(TAG, "checkBluetoothSupport: Checking Bluetooth support");
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            isBluetoothSupported = bluetoothAdapter != null;

            Log.d(TAG, "checkBluetoothSupport: BluetoothAdapter: " + (bluetoothAdapter != null ? "Available" : "NULL"));

            if (!isBluetoothSupported) {
                Log.w(TAG, "checkBluetoothSupport: Bluetooth not supported on this device");
                updateStatus("Status: Bluetooth not supported - using demo mode");
            } else {
                Log.d(TAG, "checkBluetoothSupport: Bluetooth is supported");
                Log.d(TAG, "checkBluetoothSupport: Bluetooth enabled: " + bluetoothAdapter.isEnabled());
                updateStatus("Status: Bluetooth available - attempting connection");
            }
        } catch (Exception e) {
            Log.e(TAG, "checkBluetoothSupport: Error checking Bluetooth support", e);
            isBluetoothSupported = false;
            updateStatus("Status: Bluetooth check failed - using demo mode");
        }
    }

    private void updateStatus(String status) {
        Log.d(TAG, "updateStatus: " + status);
        runOnUiThread(() -> {
            if (statusTextView != null) {
                statusTextView.setText(status);
            } else {
                Log.w(TAG, "updateStatus: statusTextView is null");
            }
        });
    }

    private boolean checkBluetoothPermissions() {
        boolean hasPermissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermissions = ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this,
                            Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
            Log.d(TAG, "checkBluetoothPermissions: Android 12+ permissions check: " + hasPermissions);
        } else {
            hasPermissions = ContextCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            Log.d(TAG, "checkBluetoothPermissions: Legacy permissions check: " + hasPermissions);
        }
        return hasPermissions;
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

        Log.d(TAG, "onRequestPermissionsResult: Request code: " + requestCode);

        if (requestCode == BLUETOOTH_PERMISSION_REQUEST_CODE) {
            boolean allPermissionsGranted = true;
            for (int i = 0; i < grantResults.length; i++) {
                Log.d(TAG, "onRequestPermissionsResult: Permission " + permissions[i] + " = " +
                        (grantResults[i] == PackageManager.PERMISSION_GRANTED ? "GRANTED" : "DENIED"));
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false;
                }
            }

            if (allPermissionsGranted) {
                Log.d(TAG, "onRequestPermissionsResult: All Bluetooth permissions granted");
                updateStatus("Status: Permissions granted - connecting to Arduino");
                connectToArduinoDevice();
            } else {
                Log.w(TAG, "onRequestPermissionsResult: Some Bluetooth permissions denied");
                updateStatus("Status: Bluetooth permissions denied - using demo mode");
                // Demo data is already shown, just update status
            }
        }
    }

    private void startBluetoothService() {
        try {
            Log.d(TAG, "startBluetoothService: Starting Bluetooth service");
            Intent bluetoothIntent = new Intent(this, BluetoothService.class);
            startService(bluetoothIntent);
            boolean bindResult = bindService(bluetoothIntent, serviceConnection, Context.BIND_AUTO_CREATE);
            Log.d(TAG, "startBluetoothService: Bind service result: " + bindResult);
        } catch (Exception e) {
            Log.e(TAG, "startBluetoothService: Error starting Bluetooth service", e);
            updateStatus("Status: Bluetooth service error - using demo mode");
        }
    }

    private void showDummyData() {
        Log.d(TAG, "showDummyData: Creating and displaying demo health data");

        try {
            currentHealthData = new HealthData();
            // Use realistic but non-hardcoded values
            currentHealthData.pulse = 72 + (int) (Math.random() * 10); // 72-82 bpm
            currentHealthData.temperature = 36.5f + (float) (Math.random() * 1.0); // 36.5-37.5°C
            currentHealthData.humidity = 40.0f + (float) (Math.random() * 20.0); // 40-60%
            currentHealthData.ekg = 100.0f + (float) (Math.random() * 40.0); // 100-140 mV

            Log.d(TAG, "showDummyData: Created demo data - Pulse: " + currentHealthData.pulse +
                    ", Temp: " + currentHealthData.temperature + ", Humidity: " + currentHealthData.humidity +
                    ", EKG: " + currentHealthData.ekg);

            updateUI(currentHealthData);
            updateStatus("Status: Demo mode - using sample data");

            Log.d(TAG, "showDummyData: Demo data displayed successfully");
        } catch (Exception e) {
            Log.e(TAG, "showDummyData: Error creating demo data", e);
            updateStatus("Status: Error creating demo data");
        }
    }

    private void initializeUI() {
        Log.d(TAG, "initializeUI: Starting UI initialization");

        try {
            pulseTextView = findViewById(R.id.pulseTextView);
            tempTextView = findViewById(R.id.tempTextView);
            humidityTextView = findViewById(R.id.humidityTextView);
            ekgTextView = findViewById(R.id.ekgTextView);
            statusTextView = findViewById(R.id.statusTextView);
            recommendationsButton = findViewById(R.id.recommendationsButton);
            saveDataButton = findViewById(R.id.saveDataButton);
            ecgButton = findViewById(R.id.ecgButton);

            // Log which views were found
            Log.d(TAG, "initializeUI: View initialization results:");
            Log.d(TAG, "  - pulseTextView: " + (pulseTextView != null ? "OK" : "NULL"));
            Log.d(TAG, "  - tempTextView: " + (tempTextView != null ? "OK" : "NULL"));
            Log.d(TAG, "  - humidityTextView: " + (humidityTextView != null ? "OK" : "NULL"));
            Log.d(TAG, "  - ekgTextView: " + (ekgTextView != null ? "OK" : "NULL"));
            Log.d(TAG, "  - statusTextView: " + (statusTextView != null ? "OK" : "NULL"));
            Log.d(TAG, "  - saveDataButton: " + (saveDataButton != null ? "OK" : "NULL"));
            Log.d(TAG, "  - ecgButton: " + (ecgButton != null ? "OK" : "NULL"));
            Log.d(TAG, "  - recommendationsButton: " + (recommendationsButton != null ? "OK" : "NULL"));

            // Set initial values
            if (pulseTextView != null)
                pulseTextView.setText("Puls: -- bpm");
            if (tempTextView != null)
                tempTextView.setText("Temperatură: --°C");
            if (humidityTextView != null)
                humidityTextView.setText("Umiditate: --%");
            if (ekgTextView != null)
                ekgTextView.setText("EKG: -- mV");

            updateStatus("Status: Initializing...");

            // Initialize save button
            if (saveDataButton != null) {
                saveDataButton.setOnClickListener(v -> {
                    Log.d(TAG, "Save data button clicked");
                    try {
                        if (currentHealthData != null) {
                            Log.d(TAG, "Saving current health data: " + currentHealthData.pulse + ", " +
                                    currentHealthData.temperature + ", " + currentHealthData.humidity + ", "
                                    + currentHealthData.ekg);
                            saveToDatabase(currentHealthData);
                            Toast.makeText(this, "Data saved successfully", Toast.LENGTH_SHORT).show();
                        } else {
                            Log.w(TAG, "No data to save - currentHealthData is null");
                            Toast.makeText(this, "No data to save", Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error saving data", e);
                        Toast.makeText(this, "Error saving data: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
            }

            if (ecgButton != null) {
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
            }

            if (recommendationsButton != null) {
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
            }

            Log.d(TAG, "initializeUI: UI elements initialized successfully");

        } catch (Exception e) {
            Log.e(TAG, "initializeUI: Error initializing UI", e);
            Toast.makeText(this, "UI initialization error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
            Log.d(TAG, "updateUI: Starting UI update");
            Log.d(TAG, "updateUI: Data values - Pulse: " + data.pulse + ", Temp: " + data.temperature +
                    ", Humidity: " + data.humidity + ", EKG: " + data.ekg);
            Log.d(TAG, "updateUI: UI Views status - Pulse: " + (pulseTextView != null) +
                    ", Temp: " + (tempTextView != null) + ", Humidity: " + (humidityTextView != null) +
                    ", EKG: " + (ekgTextView != null));

            runOnUiThread(() -> {
                try {
                    if (pulseTextView != null) {
                        String pulseText = String.format(Locale.getDefault(), "Puls: %d bpm", data.pulse);
                        pulseTextView.setText(pulseText);
                        Log.d(TAG, "updateUI: Set pulse text: " + pulseText);
                    } else {
                        Log.w(TAG, "updateUI: pulseTextView is null");
                    }

                    if (tempTextView != null) {
                        String tempText = String.format(Locale.getDefault(), "Temperatură: %.1f°C", data.temperature);
                        tempTextView.setText(tempText);
                        Log.d(TAG, "updateUI: Set temperature text: " + tempText);
                    } else {
                        Log.w(TAG, "updateUI: tempTextView is null");
                    }

                    if (humidityTextView != null) {
                        String humidityText = String.format(Locale.getDefault(), "Umiditate: %.1f%%", data.humidity);
                        humidityTextView.setText(humidityText);
                        Log.d(TAG, "updateUI: Set humidity text: " + humidityText);
                    } else {
                        Log.w(TAG, "updateUI: humidityTextView is null");
                    }

                    if (ekgTextView != null) {
                        String ekgText = String.format(Locale.getDefault(), "EKG: %.1f mV", data.ekg);
                        ekgTextView.setText(ekgText);
                        Log.d(TAG, "updateUI: Set EKG text: " + ekgText);
                    } else {
                        Log.w(TAG, "updateUI: ekgTextView is null");
                    }

                    Log.d(TAG, "updateUI: All UI elements updated successfully");
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

        Log.d(TAG, "onDataReceived: Processing data chunk: '" + data + "' (length: " + data.length() + ")");

        // TRY IMMEDIATE PARSING FIRST for instant UI updates!
        try {
            String trimmedData = data.trim();

            // Check if we have a complete JSON object
            if (trimmedData.startsWith("{") && trimmedData.endsWith("}")) {
                HealthData parsedData = parseArduinoData(trimmedData);
                if (parsedData != null && isValidHealthData(parsedData)) {
                    Log.d(TAG, "onDataReceived: INSTANT PARSE SUCCESS! Updating UI immediately");

                    runOnUiThread(() -> {
                        currentHealthData = parsedData;
                        updateUI(currentHealthData);
                        checkThresholds(currentHealthData);
                        saveToDatabase(currentHealthData);
                    });

                    lastUpdateTime = System.currentTimeMillis();
                    return; // Exit early - we got perfect data instantly!
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "onDataReceived: Instant parsing failed, falling back to buffer: " + e.getMessage());
        }

        // FALLBACK: Use buffer system for incomplete/complex data
        synchronized (dataBuffer) {
            try {
                // Add new data to buffer
                dataBuffer.append(data);

                // Check buffer size limit
                if (dataBuffer.length() > MAX_BUFFER_SIZE) {
                    Log.w(TAG, "onDataReceived: Buffer overflow, clearing old data");
                    // Keep only the last half of the buffer
                    String bufferContent = dataBuffer.toString();
                    dataBuffer.setLength(0);
                    dataBuffer.append(bufferContent.substring(bufferContent.length() / 2));
                }

                Log.d(TAG, "onDataReceived: Buffer size now: " + dataBuffer.length() + " characters");

                // Schedule buffer processing (cancel previous if exists)
                if (bufferProcessor != null) {
                    bufferHandler.removeCallbacks(bufferProcessor);
                }

                bufferProcessor = new Runnable() {
                    @Override
                    public void run() {
                        processDataBuffer();
                    }
                };

                bufferHandler.postDelayed(bufferProcessor, BUFFER_DELAY_MS);

            } catch (Exception e) {
                Log.e(TAG, "onDataReceived: Error handling data buffer", e);
            }
        }
    }

    private void processDataBuffer() {
        synchronized (dataBuffer) {
            if (dataBuffer.length() == 0) {
                Log.d(TAG, "processDataBuffer: Buffer is empty, nothing to process");
                return;
            }

            try {
                String bufferContent = dataBuffer.toString();
                Log.d(TAG, "processDataBuffer: Processing buffer content: '" + bufferContent + "'");

                // Clear the buffer
                dataBuffer.setLength(0);

                // Find the most recent complete JSON object
                HealthData latestData = findLatestValidData(bufferContent);

                if (latestData != null) {
                    // Update UI immediately from buffer too!
                    currentHealthData = latestData;
                    updateUI(currentHealthData);
                    checkThresholds(currentHealthData);
                    saveToDatabase(currentHealthData);
                    lastUpdateTime = System.currentTimeMillis();
                    Log.d(TAG, "processDataBuffer: UI updated with latest data from buffer");
                } else {
                    Log.w(TAG, "processDataBuffer: No valid data found in buffer");
                }

            } catch (Exception e) {
                Log.e(TAG, "processDataBuffer: Error processing buffer", e);
            }
        }
    }

    private HealthData findLatestValidData(String bufferContent) {
        try {
            Log.d(TAG, "findLatestValidData: Searching for JSON objects in buffer");

            HealthData latestValidData = null;
            int searchStart = 0;

            // Find all JSON objects in the buffer and keep the last valid one
            while (searchStart < bufferContent.length()) {
                int startIndex = bufferContent.indexOf('{', searchStart);
                if (startIndex == -1) {
                    break; // No more JSON objects
                }

                int endIndex = bufferContent.indexOf('}', startIndex);
                if (endIndex == -1) {
                    break; // Incomplete JSON object
                }

                String jsonData = bufferContent.substring(startIndex, endIndex + 1);
                Log.d(TAG, "findLatestValidData: Found JSON candidate: '" + jsonData + "'");

                HealthData parsedData = parseArduinoData(jsonData);
                if (parsedData != null) {
                    latestValidData = parsedData;
                    Log.d(TAG, "findLatestValidData: Valid data found, keeping as latest");
                }

                searchStart = endIndex + 1;
            }

            if (latestValidData != null) {
                Log.d(TAG, "findLatestValidData: Returning latest valid data - Pulse: " +
                        latestValidData.pulse + ", Temp: " + latestValidData.temperature +
                        ", Humidity: " + latestValidData.humidity + ", EKG: " + latestValidData.ekg);
            } else {
                Log.w(TAG, "findLatestValidData: No valid JSON data found in buffer");
            }

            return latestValidData;

        } catch (Exception e) {
            Log.e(TAG, "findLatestValidData: Error searching for valid data", e);
            return null;
        }
    }

    private HealthData parseArduinoData(String jsonData) {
        try {
            Log.d(TAG, "parseArduinoData: Parsing JSON: " + jsonData);

            // Use Gson to parse with flexible field names
            Gson gson = new Gson();
            ArduinoDataRaw rawData = gson.fromJson(jsonData, ArduinoDataRaw.class);

            if (rawData != null) {
                HealthData healthData = new HealthData();

                // Handle pulse field (could be "pulse", "0", or missing)
                if (rawData.pulse != null) {
                    healthData.pulse = rawData.pulse.intValue();
                    Log.d(TAG, "parseArduinoData: Found pulse: " + healthData.pulse);
                } else {
                    // Keep previous pulse value or use default
                    healthData.pulse = (currentHealthData != null) ? currentHealthData.pulse : 75;
                    Log.d(TAG, "parseArduinoData: No pulse data, using: " + healthData.pulse);
                }

                // Handle temperature
                if (rawData.temperature != null) {
                    healthData.temperature = rawData.temperature.floatValue();
                    Log.d(TAG, "parseArduinoData: Found temperature: " + healthData.temperature);
                } else {
                    healthData.temperature = (currentHealthData != null) ? currentHealthData.temperature : 36.5f;
                    Log.d(TAG, "parseArduinoData: No temperature data, using: " + healthData.temperature);
                }

                // Handle humidity
                if (rawData.humidity != null) {
                    healthData.humidity = rawData.humidity.floatValue();
                    Log.d(TAG, "parseArduinoData: Found humidity: " + healthData.humidity);
                } else {
                    healthData.humidity = (currentHealthData != null) ? currentHealthData.humidity : 45.0f;
                    Log.d(TAG, "parseArduinoData: No humidity data, using: " + healthData.humidity);
                }

                // Handle EKG (could be "ekg", "ecg", or missing)
                if (rawData.ekg != null) {
                    healthData.ekg = rawData.ekg.floatValue();
                    Log.d(TAG, "parseArduinoData: Found EKG: " + healthData.ekg);
                } else if (rawData.ecg != null) {
                    healthData.ekg = rawData.ecg.floatValue();
                    Log.d(TAG, "parseArduinoData: Found ECG: " + healthData.ekg);
                } else {
                    healthData.ekg = (currentHealthData != null) ? currentHealthData.ekg : 120.0f;
                    Log.d(TAG, "parseArduinoData: No EKG data, using: " + healthData.ekg);
                }

                Log.d(TAG, "parseArduinoData: Successfully parsed - Pulse: " + healthData.pulse +
                        ", Temp: " + healthData.temperature + ", Humidity: " + healthData.humidity +
                        ", EKG: " + healthData.ekg);

                return healthData;
            } else {
                Log.w(TAG, "parseArduinoData: Gson returned null for data: " + jsonData);
                return null;
            }
        } catch (JsonSyntaxException e) {
            Log.e(TAG, "parseArduinoData: JSON parsing error for data: " + jsonData, e);

            // Try manual parsing as fallback
            return parseManually(jsonData);
        } catch (Exception e) {
            Log.e(TAG, "parseArduinoData: Unexpected error parsing data: " + jsonData, e);
            return null;
        }
    }

    private boolean isValidHealthData(HealthData data) {
        if (data == null)
            return false;

        // Check if values are within reasonable ranges
        boolean validPulse = data.pulse > 30 && data.pulse < 200;
        boolean validTemp = data.temperature > 30.0f && data.temperature < 45.0f;
        boolean validHumidity = data.humidity > 0.0f && data.humidity < 100.0f;
        boolean validEkg = data.ekg > 0.0f && data.ekg < 300.0f;

        Log.d(TAG, "isValidHealthData: Pulse:" + validPulse + " Temp:" + validTemp +
                " Humidity:" + validHumidity + " EKG:" + validEkg);

        return validPulse && validTemp && validHumidity && validEkg;
    }

    private HealthData parseManually(String jsonData) {
        try {
            Log.d(TAG, "parseManually: Attempting manual parsing for: " + jsonData);

            HealthData healthData = new HealthData();
            boolean foundAnyData = false;

            // Extract temperature
            if (jsonData.contains("temperature")) {
                String tempPattern = "\"temperature\"\\s*:\\s*([0-9]+\\.?[0-9]*)";
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(tempPattern);
                java.util.regex.Matcher matcher = pattern.matcher(jsonData);
                if (matcher.find()) {
                    healthData.temperature = Float.parseFloat(matcher.group(1));
                    foundAnyData = true;
                    Log.d(TAG, "parseManually: Extracted temperature: " + healthData.temperature);
                }
            }

            // Extract humidity
            if (jsonData.contains("humidity")) {
                String humPattern = "\"humidity\"\\s*:\\s*([0-9]+\\.?[0-9]*)";
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(humPattern);
                java.util.regex.Matcher matcher = pattern.matcher(jsonData);
                if (matcher.find()) {
                    healthData.humidity = Float.parseFloat(matcher.group(1));
                    foundAnyData = true;
                    Log.d(TAG, "parseManually: Extracted humidity: " + healthData.humidity);
                }
            }

            // Extract pulse (look for any numeric value that could be pulse)
            if (jsonData.contains("pulse") || jsonData.contains("\"0\"")) {
                String pulsePattern = "\"(?:pulse|0)\"\\s*:\\s*([0-9]+)";
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(pulsePattern);
                java.util.regex.Matcher matcher = pattern.matcher(jsonData);
                if (matcher.find()) {
                    int pulseValue = Integer.parseInt(matcher.group(1));
                    if (pulseValue > 0 && pulseValue < 200) { // Reasonable pulse range
                        healthData.pulse = pulseValue;
                        foundAnyData = true;
                        Log.d(TAG, "parseManually: Extracted pulse: " + healthData.pulse);
                    }
                }
            }

            // Extract EKG/ECG
            if (jsonData.contains("ekg") || jsonData.contains("ecg")) {
                String ekgPattern = "\"(?:ekg|ecg)\"\\s*:\\s*([0-9]+\\.?[0-9]*)";
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(ekgPattern);
                java.util.regex.Matcher matcher = pattern.matcher(jsonData);
                if (matcher.find()) {
                    healthData.ekg = Float.parseFloat(matcher.group(1));
                    foundAnyData = true;
                    Log.d(TAG, "parseManually: Extracted EKG: " + healthData.ekg);
                }
            }

            // Fill missing values with current data or reasonable defaults
            if (healthData.pulse == 0) {
                healthData.pulse = (currentHealthData != null) ? currentHealthData.pulse : 75;
            }
            if (healthData.temperature == 0.0f) {
                healthData.temperature = (currentHealthData != null) ? currentHealthData.temperature : 36.5f;
            }
            if (healthData.humidity == 0.0f) {
                healthData.humidity = (currentHealthData != null) ? currentHealthData.humidity : 45.0f;
            }
            if (healthData.ekg == 0.0f) {
                healthData.ekg = (currentHealthData != null) ? currentHealthData.ekg : 120.0f;
            }

            if (foundAnyData) {
                Log.d(TAG, "parseManually: Successfully parsed some data");
                return healthData;
            } else {
                Log.w(TAG, "parseManually: No valid data found");
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "parseManually: Error in manual parsing", e);
            return null;
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
            Log.d(TAG, "onDestroy: Cleaning up resources");

            // Clean up buffer system
            if (bufferProcessor != null) {
                bufferHandler.removeCallbacks(bufferProcessor);
                bufferProcessor = null;
            }

            synchronized (dataBuffer) {
                dataBuffer.setLength(0);
            }

            // Unbind from the service
            if (isBound && serviceConnection != null) {
                unbindService(serviceConnection);
                isBound = false;
            }

            Log.d(TAG, "onDestroy: Cleanup completed");
        } catch (Exception e) {
            Log.e(TAG, "onDestroy: Error during cleanup", e);
        }
    }

    // Raw data class for flexible JSON parsing
    private static class ArduinoDataRaw {
        Number pulse; // Could be integer or string "0"
        Number temperature;
        Number humidity;
        Number ekg;
        Number ecg; // Alternative field name
    }

    // Data model class for Arduino JSON data
    private static class HealthData {
        int pulse = 0;
        float temperature = 0.0f;
        float humidity = 0.0f;
        float ekg = 0.0f;
    }
}