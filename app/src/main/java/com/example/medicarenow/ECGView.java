package com.example.medicarenow;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.LinkedList;
import java.util.Queue;

public class ECGView extends View {
    private static final int MAX_POINTS = 500; // Reduced for better performance
    private static final int SAMPLING_RATE_HZ = 200;

    private Paint paint;
    private Path path;
    private Queue<Float> ecgData;
    private int samplesPerBeat = (60 * SAMPLING_RATE_HZ) / 72; // Default 72 BPM
    private int sampleCount = 0;
    private boolean hasRealData = false;
    private float lastRealEkgValue = 0f;
    private static final float FLAT_LINE_VALUE = 0f; // Flat line when no data

    public ECGView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paint = new Paint();
        paint.setColor(Color.GREEN);
        paint.setStrokeWidth(6f); // Thicker line for better visibility
        paint.setStyle(Paint.Style.STROKE);
        paint.setAntiAlias(true);

        path = new Path();
        ecgData = new LinkedList<>();

        // Initialize with flat line - always start with no data
        for (int i = 0; i < MAX_POINTS; i++) {
            ecgData.add(FLAT_LINE_VALUE);
        }
    }

    public void setHeartRate(int bpm) {
        samplesPerBeat = (60 * SAMPLING_RATE_HZ) / bpm;
        invalidate();
    }

    // Method to add real EKG data
    public void addRealEKGValue(float ekgValue) {
        android.util.Log.d("ECGView", "addRealEKGValue: Adding EKG value: " + ekgValue);

        hasRealData = true;
        lastRealEkgValue = ekgValue;

        // Normalize EKG value to display range (-1 to 1)
        float normalizedValue = normalizeEKGValue(ekgValue);
        android.util.Log.d("ECGView", "addRealEKGValue: Normalized value: " + normalizedValue);

        // Add to data queue (shift the wave to the left)
        ecgData.poll();
        ecgData.add(normalizedValue);

        // NO ANIMATION - just add the real data point

        // Trigger immediate redraw
        invalidate();
    }

    // Method to reset to flat line when no data available
    public void resetToFlatLine() {
        android.util.Log.d("ECGView", "resetToFlatLine: Resetting to flat line");

        hasRealData = false;
        // No animation to stop - we never animate

        // Fill queue with flat line values
        ecgData.clear();
        for (int i = 0; i < MAX_POINTS; i++) {
            ecgData.add(FLAT_LINE_VALUE);
        }

        invalidate();
    }

    // Check if we have real data
    public boolean hasRealData() {
        return hasRealData;
    }

    // NO ANIMATION - Only real data points, no random generation!
    // ECGView will only update when real data is received via addRealEKGValue()

    // No animation methods needed - we only use real data

    // Normalize EKG values to display range
    private float normalizeEKGValue(float rawValue) {
        // Updated for values around 300 - wider range and better scaling
        float minEKG = 200f; // Lower bound for ~300 values
        float maxEKG = 400f; // Upper bound for ~300 values

        // Center value around 300
        float centerValue = 300f;

        // Create variation around the center value
        float deviation = rawValue - centerValue;

        // Scale the deviation to make it more visible (-2 to +2 range for wider graph)
        float scaledDeviation = (deviation / 50f); // Divide by 50 to get good range

        // Clamp to reasonable display range
        float normalizedValue = Math.max(-2f, Math.min(2f, scaledDeviation));

        android.util.Log.d("ECGView", "normalizeEKGValue: Raw=" + rawValue +
                ", Deviation=" + deviation + ", Normalized=" + normalizedValue);

        return normalizedValue;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float width = getWidth();
        float height = getHeight();
        float centerY = height / 2;
        float scale = height * 0.3f; // Increased scale for wider amplitude
        float pixelsPerPoint = width / MAX_POINTS;

        // Draw the path
        path.reset();
        int i = 0;
        float minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;

        for (Float point : ecgData) {
            float x = i * pixelsPerPoint;
            float y = centerY - (point * scale);

            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);

            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
            i++;
        }

        canvas.drawPath(path, paint);

        // Debug logging occasionally
        if (hasRealData && System.currentTimeMillis() % 1000 < 50) {
            android.util.Log.d("ECGView",
                    "onDraw: Drawing " + ecgData.size() + " points, Y range: " + minY + " to " + maxY);
        }
    }

    // NO SIMULATION METHODS - Only real data from Arduino!
}