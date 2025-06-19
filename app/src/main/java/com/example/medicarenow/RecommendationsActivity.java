package com.example.medicarenow;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;

public class RecommendationsActivity extends AppCompatActivity {

    private TextView recommendationsText;
    private FirebaseFirestore db;
    private String currentUserEmail;
    private static final String TAG = "RecommendationsActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recommendations);

        recommendationsText = findViewById(R.id.recommendationsText);
        db = FirebaseFirestore.getInstance();

        // Get current user email from SharedPreferences
        SharedPreferences prefs = getSharedPreferences("MediCareNow", MODE_PRIVATE);
        currentUserEmail = prefs.getString("user_email", "");

        Log.d(TAG, "onCreate: Current user email: " + currentUserEmail);

        if (currentUserEmail.isEmpty()) {
            Log.w(TAG, "onCreate: No user email found in session");
            Toast.makeText(this, "Eroare: Nu s-a găsit sesiunea utilizatorului", Toast.LENGTH_SHORT).show();
            showLocalRecommendations();
            return;
        }

        // First, find the patient ID for this user by email
        findPatientIdAndLoadRecommendations();
    }

    private void findPatientIdAndLoadRecommendations() {
        Log.d(TAG, "findPatientIdAndLoadRecommendations: Searching for patient with email: " + currentUserEmail);

        // Query Firestore to find patient record with matching email
        db.collection("pacienti")
                .whereEqualTo("email", currentUserEmail)
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        if (task.getResult().isEmpty()) {
                            Log.w(TAG, "findPatientIdAndLoadRecommendations: No patient record found for email: "
                                    + currentUserEmail);
                            Toast.makeText(this, "Nu s-a găsit înregistrarea de pacient pentru utilizatorul curent",
                                    Toast.LENGTH_SHORT).show();
                            showLocalRecommendations();
                            return;
                        }

                        for (QueryDocumentSnapshot document : task.getResult()) {
                            String pacientId = document.getId(); // This is the document ID
                            Log.d(TAG, "findPatientIdAndLoadRecommendations: Found patient ID: " + pacientId);
                            loadRecommendationsForPatient(pacientId);
                            return; // Only process first matching patient
                        }
                    } else {
                        Log.e(TAG, "findPatientIdAndLoadRecommendations: Error querying patients", task.getException());
                        Toast.makeText(this, "Eroare la căutarea pacientului: " + task.getException().getMessage(),
                                Toast.LENGTH_SHORT).show();
                        showLocalRecommendations();
                    }
                });
    }

    private void loadRecommendationsForPatient(String pacientId) {
        Log.d(TAG, "loadRecommendationsForPatient: Loading recommendations for patient ID: " + pacientId);

        // Query Firestore to find recommendations where pacientID equals the patient
        // document ID
        db.collection("recomandari")
                .whereEqualTo("pacientID", pacientId)
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        if (task.getResult().isEmpty()) {
                            Log.w(TAG, "loadRecommendationsForPatient: No recommendations found for patient: "
                                    + pacientId);
                            showLocalRecommendations();
                            Toast.makeText(this, "Nu s-au găsit recomandări pentru acest pacient", Toast.LENGTH_SHORT)
                                    .show();
                            return;
                        }

                        StringBuilder recommendationsBuilder = new StringBuilder();
                        recommendationsBuilder.append("Recomandări medicale personalizate:\n\n");

                        for (QueryDocumentSnapshot document : task.getResult()) {
                            String tipRecomandare = document.getString("tipRecomandare");
                            String descriere = document.getString("descriere");
                            String status = document.getString("status");
                            Long progres = document.getLong("progres");

                            Log.d(TAG, "loadRecommendationsForPatient: Found recommendation - tip: " + tipRecomandare
                                    + ", status: " + status + ", progres: " + progres);

                            if (tipRecomandare != null && descriere != null) {
                                recommendationsBuilder.append("• ").append(tipRecomandare.toUpperCase()).append("\n");
                                recommendationsBuilder.append(descriere).append("\n");
                                if (status != null) {
                                    recommendationsBuilder.append("Status: ").append(status).append("\n");
                                }
                                if (progres != null) {
                                    recommendationsBuilder.append("Progres: ").append(progres).append("%\n");
                                    // Add progress bar visualization
                                    int progressBars = (int) (progres / 10); // 10% per bar
                                    StringBuilder progressBar = new StringBuilder();
                                    for (int i = 0; i < 10; i++) {
                                        if (i < progressBars) {
                                            progressBar.append("█");
                                        } else {
                                            progressBar.append("░");
                                        }
                                    }
                                    recommendationsBuilder.append("[").append(progressBar.toString()).append("]\n");
                                }
                                recommendationsBuilder.append("\n");
                            }
                        }

                        String finalRecommendations = recommendationsBuilder.toString().trim();
                        recommendationsText.setText(finalRecommendations);
                        Log.d(TAG, "loadRecommendationsForPatient: Recommendations loaded successfully");

                    } else {
                        Log.e(TAG, "loadRecommendationsForPatient: Error querying recommendations",
                                task.getException());
                        showLocalRecommendations();
                        Toast.makeText(this, "Eroare la încărcarea recomandărilor: " + task.getException().getMessage(),
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void showLocalRecommendations() {
        Log.d(TAG, "showLocalRecommendations: Showing default recommendations");
        String localRecommendations = "Recomandări medicale generale:\n\n" +
                "1. 30 de minute de mișcare zilnic\n" +
                "2. Dietă echilibrată cu reducere de sare\n" +
                "3. Minim 2 litri de apă pe zi\n" +
                "4. 7-8 ore de somn pe noapte\n" +
                "5. Măsurare regulată a tensiunii\n" +
                "6. Evitare stres\n" +
                "7. Control medical lunar";
        recommendationsText.setText(localRecommendations);
    }
}