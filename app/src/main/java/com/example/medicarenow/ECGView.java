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
        paint.setStrokeWidth(4f);
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
        hasRealData = true;
        lastRealEkgValue = ekgValue;

        // Normalize EKG value to display range (-1 to 1)
        float normalizedValue = normalizeEKGValue(ekgValue);

        // Add to data queue
        ecgData.poll();
        ecgData.add(normalizedValue);

        // Trigger redraw
        invalidate();
    }

    // Method to reset to flat line when no data available
    public void resetToFlatLine() {
        hasRealData = false;

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

    // Normalize EKG values to display range
    private float normalizeEKGValue(float rawValue) {
        // Assuming EKG values are in range 50-200, normalize to -1 to 1
        float minEKG = 50f;
        float maxEKG = 200f;

        // Clamp value to expected range
        float clampedValue = Math.max(minEKG, Math.min(maxEKG, rawValue));

        // Normalize to -1 to 1 range
        return ((clampedValue - minEKG) / (maxEKG - minEKG)) * 2f - 1f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float width = getWidth();
        float height = getHeight();
        float centerY = height / 2;
        float scale = height * 0.4f;
        float pixelsPerPoint = width / MAX_POINTS;

        // NEVER generate simulated data - only use real data or flat line
        // If we don't have real data, the queue already contains flat line values
        // The addRealEKGValue method handles adding real data points

        // Draw the path
        path.reset();
        int i = 0;
        for (Float point : ecgData) {
            float x = i * pixelsPerPoint;
            float y = centerY - (point * scale);

            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
            i++;
        }

        canvas.drawPath(path, paint);

        // No auto-refresh - only update when real data arrives
        // This keeps the flat line static when no data is available
    }

    private float generateECGPoint(int sampleInBeat) {
        float t = (float) sampleInBeat / samplesPerBeat;

        // ECG waveform components
        float pWave = 0, qrsComplex = 0, tWave = 0;

        // P Wave
        if (t >= 0.1 && t <= 0.2) {
            pWave = (float) (0.25 * Math.sin(Math.PI * (t - 0.1) / 0.1));
        }

        // QRS Complex
        if (t >= 0.25 && t <= 0.35) {
            if (t <= 0.27) {
                qrsComplex = -0.5f * (t - 0.25f) / 0.02f;
            } else if (t <= 0.30) {
                qrsComplex = 1.0f - 2.5f * (t - 0.27f);
            } else {
                qrsComplex = -0.3f + 3.0f * (t - 0.30f) / 0.05f;
            }
        }

        // T Wave
        if (t >= 0.4 && t <= 0.6) {
            tWave = (float) (0.3 * Math.sin(Math.PI * (t - 0.4) / 0.2));
        }

        return pWave + qrsComplex + tWave + 0.05f * (float) Math.sin(2 * Math.PI * t * 5);
    }
}