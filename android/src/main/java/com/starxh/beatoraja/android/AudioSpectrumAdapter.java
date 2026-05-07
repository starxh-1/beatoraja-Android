package com.starxh.beatoraja.android;

import android.util.Log;
import barsoosayque.libgdxoboe.OboeAudio;
import com.starxh.beatoraja.AudioSpectrumManager;
import com.starxh.beatoraja.AudioSpectrumProvider;

/**
 * Adapter to expose OboeAudio's spectrum data via AudioSpectrumProvider interface.
 */
public class AudioSpectrumAdapter implements AudioSpectrumProvider {
    private static final String TAG = "AudioSpectrumAdapter";
    private OboeAudio oboeAudio;

    public AudioSpectrumAdapter(OboeAudio oboeAudio) {
        this.oboeAudio = oboeAudio;
        Log.i(TAG, "Adapter created with OboeAudio: " + (oboeAudio != null ? "valid" : "null"));
    }

    @Override
    public float[] getSpectrumMagnitudes() {
        if (oboeAudio != null) {
            return oboeAudio.getSpectrumMagnitudes();
        }
        return new float[64]; // 返回 64 位长度以保持一致
    }

    @Override
    public void setAsGlobalProvider() {
        AudioSpectrumManager.setGlobalProvider(this);
        Log.i(TAG, "Set as global provider");
    }
}
