package com.starxh.beatoraja.android;

import android.media.audiofx.Visualizer;
import android.util.Log;

/**
 * Android Visualizer API wrapper for audio spectrum capture.
 * Captures FFT data from the device's audio output.
 */
public class AudioSpectrumVisualizer {
    private static final String TAG = "AudioSpectrumVisualizer";
    private static volatile AudioSpectrumVisualizer instance;

    private Visualizer visualizer;
    private volatile byte[] fftData;
    private volatile boolean hasNewData = false;
    private volatile float[] smoothedMagnitudes;

    public AudioSpectrumVisualizer() {
        smoothedMagnitudes = new float[32];
    }

    public static AudioSpectrumVisualizer getInstance() {
        return instance;
    }

    public static void setInstance(AudioSpectrumVisualizer vis) {
        instance = vis;
    }

    /**
     * Initialize the Visualizer.
     * @param audioSessionId The audio session ID. Use 0 for mixed audio output.
     */
    public boolean initialize(int audioSessionId) {
        try {
            // Release existing visualizer if any
            release();

            Log.i(TAG, "Initializing Visualizer with session: " + audioSessionId);

            visualizer = new Visualizer(audioSessionId);
            Log.i(TAG, "Visualizer instance created");

            // Set capture listener
            visualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int samplingRate) {
                    // Not used
                }

                @Override
                public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {
                    fftData = fft.clone();
                    hasNewData = true;
                }
            }, Visualizer.getMaxCaptureRate(), false, true);

            // Enable capture
            int result = visualizer.setEnabled(true);
            if (result != Visualizer.SUCCESS) {
                Log.e(TAG, "Failed to enable Visualizer, result: " + result +
                      " (1=SUCCESS, 2=ERROR_INVALID_OPERATION, 3=ERROR_BAD_VALUE)");
                release();
                return false;
            }

            instance = this;
            Log.i(TAG, "Visualizer initialized successfully");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialize Visualizer", t);
            release();
            return false;
        }
    }

    /**
     * Release the Visualizer resources.
     */
    public void release() {
        if (visualizer != null) {
            try {
                visualizer.setEnabled(false);
                visualizer.release();
            } catch (Throwable t) {
                Log.e(TAG, "Error releasing Visualizer", t);
            }
            visualizer = null;
        }
    }

    /**
     * Get the smoothed FFT magnitude for each frequency band.
     * This method is thread-safe and returns smoothed values for visualization.
     * @return Array of smoothed magnitude values (0.0 to 1.0), or null if no new data
     */
    public float[] getSmoothedBandMagnitudes() {
        if (!hasNewData || fftData == null) {
            return smoothedMagnitudes;
        }

        byte[] data = fftData;
        hasNewData = false;

        // FFT data is in byte pairs: [real, imaginary, real, imaginary, ...]
        int bandCount = Math.min(data.length / 2, 32);
        if (bandCount > smoothedMagnitudes.length) {
            bandCount = smoothedMagnitudes.length;
        }

        for (int i = 0; i < bandCount; i++) {
            int idx = i * 2;
            if (idx + 1 < data.length) {
                // Convert from dB to linear scale (approximate)
                float magnitude = (float) Math.sqrt(
                    data[idx] * data[idx] + data[idx + 1] * data[idx + 1]
                ) / 128f;
                magnitude = Math.min(1.0f, magnitude);

                // Apply smoothing (decay)
                smoothedMagnitudes[i] = Math.max(magnitude, smoothedMagnitudes[i] * 0.85f);
            }
        }

        return smoothedMagnitudes;
    }

    /**
     * Check if new FFT data is available.
     */
    public boolean hasNewData() {
        return hasNewData;
    }
}
