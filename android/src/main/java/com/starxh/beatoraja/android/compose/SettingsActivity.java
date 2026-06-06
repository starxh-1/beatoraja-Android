package com.starxh.beatoraja.android.compose;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.util.Log;
import android.widget.Toast;
import android.content.res.Configuration;
import android.content.res.Resources;

import android.view.Window;
import android.view.WindowManager;

import android.view.MotionEvent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.starxh.beatoraja.android.AndroidLauncher;
import com.starxh.beatoraja.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Settings Activity - 显示在游戏启动之前
 */
public class SettingsActivity extends Activity {

    private static final int REQUEST_CODE_PICK_FOLDER = 1234;
    private static final int REQUEST_CODE_PICK_FOLDER_LEGACY = 1235;
    private static final int REQUEST_CODE_EXPORT_SCORE = 1236;
    private static final int REQUEST_CODE_IMPORT_PLAYER = 1237;
    private static final int REQUEST_CODE_IMPORT_SCORE = 1238;
    private int selectedVolume = 100;
    private int selectedKeyVolume = 100;
    private int selectedBgmVolume = 100;
    private String selectedPlayerName = "player1";
    private List<String> bmsPaths = new ArrayList<>();
    private boolean showAudioSpectrum = true;
    private List<String> tableUrls = new ArrayList<>();
    private List<String> availablePlayers = new ArrayList<>();
    private int selectedGaugeAutoShift = 3;
    private int[] selectedAutoSaveReplay = {0, 0, 0, 0};
    private int selectedGreenNumber = 0;
    private int selectedHispeedFix = 3;
    private int selectedTargetScore = 0;
    private int selectedGaugeType = 0;
    private int selectedNoteTimingOffset = 0;
    private boolean selectedAutoTimingAdjust = false;
    private int selectedNoteModifier = 0;
    private boolean selectedEnableLanecover = false;
    private boolean selectedEnableLift = false;
    private int selectedBga = 0;
    private int selectedBgaExpand = 1;
    // private int selectedPollingRate = 1000; // hardcoded to 1000Hz
    private int selectedFloatingMenuPosition = 0;

    private String[] targetScoreOptions = {"MAX", "RATE_MAX-", "RATE_AAA", "RATE_AA", "RATE_A"};

    private LinearLayout bmsPathContainer;
    private LinearLayout tableUrlContainer;
    private Spinner playerSpinner;

    private Handler keepAliveHandler;
    private boolean isSimulatingTouch = false;

    private final Runnable keepAliveRunnable = new Runnable() {
        @Override
        public void run() {
            // 已移除模拟触摸
        }
    };

    private void startKeepAlive() {
        if (keepAliveHandler == null) keepAliveHandler = new Handler(Looper.getMainLooper());
        keepAliveHandler.removeCallbacks(keepAliveRunnable);
        keepAliveHandler.postDelayed(keepAliveRunnable, 1000);
    }

    private void stopKeepAlive() {
        if (keepAliveHandler != null) keepAliveHandler.removeCallbacks(keepAliveRunnable);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 在加载视图之前，强制刷新当前上下文的语言环境
        updateContextLanguage();

        super.onCreate(savedInstanceState);

        // 性能优化：启用硬件加速和保持屏幕常亮
        Window window = getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                window.setSustainedPerformanceMode(true);
            }
        }

        readConfigDirectly();
        setContentView(R.layout.activity_settings);
        initViews();
    }

    private void updateContextLanguage() {
        Locale systemLocale = Locale.getDefault();
        String lang = systemLocale.getLanguage();
        String country = systemLocale.getCountry();
        Log.i("SettingsActivity", "System Locale: " + systemLocale.toString() + " (lang: " + lang + ", country: " + country + ")");

        // 支持 ja, jp, zh
        if (lang.equalsIgnoreCase("ja") || lang.equalsIgnoreCase("jp") || lang.equalsIgnoreCase("zh")) {
            Locale targetLocale = lang.equalsIgnoreCase("zh") ? Locale.SIMPLIFIED_CHINESE : Locale.JAPANESE;

            Resources res = getResources();
            Configuration config = new Configuration(res.getConfiguration());
            config.setLocale(targetLocale);

            // 针对 API 17+ 的更新方式
            res.updateConfiguration(config, res.getDisplayMetrics());

            // 同时更新 Application 级别的配置
            if (getApplicationContext() != null) {
                Resources appRes = getApplicationContext().getResources();
                Configuration appConfig = new Configuration(appRes.getConfiguration());
                appConfig.setLocale(targetLocale);
                appRes.updateConfiguration(appConfig, appRes.getDisplayMetrics());
            }

            Log.i("SettingsActivity", "Forced UI language to target locale: " + targetLocale.toString());
        }
    }

    private void readConfigDirectly() {
        try {
            File configFile = new File(getExternalFilesDir(null), "config_sys.json");
            if (configFile.exists()) {
                StringBuilder content = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
                    String line;
                    while ((line = reader.readLine()) != null) content.append(line);
                }
                String json = content.toString();
                selectedVolume = findJsonFloatValueAsInt(json, "audio", "systemvolume", 100);
                selectedKeyVolume = (int) (findJsonFloatValue(json, "audio", "keyvolume", 1.0f) * 100);
                selectedBgmVolume = (int) (findJsonFloatValue(json, "audio", "bgvolume", 1.0f) * 100);
                selectedPlayerName = findJsonStringValue(json, "playername", "player1");
                bmsPaths = findJsonArrayStrings(json, "bmsroot");
                String defaultBmsPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath() + "/beatoraja/songs";
                boolean hasDefault = false;
                for (String p : bmsPaths) {
                    if (p.equalsIgnoreCase(defaultBmsPath) || p.equalsIgnoreCase("/storage/emulated/0/Download/beatoraja/songs")) {
                        hasDefault = true; break;
                    }
                }
                if (!hasDefault) bmsPaths.add(0, defaultBmsPath);
                showAudioSpectrum = findJsonBooleanValue(json, "showAudioSpectrum", true);
                // selectedPollingRate = findJsonIntValue(json, "inputPollingRate", 1000); // hardcoded to 1000Hz
                selectedFloatingMenuPosition = findJsonIntValue(json, "floatingMenuPosition", 0);
                selectedBga = findJsonIntValue(json, "bga", 0);
                selectedBgaExpand = findJsonIntValue(json, "bgaExpand", 1);
                tableUrls = findJsonArrayStrings(json, "tableURL");
                if (tableUrls.isEmpty()) tableUrls.add("");
            } else {
                String defaultBmsPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath() + "/beatoraja/songs";
                bmsPaths.add(defaultBmsPath);
                tableUrls.add("");
            }
        } catch (Exception e) {
            Log.e("SettingsActivity", "Read config fail", e);
        }
        readPlayOptionsFromPlayerConfig();
    }

    private void readPlayOptionsFromPlayerConfig() {
        try {
            File playerConfigFile = new File(getExternalFilesDir(null), "player/" + selectedPlayerName + "/config_player.json");
            if (playerConfigFile.exists()) {
                StringBuilder content = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new FileReader(playerConfigFile))) {
                    String line;
                    while ((line = reader.readLine()) != null) content.append(line);
                }
                String json = content.toString();
                selectedGaugeAutoShift = findJsonIntValue(json, "gaugeAutoShift", 0);
                selectedAutoSaveReplay = findJsonIntArray(json, "autosavereplay", 4);
                // 从 mode7.playconfig 解析 hispeedFix
                int mode7Start = json.indexOf("\"mode7\"");
                if (mode7Start >= 0) {
                    int playconfigStart = json.indexOf("\"playconfig\"", mode7Start);
                    if (playconfigStart >= 0) {
                        selectedHispeedFix = findJsonIntValueFrom(json, "fixhispeed", playconfigStart, 3);
                        selectedEnableLanecover = findJsonBooleanValueFrom(json, "enablelanecover", playconfigStart, true);
                        selectedEnableLift = findJsonBooleanValueFrom(json, "enablelift", playconfigStart, false);
                    }
                }
                String targetidStr = findJsonStringValue(json, "targetid", "MAX");
                selectedTargetScore = Arrays.asList(targetScoreOptions).indexOf(targetidStr);
                if (selectedTargetScore < 0) selectedTargetScore = 0;
                selectedGaugeType = findJsonIntValue(json, "gauge", 0);
                selectedNoteTimingOffset = findJsonIntValue(json, "judgetiming", 0);
                selectedAutoTimingAdjust = findJsonBooleanValue(json, "notesDisplayTimingAutoAdjust", false);
                selectedNoteModifier = findJsonIntValue(json, "random", 0);
            }
        } catch (Exception e) { Log.e("SettingsActivity", "Read player config fail", e); }
    }

    private List<String> getAvailablePlayers() {
        List<String> players = new ArrayList<>();
        try {
            File playerDir = new File(getExternalFilesDir(null), "player");
            if (playerDir.exists() && playerDir.isDirectory()) {
                File[] files = playerDir.listFiles();
                if (files != null) {
                    for (File f : files) if (f.isDirectory()) players.add(f.getName());
                }
            }
        } catch (Exception ignored) {}
        if (players.isEmpty()) players.add("player1");
        return players;
    }

    private List<String> findJsonArrayStrings(String json, String key) {
        List<String> result = new ArrayList<>();
        try {
            int keyStart = json.indexOf("\"" + key + "\"");
            if (keyStart < 0) return result;
            int bracketStart = json.indexOf("[", keyStart);
            int bracketEnd = json.indexOf("]", bracketStart);
            if (bracketStart < 0 || bracketEnd < 0) return result;
            String arrayContent = json.substring(bracketStart + 1, bracketEnd);
            int pos = 0;
            while (pos < arrayContent.length()) {
                while (pos < arrayContent.length() && (arrayContent.charAt(pos) == ' ' || arrayContent.charAt(pos) == '\t' || arrayContent.charAt(pos) == ',' || arrayContent.charAt(pos) == '\n' || arrayContent.charAt(pos) == '\r')) pos++;
                if (pos >= arrayContent.length()) break;
                if (arrayContent.charAt(pos) == '"') {
                    pos++; int start = pos;
                    while (pos < arrayContent.length() && arrayContent.charAt(pos) != '"') pos++;
                    if (pos < arrayContent.length()) {
                        String val = arrayContent.substring(start, pos).replace("\\/", "/").replace("\\", "/");
                        while (val.contains("//")) val = val.replace("//", "/");
                        result.add(val); pos++;
                    }
                } else {
                    while (pos < arrayContent.length() && arrayContent.charAt(pos) != ',' && arrayContent.charAt(pos) != ']') pos++;
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    private String findJsonStringValue(String json, String key, String defaultValue) {
        try {
            int keyStart = json.indexOf("\"" + key + "\"");
            if (keyStart < 0) return defaultValue;
            int colonPos = json.indexOf(":", keyStart);
            if (colonPos < 0) return defaultValue;
            int start = colonPos + 1;
            while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '"')) start++;
            int end = start;
            while (end < json.length() && json.charAt(end) != '"' && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
            if (end > start) return json.substring(start, end).trim();
        } catch (Exception ignored) {}
        return defaultValue;
    }

    private int findJsonIntValue(String json, String key, int defaultValue) {
        try {
            int keyStart = json.indexOf("\"" + key + "\"");
            if (keyStart < 0) return defaultValue;
            int colonPos = json.indexOf(":", keyStart);
            if (colonPos < 0) return defaultValue;
            int start = colonPos + 1;
            while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '"')) start++;
            int end = start;
            while (end < json.length() && json.charAt(end) >= '0' && json.charAt(end) <= '9') end++;
            if (end > start) return Integer.parseInt(json.substring(start, end));
        } catch (Exception ignored) {}
        return defaultValue;
    }

    private int findJsonIntValueFrom(String json, String key, int fromIndex, int defaultValue) {
        try {
            int keyStart = json.indexOf("\"" + key + "\"", fromIndex);
            if (keyStart < 0) return defaultValue;
            int colonPos = json.indexOf(":", keyStart);
            if (colonPos < 0) return defaultValue;
            int start = colonPos + 1;
            while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '"')) start++;
            int end = start;
            while (end < json.length() && json.charAt(end) >= '0' && json.charAt(end) <= '9') end++;
            if (end > start) return Integer.parseInt(json.substring(start, end));
        } catch (Exception ignored) {}
        return defaultValue;
    }

    private boolean findJsonBooleanValue(String json, String key, boolean defaultValue) {
        try {
            int keyStart = json.indexOf("\"" + key + "\"");
            if (keyStart < 0) return defaultValue;
            int colonPos = json.indexOf(":", keyStart);
            if (colonPos < 0) return defaultValue;
            int start = colonPos + 1;
            while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '"')) start++;
            String value = json.substring(start, Math.min(start + 6, json.length())).trim().toLowerCase();
            if (value.startsWith("true")) return true;
            if (value.startsWith("false")) return false;
        } catch (Exception ignored) {}
        return defaultValue;
    }

    private boolean findJsonBooleanValueFrom(String json, String key, int fromIndex, boolean defaultValue) {
        try {
            int keyStart = json.indexOf("\"" + key + "\"", fromIndex);
            if (keyStart < 0) return defaultValue;
            int colonPos = json.indexOf(":", keyStart);
            if (colonPos < 0) return defaultValue;
            int start = colonPos + 1;
            while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '"')) start++;
            String value = json.substring(start, Math.min(start + 6, json.length())).trim().toLowerCase();
            if (value.startsWith("true")) return true;
            if (value.startsWith("false")) return false;
        } catch (Exception ignored) {}
        return defaultValue;
    }

    private int[] findJsonIntArray(String json, String key, int size) {
        int[] result = new int[size];
        try {
            int keyStart = json.indexOf("\"" + key + "\"");
            if (keyStart < 0) return result;
            int bracketStart = json.indexOf("[", keyStart);
            int bracketEnd = json.indexOf("]", bracketStart);
            if (bracketStart < 0 || bracketEnd < 0) return result;
            String arrayContent = json.substring(bracketStart + 1, bracketEnd);
            int pos = 0, index = 0;
            while (pos < arrayContent.length() && index < size) {
                while (pos < arrayContent.length() && (arrayContent.charAt(pos) == ' ' || arrayContent.charAt(pos) == ',' || arrayContent.charAt(pos) == '\n' || arrayContent.charAt(pos) == '\r')) pos++;
                if (pos >= arrayContent.length()) break;
                int start = pos;
                while (pos < arrayContent.length() && arrayContent.charAt(pos) >= '0' && arrayContent.charAt(pos) <= '9') pos++;
                if (pos > start) result[index++] = Integer.parseInt(arrayContent.substring(start, pos));
                else while (pos < arrayContent.length() && arrayContent.charAt(pos) != ',' && arrayContent.charAt(pos) != ']') pos++;
            }
        } catch (Exception ignored) {}
        return result;
    }

    private int findJsonIntValueInSection(String json, String section, String key, int defaultValue) {
        try {
            int sectionStart = json.indexOf("\"" + section + "\"");
            if (sectionStart < 0) return defaultValue;
            int keyStart = json.indexOf("\"" + key + "\"", sectionStart);
            if (keyStart < 0) return defaultValue;
            int colonPos = json.indexOf(":", keyStart);
            if (colonPos < 0) return defaultValue;
            int start = colonPos + 1;
            while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '"')) start++;
            int end = start;
            while (end < json.length() && json.charAt(end) >= '0' && json.charAt(end) <= '9') end++;
            if (end > start) return Integer.parseInt(json.substring(start, end));
        } catch (Exception ignored) {}
        return defaultValue;
    }

    private int findJsonFloatValueAsInt(String json, String section, String key, int defaultValue) {
        try {
            int sectionStart = json.indexOf("\"" + section + "\"");
            if (sectionStart < 0) return defaultValue;
            int keyStart = json.indexOf("\"" + key + "\"", sectionStart);
            if (keyStart < 0) return defaultValue;
            int colonPos = json.indexOf(":", keyStart);
            if (colonPos < 0) return defaultValue;
            int start = colonPos + 1;
            while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '"')) start++;
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.')) end++;
            if (end > start) return (int) (Float.parseFloat(json.substring(start, end)) * 100);
        } catch (Exception ignored) {}
        return defaultValue;
    }

    private float findJsonFloatValue(String json, String section, String key, float defaultValue) {
        try {
            int sectionStart = json.indexOf("\"" + section + "\"");
            if (sectionStart < 0) return defaultValue;
            int keyStart = json.indexOf("\"" + key + "\"", sectionStart);
            if (keyStart < 0) return defaultValue;
            int colonPos = json.indexOf(":", keyStart);
            if (colonPos < 0) return defaultValue;
            int start = colonPos + 1;
            while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '"')) start++;
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.')) end++;
            if (end > start) return Float.parseFloat(json.substring(start, end));
        } catch (Exception ignored) {}
        return defaultValue;
    }

    private void initViews() {
        // Volume
        TextView volumePercent = findViewById(R.id.volumePercent);
        volumePercent.setText(selectedVolume + "%");
        android.widget.SeekBar systemVolumeSeekBar = findViewById(R.id.systemVolumeSeekBar);
        systemVolumeSeekBar.setProgress(selectedVolume);
        systemVolumeSeekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) { selectedVolume = progress; volumePercent.setText(progress + "%"); }
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });

        TextView keyVolumePercent = findViewById(R.id.keyVolumePercent);
        keyVolumePercent.setText(selectedKeyVolume + "%");
        android.widget.SeekBar keyVolumeSeekBar = findViewById(R.id.keyVolumeSeekBar);
        keyVolumeSeekBar.setProgress(selectedKeyVolume);
        keyVolumeSeekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) { selectedKeyVolume = progress; keyVolumePercent.setText(progress + "%"); }
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });

        TextView bgmVolumePercent = findViewById(R.id.bgmVolumePercent);
        bgmVolumePercent.setText(selectedBgmVolume + "%");
        android.widget.SeekBar bgmVolumeSeekBar = findViewById(R.id.bgmVolumeSeekBar);
        bgmVolumeSeekBar.setProgress(selectedBgmVolume);
        bgmVolumeSeekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) { selectedBgmVolume = progress; bgmVolumePercent.setText(progress + "%"); }
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });

        // ===== Polling Rate UI disabled (hardcoded to 1000Hz per Endless Dream upstream fix) =====
        // Spinner pollingRateSpinner = findViewById(R.id.pollingRateSpinner);
        // String[] pollingRateOptions = getResources().getStringArray(R.array.input_polling_rate_options);
        // ArrayAdapter<String> pollingRateAdapter = new ArrayAdapter<>(this, R.layout.spinner_item, pollingRateOptions);
        // pollingRateAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        // pollingRateSpinner.setAdapter(pollingRateAdapter);
        // int pollingRateIndex = -1;
        // for (int i = 0; i < pollingRateOptions.length; i++) {
        //     String opt = pollingRateOptions[i];
        //     int value = 1000;
        //     try { value = Integer.parseInt(opt.split(" ")[0]); } catch (Exception ignored) {}
        //     if (value == selectedPollingRate) { pollingRateIndex = i; break; }
        // }
        // pollingRateSpinner.setSelection(pollingRateIndex >= 0 ? pollingRateIndex : 2);
        // pollingRateSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
        //     @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
        //         try {
        //             selectedPollingRate = Integer.parseInt(pollingRateOptions[position].split(" ")[0]);
        //         } catch (Exception e) {
        //             Log.e("SettingsActivity", "Parse polling rate fail: " + pollingRateOptions[position]);
        //         }
        //     }
        //     @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        // });
        //
        // // Polling Rate Help button
        // findViewById(R.id.pollingRateHelp).setOnClickListener(v -> {
        //     new android.app.AlertDialog.Builder(this)
        //         .setTitle(getString(R.string.pollingrate))
        //         .setMessage(getString(R.string.pollingrate_help))
        //         .setPositiveButton("OK", null)
        //         .show();
        // });
        // ===== End of disabled polling rate UI =====

        // Player Spinner
        playerSpinner = findViewById(R.id.playerSpinner);
        availablePlayers = getAvailablePlayers();
        ArrayAdapter<String> playerAdapter = new ArrayAdapter<>(this, R.layout.spinner_item, availablePlayers);
        playerAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        playerSpinner.setAdapter(playerAdapter);
        int playerIndex = availablePlayers.indexOf(selectedPlayerName);
        if (playerIndex >= 0) playerSpinner.setSelection(playerIndex);
        playerSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                String newPlayerName = availablePlayers.get(position);
                if (!newPlayerName.equals(selectedPlayerName)) {
                    selectedPlayerName = newPlayerName;
                    readPlayOptionsFromPlayerConfig();
                    updatePlayOptionsUI();
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        findViewById(R.id.newPlayerBtn).setOnClickListener(v -> showNewPlayerDialog());
        findViewById(R.id.deletePlayerBtn).setOnClickListener(v -> deleteCurrentPlayer());
        findViewById(R.id.exportScoreBtn).setOnClickListener(v -> exportScoreDatabase());
        findViewById(R.id.importPlayerBtn).setOnClickListener(v -> importScoreDatabase());

        // Help buttons
        findViewById(R.id.playerHelp).setOnClickListener(v -> showHelpDialog(getString(R.string.player_help_title), getString(R.string.player_help)));
        findViewById(R.id.bmsPathHelp).setOnClickListener(v -> showHelpDialog(getString(R.string.bms_path_help_title), getString(R.string.bms_path_help)));
        findViewById(R.id.audioSpectrumHelp).setOnClickListener(v -> showHelpDialog(getString(R.string.audio_spectrum_help_title), getString(R.string.audio_spectrum_help)));
        findViewById(R.id.gaugeAutoShiftHelp).setOnClickListener(v -> showHelpDialog(getString(R.string.gauge_auto_shift_help_title), getString(R.string.gauge_auto_shift_help)));

        // Play Options expandable section
        final LinearLayout playOptionsContent = findViewById(R.id.playOptionsContent);
        final TextView playOptionsArrow = findViewById(R.id.playOptionsArrow);
        findViewById(R.id.playOptionsHeader).setOnClickListener(v -> {
            if (playOptionsContent.getVisibility() == View.VISIBLE) {
                playOptionsContent.setVisibility(View.GONE);
                playOptionsArrow.setText("▶");
            } else {
                playOptionsContent.setVisibility(View.VISIBLE);
                playOptionsArrow.setText("▼");
            }
        });

        // BMS Path Container
        bmsPathContainer = findViewById(R.id.bmsPathContainer);
        findViewById(R.id.addBmsPathBtn).setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(intent, REQUEST_CODE_PICK_FOLDER);
            } else {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra("localOnly", true);
                startActivityForResult(intent, REQUEST_CODE_PICK_FOLDER_LEGACY);
            }
        });

        // Show Audio Spectrum
        Switch showAudioSpectrumSwitch = findViewById(R.id.showAudioSpectrumSwitch);
        showAudioSpectrumSwitch.setChecked(showAudioSpectrum);
        showAudioSpectrumSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> showAudioSpectrum = isChecked);

        // Floating Menu Position
        Spinner floatingMenuPositionSpinner = findViewById(R.id.floatingMenuPositionSpinner);
        String[] floatingMenuPositionOptions = getResources().getStringArray(R.array.floating_menu_position_options);
        ArrayAdapter<String> fmpAdapter = new ArrayAdapter<>(this, R.layout.spinner_item, floatingMenuPositionOptions);
        fmpAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        floatingMenuPositionSpinner.setAdapter(fmpAdapter);
        floatingMenuPositionSpinner.setSelection(Math.min(selectedFloatingMenuPosition, floatingMenuPositionOptions.length - 1));

        // BGA
        Spinner bgaDisplaySpinner = findViewById(R.id.bgaDisplaySpinner);
        String[] bgaDisplayOptions = getResources().getStringArray(R.array.bga_display_options);
        ArrayAdapter<String> bgaDisplayAdapter = new ArrayAdapter<>(this, R.layout.spinner_item, bgaDisplayOptions);
        bgaDisplayAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        bgaDisplaySpinner.setAdapter(bgaDisplayAdapter);
        bgaDisplaySpinner.setSelection(selectedBga);

        Spinner bgaExpandSpinner = findViewById(R.id.bgaExpandSpinner);
        String[] bgaExpandOptions = getResources().getStringArray(R.array.bga_expand_options);
        ArrayAdapter<String> bgaExpandAdapter = new ArrayAdapter<>(this, R.layout.spinner_item, bgaExpandOptions);
        bgaExpandAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        bgaExpandSpinner.setAdapter(bgaExpandAdapter);
        bgaExpandSpinner.setSelection(selectedBgaExpand);

        // Table URL
        tableUrlContainer = findViewById(R.id.tableUrlContainer);
        findViewById(R.id.addTableUrlBtn).setOnClickListener(v -> { tableUrls.add(""); refreshTableUrlList(); });
        findViewById(R.id.updateAllTablesBtn).setOnClickListener(v -> updateAllTables());

        refreshBmsPathList();
        refreshTableUrlList();

        // Play Options
        Spinner gaugeAutoShiftSpinner = findViewById(R.id.gaugeAutoShiftSpinner);
        String[] gaugeAutoShiftOptions = getResources().getStringArray(R.array.gauge_auto_shift_options);
        ArrayAdapter<String> gasAdapter = new ArrayAdapter<>(this, R.layout.spinner_item, gaugeAutoShiftOptions);
        gasAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        gaugeAutoShiftSpinner.setAdapter(gasAdapter);
        gaugeAutoShiftSpinner.setSelection(Math.min(selectedGaugeAutoShift, gaugeAutoShiftOptions.length - 1));

        Spinner[] asrSpinners = {findViewById(R.id.autoSaveReplay1), findViewById(R.id.autoSaveReplay2), findViewById(R.id.autoSaveReplay3), findViewById(R.id.autoSaveReplay4)};
        String[] asrOptions = getResources().getStringArray(R.array.auto_save_options);
        ArrayAdapter<String> asrAdapter = new ArrayAdapter<>(this, R.layout.spinner_item, asrOptions);
        asrAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        for (int i = 0; i < 4; i++) {
            asrSpinners[i].setAdapter(asrAdapter);
            asrSpinners[i].setSelection(Math.min(selectedAutoSaveReplay[i], asrOptions.length - 1));
        }

        ((EditText) findViewById(R.id.greenNumberInput)).setText(String.valueOf(selectedGreenNumber));

        Spinner targetScoreSpinner = findViewById(R.id.targetScoreSpinner);
        ArrayAdapter<String> tsAdapter = new ArrayAdapter<>(this, R.layout.spinner_item, targetScoreOptions);
        tsAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        targetScoreSpinner.setAdapter(tsAdapter);
        targetScoreSpinner.setSelection(Math.min(selectedTargetScore, targetScoreOptions.length - 1));

        Spinner gaugeTypeSpinner = findViewById(R.id.gaugeTypeSpinner);
        String[] gaugeTypeOptions = getResources().getStringArray(R.array.gauge_type_options);
        ArrayAdapter<String> gtAdapter = new ArrayAdapter<>(this, R.layout.spinner_item, gaugeTypeOptions);
        gtAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        gaugeTypeSpinner.setAdapter(gtAdapter);
        gaugeTypeSpinner.setSelection(Math.min(selectedGaugeType, gaugeTypeOptions.length - 1));

        ((EditText) findViewById(R.id.noteTimingOffsetInput)).setText(String.valueOf(selectedNoteTimingOffset));

        ((Switch) findViewById(R.id.autoTimingAdjustSwitch)).setChecked(selectedAutoTimingAdjust);

        Spinner noteModifierSpinner = findViewById(R.id.noteModifierSpinner);
        String[] noteModifierOptions = getResources().getStringArray(R.array.note_modifier_options);
        ArrayAdapter<String> nmAdapter = new ArrayAdapter<>(this, R.layout.spinner_item, noteModifierOptions);
        nmAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        noteModifierSpinner.setAdapter(nmAdapter);
        noteModifierSpinner.setSelection(Math.min(selectedNoteModifier, noteModifierOptions.length - 1));

        Spinner hispeedFixSpinner = findViewById(R.id.hispeedFixSpinner);
        String[] hsfOptions = getResources().getStringArray(R.array.hispeed_fix_options);
        ArrayAdapter<String> hsfAdapter = new ArrayAdapter<>(this, R.layout.spinner_item, hsfOptions);
        hsfAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        hispeedFixSpinner.setAdapter(hsfAdapter);
        hispeedFixSpinner.setSelection(Math.min(selectedHispeedFix, hsfOptions.length - 1));

        ((Switch) findViewById(R.id.enableLanecoverSwitch)).setChecked(selectedEnableLanecover);
        ((Switch) findViewById(R.id.enableLiftSwitch)).setChecked(selectedEnableLift);

        findViewById(R.id.saveButton).setOnClickListener(v -> {
            readAllOptionsFromUI();
            saveConfigToJson();
            Toast.makeText(this, getString(R.string.msg_settings_saved), Toast.LENGTH_SHORT).show();
            launchGame();
        });
    }

    private void refreshBmsPathList() {
        bmsPathContainer.removeAllViews();
        String defaultPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath() + "/beatoraja/songs";
        for (int i = 0; i < bmsPaths.size(); i++) {
            final int index = i;
            final String currentPath = bmsPaths.get(i);
            EditText editText = new EditText(this);
            editText.setText(currentPath);
            editText.setTextColor(0xFFFFFFFF);
            editText.setBackgroundColor(0xFF333333);
            editText.setPadding(16, 12, 16, 12);
            editText.setTextSize(14);
            editText.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
            final LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, 4, 0, 4);
            editText.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            editText.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) { bmsPaths.set(index, s.toString().trim()); }
            });
            row.addView(editText);
            if (!currentPath.equalsIgnoreCase(defaultPath) && !currentPath.equalsIgnoreCase("/storage/emulated/0/Download/beatoraja/songs")) {
                Button removeBtn = new Button(this);
                removeBtn.setText(getString(R.string.btn_remove));
                removeBtn.setBackgroundColor(0xFFAA3333);
                removeBtn.setTextColor(0xFFFFFFFF);
                removeBtn.setOnClickListener(v -> { bmsPaths.remove(index); refreshBmsPathList(); });
                row.addView(removeBtn);
            } else {
                editText.setEnabled(false);
                View spacer = new View(this);
                spacer.setLayoutParams(new LinearLayout.LayoutParams(128, ViewGroup.LayoutParams.MATCH_PARENT));
                row.addView(spacer);
            }
            bmsPathContainer.addView(row);
        }
    }

    private void refreshTableUrlList() {
        tableUrlContainer.removeAllViews();
        for (int i = 0; i < tableUrls.size(); i++) {
            final int index = i;
            EditText editText = new EditText(this);
            editText.setText(tableUrls.get(i));
            editText.setTextColor(0xFFFFFFFF);
            editText.setBackgroundColor(0xFF333333);
            editText.setPadding(16, 12, 16, 12);
            editText.setTextSize(14);
            editText.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
            final LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, 4, 0, 4);
            editText.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            editText.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) { tableUrls.set(index, s.toString().trim()); }
            });
            Button updateBtn = new Button(this);
            updateBtn.setText(getString(R.string.btn_update));
            updateBtn.setBackgroundColor(0xFF4CAF50);
            updateBtn.setTextColor(0xFFFFFFFF);
            updateBtn.setOnClickListener(v -> updateSingleTable(index, editText, updateBtn));
            Button removeBtn = new Button(this);
            removeBtn.setText(getString(R.string.btn_remove));
            removeBtn.setBackgroundColor(0xFFAA3333);
            removeBtn.setTextColor(0xFFFFFFFF);
            removeBtn.setOnClickListener(v -> { tableUrls.remove(index); refreshTableUrlList(); });
            row.addView(editText); row.addView(updateBtn); row.addView(removeBtn);
            tableUrlContainer.addView(row);
        }
    }

    private void updateSingleTable(int index, EditText editText, Button updateBtn) {
        String url = tableUrls.get(index);
        if (url == null || url.trim().isEmpty()) {
            Toast.makeText(this, getString(R.string.msg_invalid_url), Toast.LENGTH_SHORT).show();
            return;
        }
        updateBtn.setText("..."); updateBtn.setEnabled(false); editText.setEnabled(false);
        new Thread(() -> {
            try {
                File tableDir = new File(getExternalFilesDir(null), "table");
                if (!tableDir.exists()) tableDir.mkdirs();
                bms.table.DifficultyTable table = new bms.table.DifficultyTable();
                if (url.trim().endsWith(".json")) {
                    table.setHeadURL(url.trim());
                } else {
                    table.setSourceURL(url.trim());
                }
                new bms.table.DifficultyTableParser().decode(true, table);
                File cacheFile = new File(tableDir, sha256(url.trim()) + ".bmt");
                try (java.io.OutputStreamWriter writer = new java.io.OutputStreamWriter(new java.util.zip.GZIPOutputStream(new java.io.FileOutputStream(cacheFile)), StandardCharsets.UTF_8)) {
                    com.badlogic.gdx.utils.Json json = new com.badlogic.gdx.utils.Json();
                    json.setElementType(bms.player.beatoraja.TableData.class, "folder", java.util.ArrayList.class);
                    json.setElementType(bms.player.beatoraja.TableData.TableFolder.class, "songs", java.util.ArrayList.class);
                    json.setElementType(bms.player.beatoraja.TableData.class, "course", java.util.ArrayList.class);
                    json.setElementType(bms.player.beatoraja.CourseData.class, "trophy", java.util.ArrayList.class);
                    json.setOutputType(com.badlogic.gdx.utils.JsonWriter.OutputType.json);
                    writer.write(json.prettyPrint(convertToTableData(table)));
                }
                runOnUiThread(() -> { updateBtn.setText(getString(R.string.btn_update)); updateBtn.setEnabled(true); editText.setEnabled(true); Toast.makeText(this, getString(R.string.msg_table_updated), Toast.LENGTH_SHORT).show(); });
            } catch (Exception e) {
                runOnUiThread(() -> { updateBtn.setText(getString(R.string.btn_update)); updateBtn.setEnabled(true); editText.setEnabled(true); Toast.makeText(this, getString(R.string.msg_update_failed, e.getMessage()), Toast.LENGTH_LONG).show(); });
            }
        }).start();
    }

    private String sha256(String input) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return String.valueOf(input.hashCode()); }
    }

    private bms.player.beatoraja.TableData convertToTableData(bms.table.DifficultyTable dt) {
        bms.player.beatoraja.TableData td = new bms.player.beatoraja.TableData();
        td.setUrl(dt.getSourceURL() != null ? dt.getSourceURL() : dt.getHeadURL());
        td.setName(dt.getName()); td.setTag(dt.getTag());
        String[] levels = dt.getLevelDescription();
        bms.player.beatoraja.TableData.TableFolder[] folders = new bms.player.beatoraja.TableData.TableFolder[levels.length];
        for (int i = 0; i < levels.length; i++) {
            bms.player.beatoraja.TableData.TableFolder folder = new bms.player.beatoraja.TableData.TableFolder();
            folder.setName(td.getTag() + levels[i]);
            List<bms.player.beatoraja.song.SongData> songs = new ArrayList<>();
            for (bms.table.DifficultyTableElement dte : dt.getElements()) {
                if (levels[i].equals(dte.getLevel())) songs.add(convertToSongData(dte));
            }
            folder.setSong(songs.toArray(new bms.player.beatoraja.song.SongData[0]));
            folders[i] = folder;
        }
        td.setFolder(folders);
        if (dt.getCourse() != null && dt.getCourse().length > 0) {
            List<bms.player.beatoraja.CourseData> courses = new ArrayList<>();
            for (bms.table.Course[] courseArr : dt.getCourse()) {
                for (bms.table.Course c : courseArr) {
                    bms.player.beatoraja.CourseData cd = new bms.player.beatoraja.CourseData();
                    cd.setName(c.getName());
                    List<bms.player.beatoraja.song.SongData> songs = new ArrayList<>();
                    if (c.getCharts() != null) {
                        for (bms.table.BMSTableElement chart : c.getCharts()) songs.add(convertToSongData(chart));
                    }
                    cd.setSong(songs.toArray(new bms.player.beatoraja.song.SongData[0]));
                    courses.add(cd);
                }
            }
            td.setCourse(courses.toArray(new bms.player.beatoraja.CourseData[0]));
        }
        return td;
    }

    private bms.player.beatoraja.song.SongData convertToSongData(bms.table.BMSTableElement te) {
        bms.player.beatoraja.song.SongData song = new bms.player.beatoraja.song.SongData();
        if (te.getMD5() != null) song.setMd5(te.getMD5().toLowerCase());
        if (te.getSHA256() != null) song.setSha256(te.getSHA256().toLowerCase());
        song.setTitle(te.getTitle()); song.setArtist(te.getArtist()); song.setUrl(te.getURL());
        if (te instanceof bms.table.DifficultyTableElement) song.setAppendurl(((bms.table.DifficultyTableElement) te).getAppendURL());
        return song;
    }

    private void updateAllTables() {
        int count = 0;
        for (String url : tableUrls) if (url != null && !url.trim().isEmpty()) count++;
        if (count == 0) { Toast.makeText(this, getString(R.string.msg_no_table_urls), Toast.LENGTH_SHORT).show(); return; }
        final int total = count;
        Button updateAllBtn = findViewById(R.id.updateAllTablesBtn);
        updateAllBtn.setText(getString(R.string.btn_update) + "..."); updateAllBtn.setEnabled(false);
        Toast.makeText(this, getString(R.string.msg_updating_tables, total), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            int updated = 0, failed = 0;
            for (String url : tableUrls) {
                if (url == null || url.trim().isEmpty()) continue;
                try {
                    bms.table.DifficultyTable table = new bms.table.DifficultyTable(url.trim());
                    new bms.table.DifficultyTableParser().decode(true, table);
                    File tableDir = new File(getExternalFilesDir(null), "table");
                    if (!tableDir.exists()) tableDir.mkdirs();
                    File cacheFile = new File(tableDir, sha256(url.trim()) + ".bmt");
                    try (java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(new java.io.FileOutputStream(cacheFile))) { oos.writeObject(table); }
                    updated++;
                } catch (Exception e) { failed++; }
            }
            final int s = updated, f = failed;
            runOnUiThread(() -> { updateAllBtn.setText(getString(R.string.btn_update)); updateAllBtn.setEnabled(true); Toast.makeText(this, getString(R.string.msg_tables_updated_summary, s, f), Toast.LENGTH_LONG).show(); });
        }).start();
    }

    private void saveConfigToJson() {
        try {
            File configFile = new File(getExternalFilesDir(null), "config_sys.json");
            org.json.JSONObject config = new org.json.JSONObject();
            if (configFile.exists()) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new FileReader(configFile))) {
                    String line; while ((line = br.readLine()) != null) sb.append(line);
                }
                config = new org.json.JSONObject(sb.toString());
            }
            config.put("playername", selectedPlayerName);
            org.json.JSONObject audio = config.optJSONObject("audio");
            if (audio == null) audio = new org.json.JSONObject();
            audio.put("systemvolume", String.format("%.2f", selectedVolume / 100f));
            audio.put("keyvolume", String.format("%.2f", selectedKeyVolume / 100f));
            audio.put("bgvolume", String.format("%.2f", selectedBgmVolume / 100f));
            config.put("audio", audio);
            config.put("showAudioSpectrum", showAudioSpectrum);
            // config.put("inputPollingRate", selectedPollingRate); // hardcoded to 1000Hz
            config.put("floatingMenuPosition", selectedFloatingMenuPosition);
            config.put("bga", selectedBga);
            config.put("bgaExpand", selectedBgaExpand);

            // 自动同步当前系统语言给游戏内核
            String currentLang = Locale.getDefault().getLanguage();
            if (currentLang.equals("zh") || currentLang.equals("ja")) {
                config.put("language", currentLang);
            } else {
                config.put("language", "en");
            }

            org.json.JSONArray bmsroot = new org.json.JSONArray();
            for (String p : bmsPaths) bmsroot.put(p);
            config.put("bmsroot", bmsroot);
            if (!config.has("irconfig")) config.put("irconfig", new org.json.JSONArray());
            org.json.JSONArray table = new org.json.JSONArray();
            for (String u : tableUrls) table.put(u);
            config.put("tableURL", table);
            try (FileWriter fw = new FileWriter(configFile)) { fw.write(config.toString(2)); }
        } catch (Exception e) { Log.e("SettingsActivity", "Save config fail", e); }
        savePlayOptionsToPlayerConfig();
    }

    private void savePlayOptionsToPlayerConfig() {
        try {
            File configFile = new File(getExternalFilesDir(null), "player/" + selectedPlayerName + "/config_player.json");
            org.json.JSONObject config = new org.json.JSONObject();
            if (configFile.exists()) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new FileReader(configFile))) {
                    String line; while ((line = br.readLine()) != null) sb.append(line);
                }
                config = new org.json.JSONObject(sb.toString());
            }
            config.put("gaugeAutoShift", selectedGaugeAutoShift);
            org.json.JSONArray asr = new org.json.JSONArray();
            for (int val : selectedAutoSaveReplay) asr.put(val);
            config.put("autosavereplay", asr);
            config.put("greenNumber", selectedGreenNumber);
            config.put("targetid", targetScoreOptions[selectedTargetScore]);
            config.put("gauge", selectedGaugeType);
            config.put("judgetiming", selectedNoteTimingOffset);
            config.put("notesDisplayTimingAutoAdjust", selectedAutoTimingAdjust);
            config.put("random", selectedNoteModifier);
            // 写入 PlayConfig 的字段名是 fixhispeed
            config.put("hispeedFix", selectedHispeedFix);
            String[] modes = {"mode5", "mode7", "mode9", "mode10", "mode14", "mode24", "mode24double"};
            for (String m : modes) {
                org.json.JSONObject mo = config.optJSONObject(m); if (mo == null) mo = new org.json.JSONObject();
                org.json.JSONObject pc = mo.optJSONObject("playconfig"); if (pc == null) pc = new org.json.JSONObject();
                pc.put("enablelanecover", selectedEnableLanecover); pc.put("enablelift", selectedEnableLift);
                pc.put("fixhispeed", selectedHispeedFix);
                mo.put("playconfig", pc); config.put(m, mo);
            }
            File pDir = configFile.getParentFile(); if (!pDir.exists()) pDir.mkdirs();
            try (FileWriter fw = new FileWriter(configFile)) { fw.write(config.toString(2)); }
        } catch (Exception e) { Log.e("SettingsActivity", "Save player config fail", e); }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        try {
            super.onActivityResult(requestCode, resultCode, data);
        } catch (Exception e) {
            Log.e("SettingsActivity", "Error in super.onActivityResult", e);
        }

        if (resultCode != RESULT_OK || data == null) {
            Log.w("SettingsActivity", "onActivityResult: resultCode not OK or data is null");
            return;
        }

        Uri uri = data.getData();
        Log.i("SettingsActivity", "onActivityResult: requestCode=" + requestCode + ", uri=" + uri);

        if (uri == null) return;

        if (requestCode == REQUEST_CODE_PICK_FOLDER) {
            String path = getPathFromSAFUri(uri);
            if (path != null) {
                Log.i("SettingsActivity", "Added BMS path: " + path);
                bmsPaths.add(path);
                refreshBmsPathList();
            } else {
                Log.w("SettingsActivity", "Failed to get path from SAF URI: " + uri);
                Toast.makeText(this, "Failed to get folder path", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQUEST_CODE_PICK_FOLDER_LEGACY) {
            String path = getPathFromFileUri(uri);
            if (path != null) {
                File f = new File(path);
                if (f.isFile()) path = f.getParent();
                if (path != null) {
                    bmsPaths.add(path);
                    refreshBmsPathList();
                }
            }
        } else if (requestCode == REQUEST_CODE_EXPORT_SCORE) {
            exportScoreToUri(uri);
        } else if (requestCode == REQUEST_CODE_IMPORT_SCORE) {
            importScoreFromUri(uri);
        } else if (requestCode == REQUEST_CODE_IMPORT_PLAYER) {
            importPlayerFromUri(uri);
        }
    }

    private void exportScoreDatabase() {
        File f = new File(getExternalFilesDir(null), "player/" + selectedPlayerName + "/score.db");
        if (!f.exists()) { Toast.makeText(this, getString(R.string.msg_score_db_not_found, selectedPlayerName), Toast.LENGTH_SHORT).show(); return; }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, selectedPlayerName + "_score.zip");
        startActivityForResult(intent, REQUEST_CODE_EXPORT_SCORE);
    }

    private void exportScoreToUri(Uri uri) {
        new Thread(() -> {
            try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(getContentResolver().openOutputStream(uri))) {
                File playerDir = new File(getExternalFilesDir(null), "player/" + selectedPlayerName);
                String[] files = {"score.db", "score.db-wal", "score.db-shm"};
                byte[] buf = new byte[8192];
                for (String name : files) {
                    File f = new File(playerDir, name);
                    if (f.exists()) {
                        zos.putNextEntry(new java.util.zip.ZipEntry(name));
                        try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                            int r; while ((r = fis.read(buf)) != -1) zos.write(buf, 0, r);
                        }
                        zos.closeEntry();
                    }
                }
                runOnUiThread(() -> Toast.makeText(this, getString(R.string.msg_score_exported), Toast.LENGTH_SHORT).show());
            } catch (Exception e) { runOnUiThread(() -> Toast.makeText(this, getString(R.string.msg_export_failed, e.getMessage()), Toast.LENGTH_LONG).show()); }
        }).start();
    }

    private void importScoreDatabase() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("application/zip");
        startActivityForResult(intent, REQUEST_CODE_IMPORT_SCORE);
    }

    private void importScoreFromUri(Uri uri) {
        new Thread(() -> {
            try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(getContentResolver().openInputStream(uri))) {
                File playerDir = new File(getExternalFilesDir(null), "player/" + selectedPlayerName);
                byte[] buf = new byte[8192];
                java.util.zip.ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String name = new File(entry.getName()).getName();
                    if (!name.equals("score.db") && !name.equals("score.db-wal") && !name.equals("score.db-shm")) {
                        zis.closeEntry();
                        continue;
                    }
                    File outFile = new File(playerDir, name);
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile)) {
                        int r; while ((r = zis.read(buf)) != -1) fos.write(buf, 0, r);
                    }
                    zis.closeEntry();
                }
                runOnUiThread(() -> Toast.makeText(this, getString(R.string.msg_score_imported), Toast.LENGTH_SHORT).show());
            } catch (Exception e) { runOnUiThread(() -> Toast.makeText(this, getString(R.string.msg_score_import_failed, e.getMessage()), Toast.LENGTH_LONG).show()); }
        }).start();
    }

    private void importPlayerConfig() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("application/json");
        startActivityForResult(intent, REQUEST_CODE_IMPORT_PLAYER);
    }

    private void importPlayerFromUri(Uri uri) {
        new Thread(() -> {
            try {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new java.io.InputStreamReader(getContentResolver().openInputStream(uri), StandardCharsets.UTF_8))) {
                    String line; while ((line = br.readLine()) != null) sb.append(line);
                }
                String id = extractPlayerIdFromJson(sb.toString());
                if (id == null) id = "imported";
                File pDir = new File(getExternalFilesDir(null), "player/" + id);
                if (pDir.exists()) id += "_" + System.currentTimeMillis();
                final String finalId = id;
                File target = new File(getExternalFilesDir(null), "player/" + finalId);
                target.mkdirs(); new File(target, "replay").mkdirs();
                try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                     java.io.OutputStream out = new java.io.FileOutputStream(new File(target, "config_player.json"))) {
                    byte[] buf = new byte[8192]; int r; while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                }
                runOnUiThread(() -> { availablePlayers.add(finalId); ((ArrayAdapter) playerSpinner.getAdapter()).notifyDataSetChanged(); playerSpinner.setSelection(availablePlayers.indexOf(finalId)); selectedPlayerName = finalId; Toast.makeText(this, getString(R.string.msg_import_success, finalId), Toast.LENGTH_SHORT).show(); });
            } catch (Exception e) { runOnUiThread(() -> Toast.makeText(this, getString(R.string.msg_import_failed, e.getMessage()), Toast.LENGTH_LONG).show()); }
        }).start();
    }

    private String extractPlayerIdFromJson(String json) {
        int idx = json.indexOf("\"id\"");
        if (idx >= 0) {
            int c = json.indexOf(":", idx);
            if (c >= 0) {
                int s = c + 1; while (s < json.length() && (json.charAt(s) == ' ' || json.charAt(s) == '"')) s++;
                int e = s; while (e < json.length() && json.charAt(e) != '"' && json.charAt(e) != ',' && json.charAt(e) != '}') e++;
                if (e > s) return json.substring(s, e).trim();
            }
        }
        return null;
    }

    private String getPathFromSAFUri(Uri uri) {
        if (uri == null) return null;
        try {
            Log.d("SettingsActivity", "getPathFromSAFUri: " + uri.toString());
            String docId = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && DocumentsContract.isTreeUri(uri)) {
                    docId = DocumentsContract.getTreeDocumentId(uri);
                } else if (DocumentsContract.isDocumentUri(this, uri)) {
                    docId = DocumentsContract.getDocumentId(uri);
                }
            }

            if (docId != null && !docId.isEmpty()) {
                Log.d("SettingsActivity", "Document ID: " + docId);
                if (docId.contains(":")) {
                    String[] parts = docId.split(":");
                    String type = parts[0];
                    String relativePath = parts.length > 1 ? parts[1] : "";

                    String path;
                    if ("primary".equalsIgnoreCase(type)) {
                        path = Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + relativePath;
                    } else {
                        path = "/storage/" + type + "/" + relativePath;
                    }
                    File f = new File(path);
                    if (f.exists()) return f.getAbsolutePath();
                }
            }

            // Fallback: try to parse from path
            String path = uri.getPath();
            if (path != null) {
                Log.d("SettingsActivity", "Fallback parsing path: " + path);
                String treePart = path;
                if (path.startsWith("/tree/")) treePart = path.substring(7);
                else if (path.startsWith("/document/")) treePart = path.substring(10);

                int colon = treePart.indexOf(':');
                if (colon < 0) {
                    // Try finding encoded colon
                    treePart = java.net.URLDecoder.decode(treePart, "UTF-8");
                    colon = treePart.indexOf(':');
                }

                if (colon >= 0) {
                    String type = treePart.substring(0, colon);
                    String relativePath = treePart.substring(colon + 1);
                    String full;
                    if ("primary".equalsIgnoreCase(type)) {
                        full = Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + relativePath;
                    } else {
                        full = "/storage/" + type + "/" + relativePath;
                    }
                    File f = new File(full);
                    if (f.exists()) return f.getAbsolutePath();
                }
            }
        } catch (Exception e) {
            Log.e("SettingsActivity", "getPathFromSAFUri error", e);
        }
        return null;
    }

    private String getPathFromFileUri(Uri uri) {
        if ("content".equals(uri.getScheme())) {
            if (DocumentsContract.isDocumentUri(this, uri)) {
                String docId = DocumentsContract.getDocumentId(uri);
                if (docId.contains(":")) {
                    String[] p = docId.split(":");
                    if (p.length >= 2) return ("primary".equals(p[0]) ? Environment.getExternalStorageDirectory().getAbsolutePath() : "/storage/" + p[0]) + "/" + p[1];
                }
            }
        }
        return "file".equals(uri.getScheme()) ? uri.getPath() : null;
    }

    private void showNewPlayerDialog() {
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
        b.setTitle(getString(R.string.dialog_new_player));
        final EditText input = new EditText(this); input.setHint(getString(R.string.hint_player_name));
        input.setTextColor(0xFFFFFFFF); input.setBackgroundColor(0xFF333333);
        LinearLayout c = new LinearLayout(this); c.setPadding(32, 16, 32, 16); c.addView(input); b.setView(c);
        b.setPositiveButton(getString(R.string.btn_create), (d, w) -> {
            String name = input.getText().toString().trim();
            if (!name.isEmpty() && !availablePlayers.contains(name)) {
                new File(getExternalFilesDir(null), "player/" + name).mkdirs();
                availablePlayers.add(name); ((ArrayAdapter) playerSpinner.getAdapter()).notifyDataSetChanged();
                playerSpinner.setSelection(availablePlayers.indexOf(name)); selectedPlayerName = name;
                Toast.makeText(this, getString(R.string.msg_player_created, name), Toast.LENGTH_SHORT).show();
            } else Toast.makeText(this, name.isEmpty() ? getString(R.string.msg_player_empty) : getString(R.string.msg_player_exists), Toast.LENGTH_SHORT).show();
        });
        b.setNegativeButton(getString(R.string.btn_cancel), null); b.show();
    }

    private void deleteCurrentPlayer() {
        if (availablePlayers.size() <= 1) { Toast.makeText(this, getString(R.string.msg_last_player_error), Toast.LENGTH_SHORT).show(); return; }
        String toDel = selectedPlayerName; int idx = playerSpinner.getSelectedItemPosition();
        new android.app.AlertDialog.Builder(this).setTitle(getString(R.string.dialog_delete_player)).setMessage(getString(R.string.msg_delete_confirm, toDel))
            .setPositiveButton(getString(R.string.delete), (d, w) -> {
                deleteRecursive(new File(getExternalFilesDir(null), "player/" + toDel));
                availablePlayers.remove(toDel); ((ArrayAdapter) playerSpinner.getAdapter()).notifyDataSetChanged();
                int next = idx > 0 ? idx - 1 : 0; playerSpinner.setSelection(next); selectedPlayerName = availablePlayers.get(next);
                Toast.makeText(this, getString(R.string.msg_delete_success), Toast.LENGTH_SHORT).show();
            }).setNegativeButton(getString(R.string.btn_cancel), null).show();
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) { File[] files = f.listFiles(); if (files != null) for (File child : files) deleteRecursive(child); }
        f.delete();
    }

    @Override protected void onResume() {
        super.onResume();
        startKeepAlive();
        readAllOptionsFromUI();
        saveConfigToJson();
    }

    @Override protected void onPause() {
        stopKeepAlive();
        super.onPause();
    }
    @Override public void onBackPressed() { readAllOptionsFromUI(); saveConfigToJson(); super.onBackPressed(); }

    private void readAllOptionsFromUI() {
        selectedVolume = ((android.widget.SeekBar) findViewById(R.id.systemVolumeSeekBar)).getProgress();
        selectedKeyVolume = ((android.widget.SeekBar) findViewById(R.id.keyVolumeSeekBar)).getProgress();
        selectedBgmVolume = ((android.widget.SeekBar) findViewById(R.id.bgmVolumeSeekBar)).getProgress();
        selectedPlayerName = (String) playerSpinner.getSelectedItem();
        bmsPaths.clear();
        for (int i = 0; i < bmsPathContainer.getChildCount(); i++) {
            View r = bmsPathContainer.getChildAt(i);
            if (r instanceof LinearLayout) {
                View e = ((LinearLayout) r).getChildAt(0);
                if (e instanceof EditText) { String p = ((EditText) e).getText().toString().trim(); if (!p.isEmpty()) bmsPaths.add(p); }
            }
        }
        showAudioSpectrum = ((Switch) findViewById(R.id.showAudioSpectrumSwitch)).isChecked();
        selectedFloatingMenuPosition = ((Spinner) findViewById(R.id.floatingMenuPositionSpinner)).getSelectedItemPosition();
        selectedBga = ((Spinner) findViewById(R.id.bgaDisplaySpinner)).getSelectedItemPosition();
        selectedBgaExpand = ((Spinner) findViewById(R.id.bgaExpandSpinner)).getSelectedItemPosition();
        tableUrls.clear();
        for (int i = 0; i < tableUrlContainer.getChildCount(); i++) {
            View r = tableUrlContainer.getChildAt(i);
            if (r instanceof LinearLayout) {
                View e = ((LinearLayout) r).getChildAt(0);
                if (e instanceof EditText) tableUrls.add(((EditText) e).getText().toString().trim());
            }
        }
        selectedGaugeAutoShift = ((Spinner) findViewById(R.id.gaugeAutoShiftSpinner)).getSelectedItemPosition();
        selectedAutoSaveReplay[0] = ((Spinner) findViewById(R.id.autoSaveReplay1)).getSelectedItemPosition();
        selectedAutoSaveReplay[1] = ((Spinner) findViewById(R.id.autoSaveReplay2)).getSelectedItemPosition();
        selectedAutoSaveReplay[2] = ((Spinner) findViewById(R.id.autoSaveReplay3)).getSelectedItemPosition();
        selectedAutoSaveReplay[3] = ((Spinner) findViewById(R.id.autoSaveReplay4)).getSelectedItemPosition();
        try { selectedGreenNumber = Integer.parseInt(((EditText) findViewById(R.id.greenNumberInput)).getText().toString()); } catch (Exception ignored) {}
        selectedTargetScore = ((Spinner) findViewById(R.id.targetScoreSpinner)).getSelectedItemPosition();
        selectedGaugeType = ((Spinner) findViewById(R.id.gaugeTypeSpinner)).getSelectedItemPosition();
        try { selectedNoteTimingOffset = Integer.parseInt(((EditText) findViewById(R.id.noteTimingOffsetInput)).getText().toString()); } catch (Exception ignored) {}
        selectedAutoTimingAdjust = ((Switch) findViewById(R.id.autoTimingAdjustSwitch)).isChecked();
        selectedNoteModifier = ((Spinner) findViewById(R.id.noteModifierSpinner)).getSelectedItemPosition();
        selectedHispeedFix = ((Spinner) findViewById(R.id.hispeedFixSpinner)).getSelectedItemPosition();
        // Polling rate UI disabled (hardcoded to 1000Hz)
        // String pr = (String) ((Spinner) findViewById(R.id.pollingRateSpinner)).getSelectedItem();
        // if (pr != null) {
        //     try {
        //         selectedPollingRate = Integer.parseInt(pr.split(" ")[0]);
        //     } catch (Exception e) {
        //         Log.e("SettingsActivity", "Parse polling rate fail from UI: " + pr);
        //     }
        // }
        selectedEnableLanecover = ((Switch) findViewById(R.id.enableLanecoverSwitch)).isChecked();
        selectedEnableLift = ((Switch) findViewById(R.id.enableLiftSwitch)).isChecked();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (isSimulatingTouch) return true;
        return super.dispatchTouchEvent(ev);
    }

    private void updatePlayOptionsUI() {
        try {
            ((Spinner) findViewById(R.id.gaugeAutoShiftSpinner)).setSelection(selectedGaugeAutoShift);
            ((Spinner) findViewById(R.id.hispeedFixSpinner)).setSelection(selectedHispeedFix);
            Spinner[] s = {findViewById(R.id.autoSaveReplay1), findViewById(R.id.autoSaveReplay2), findViewById(R.id.autoSaveReplay3), findViewById(R.id.autoSaveReplay4)};
            for (int i = 0; i < 4; i++) s[i].setSelection(selectedAutoSaveReplay[i]);
            ((EditText) findViewById(R.id.greenNumberInput)).setText(String.valueOf(selectedGreenNumber));
            ((Spinner) findViewById(R.id.targetScoreSpinner)).setSelection(selectedTargetScore);
            ((Spinner) findViewById(R.id.gaugeTypeSpinner)).setSelection(selectedGaugeType);
            ((EditText) findViewById(R.id.noteTimingOffsetInput)).setText(String.valueOf(selectedNoteTimingOffset));
            ((Switch) findViewById(R.id.autoTimingAdjustSwitch)).setChecked(selectedAutoTimingAdjust);
            ((Spinner) findViewById(R.id.noteModifierSpinner)).setSelection(selectedNoteModifier);
            ((Switch) findViewById(R.id.enableLanecoverSwitch)).setChecked(selectedEnableLanecover);
            ((Switch) findViewById(R.id.enableLiftSwitch)).setChecked(selectedEnableLift);
        } catch (Exception ignored) {}
    }

    private void launchGame() {
        Intent intent = new Intent(this, AndroidLauncher.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent); finish();
    }

    private void showHelpDialog(String title, String message) {
        new android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show();
    }
}
