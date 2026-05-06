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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.util.Log;
import android.widget.Toast;

import com.starxh.beatoraja.android.AndroidLauncher;
import com.starxh.beatoraja.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Settings Activity - 显示在游戏启动之前
 * 使用传统 Android 视图，不依赖 Compose
 */
public class SettingsActivity extends Activity {

    private static final int REQUEST_CODE_PICK_FOLDER = 1234;
    private static final int REQUEST_CODE_PICK_FOLDER_LEGACY = 1235;
    private static final int REQUEST_CODE_EXPORT_SCORE = 1236;
    private static final int REQUEST_CODE_IMPORT_PLAYER = 1237;

    private int selectedVolume = 100;
    private int selectedKeyVolume = 100;
    private int selectedBgmVolume = 100;
    private String selectedPlayerName = "player1";
    private List<String> bmsPaths = new ArrayList<>();
    private boolean irEnable = false;
    private int irSendCount = 5;
    private List<String> tableUrls = new ArrayList<>();
    private List<String> availablePlayers = new ArrayList<>();
    private int selectedGaugeAutoShift = 0;
    private int[] selectedAutoSaveReplay = {0, 0, 0, 0};
    private int selectedGreenNumber = 0;
    private int selectedHispeedFix = 3;

    // UI containers
    private LinearLayout bmsPathContainer;
    private LinearLayout tableUrlContainer;
    private Spinner playerSpinner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        readConfigDirectly();

        setContentView(R.layout.activity_settings);

        initViews();
    }

    /**
     * 直接读取配置文件
     */
    private void readConfigDirectly() {
        try {
            File filesDir = getExternalFilesDir(null);
            File configFile = new File(filesDir, "config_sys.json");

            if (configFile.exists()) {
                StringBuilder content = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        content.append(line);
                    }
                }

                String json = content.toString();
                Log.d("SettingsActivity", "Config JSON length: " + json.length());

                // 解析 audio.systemvolume
                selectedVolume = findJsonFloatValueAsInt(json, "audio", "systemvolume", 100);

                // 解析 audio.keyvolume
                selectedKeyVolume = (int) (findJsonFloatValue(json, "audio", "keyvolume", 1.0f) * 100);

                // 解析 audio.bgvolume
                selectedBgmVolume = (int) (findJsonFloatValue(json, "audio", "bgvolume", 1.0f) * 100);

                // 解析 playername
                selectedPlayerName = findJsonStringValue(json, "playername", "player1");

                // 解析 bmsroot 数组
                bmsPaths = findJsonArrayStrings(json, "bmsroot");
                Log.d("SettingsActivity", "Read " + bmsPaths.size() + " bmsroot paths");
                if (bmsPaths.isEmpty()) {
                    bmsPaths.add("/storage/emulated/0/Download/oraja_bms");
                }

                // 解析 irSendCount
                irSendCount = findJsonIntValue(json, "irSendCount", 5);

                // 解析 tableURL 数组
                tableUrls = findJsonArrayStrings(json, "tableURL");
                if (tableUrls.isEmpty()) {
                    tableUrls.add("");
                }

                // IR enable: default OFF, only enable if irconfig has content
                irEnable = false;

                // 解析 playerconfig - gaugeAutoShift, autosavereplay, greenNumber, hispeedFix
                selectedGaugeAutoShift = findJsonIntValue(json, "gaugeautoshift", 0);
                selectedHispeedFix = findJsonIntValue(json, "hispeedfix", 3);
                selectedGreenNumber = findJsonIntValue(json, "greennumber", 0);

                // autosavereplay array (4 elements)
                selectedAutoSaveReplay = findJsonIntArray(json, "autosavereplay", 4);
            } else {
                // 默认值
                bmsPaths.add("/storage/emulated/0/Download/oraja_bms");
                tableUrls.add("");
            }
        } catch (Exception e) {
            e.printStackTrace();
            bmsPaths.add("/storage/emulated/0/Download/oraja_bms");
            tableUrls.add("");
        }
    }

    /**
     * 获取所有可用的玩家目录
     */
    private List<String> getAvailablePlayers() {
        List<String> players = new ArrayList<>();
        try {
            File filesDir = getExternalFilesDir(null);
            File playerDir = new File(filesDir, "player");
            if (playerDir.exists() && playerDir.isDirectory()) {
                File[] files = playerDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.isDirectory()) {
                            players.add(f.getName());
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        // 确保至少有一个默认玩家
        if (players.isEmpty()) {
            players.add("player1");
        }
        return players;
    }

    private List<String> findJsonArrayStrings(String json, String key) {
        List<String> result = new ArrayList<>();
        try {
            int keyStart = json.indexOf("\"" + key + "\"");
            if (keyStart < 0) {
                Log.d("SettingsActivity", "Key not found: " + key);
                return result;
            }

            int bracketStart = json.indexOf("[", keyStart);
            int bracketEnd = json.indexOf("]", bracketStart);
            if (bracketStart < 0 || bracketEnd < 0) {
                Log.d("SettingsActivity", "Bracket not found for key: " + key);
                return result;
            }

            String arrayContent = json.substring(bracketStart + 1, bracketEnd);
            Log.d("SettingsActivity", "Array content for " + key + ": [" + arrayContent + "]");

            // 解析数组中的每个字符串 - 使用与 AndroidLauncher 完全相同的逻辑
            int pos = 0;
            while (pos < arrayContent.length()) {
                // 跳过空白、逗号（包括空格、TAB、换行、回车）
                while (pos < arrayContent.length()) {
                    char c = arrayContent.charAt(pos);
                    if (c == ' ' || c == '\t' || c == ',' || c == '\n' || c == '\r') {
                        pos++;
                    } else {
                        break;
                    }
                }
                if (pos >= arrayContent.length()) break;

                if (arrayContent.charAt(pos) == '"') {
                    pos++;
                    int start = pos;
                    while (pos < arrayContent.length() && arrayContent.charAt(pos) != '"') {
                        pos++;
                    }
                    if (pos < arrayContent.length()) {
                        String val = arrayContent.substring(start, pos);
                        // Normalize escaped slashes: \/ -> /, \\ -> /
                        val = val.replace("\\/", "/").replace("\\", "/");
                        // Collapse any duplicate slashes
                        while (val.contains("//")) {
                            val = val.replace("//", "/");
                        }
                        Log.d("SettingsActivity", "Extracted string: [" + val + "]");
                        result.add(val);
                        pos++; // 跳过结束的引号
                        Log.d("SettingsActivity", "After extraction, pos=" + pos + ", char=" + (pos < arrayContent.length() ? String.valueOf(arrayContent.charAt(pos)) : "N/A"));
                    } else {
                        Log.d("SettingsActivity", "Unexpected end of array content");
                    }
                } else {
                    // 非引号字符，跳过
                    char nonQuoteChar = arrayContent.charAt(pos);
                    Log.d("SettingsActivity", "Non-quote char at pos " + pos + ": '" + (nonQuoteChar < 32 ? "0x" + Integer.toHexString(nonQuoteChar) : nonQuoteChar) + "'");
                    while (pos < arrayContent.length()) {
                        char c = arrayContent.charAt(pos);
                        if (c != ',' && c != ']') {
                            pos++;
                        } else {
                            break;
                        }
                    }
                }
            }
            Log.d("SettingsActivity", "Total strings extracted for " + key + ": " + result.size());
        } catch (Exception e) {
            Log.e("SettingsActivity", "Error parsing array for key: " + key, e);
        }
        return result;
    }

    private boolean findJsonArrayExists(String json, String key) {
        try {
            int keyStart = json.indexOf("\"" + key + "\"");
            if (keyStart < 0) return false;
            int bracketStart = json.indexOf("[", keyStart);
            return bracketStart >= 0 && bracketStart < keyStart + 100;
        } catch (Exception e) {
            return false;
        }
    }

    private String findJsonStringValue(String json, String key, String defaultValue) {
        try {
            int keyStart = json.indexOf("\"" + key + "\"");
            if (keyStart < 0) return defaultValue;

            int colonPos = json.indexOf(":", keyStart);
            if (colonPos < 0) return defaultValue;

            int start = colonPos + 1;
            while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '"')) {
                start++;
            }
            int end = start;
            while (end < json.length() && json.charAt(end) != '"' && json.charAt(end) != ',' && json.charAt(end) != '}') {
                end++;
            }
            if (end > start) {
                String val = json.substring(start, end).trim();
                return val;
            }
        } catch (Exception e) {
            // ignore
        }
        return defaultValue;
    }

    private int findJsonIntValue(String json, String key, int defaultValue) {
        try {
            int keyStart = json.indexOf("\"" + key + "\"");
            if (keyStart < 0) return defaultValue;

            int colonPos = json.indexOf(":", keyStart);
            if (colonPos < 0) return defaultValue;

            int start = colonPos + 1;
            while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '"')) {
                start++;
            }
            int end = start;
            while (end < json.length() && json.charAt(end) >= '0' && json.charAt(end) <= '9') {
                end++;
            }
            if (end > start) {
                return Integer.parseInt(json.substring(start, end));
            }
        } catch (Exception e) {
            // ignore
        }
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
            int pos = 0;
            int index = 0;
            while (pos < arrayContent.length() && index < size) {
                while (pos < arrayContent.length() && (arrayContent.charAt(pos) == ' ' || arrayContent.charAt(pos) == ',' || arrayContent.charAt(pos) == '\n' || arrayContent.charAt(pos) == '\r')) pos++;
                if (pos >= arrayContent.length()) break;
                int start = pos;
                while (pos < arrayContent.length() && arrayContent.charAt(pos) >= '0' && arrayContent.charAt(pos) <= '9') pos++;
                if (pos > start) {
                    result[index++] = Integer.parseInt(arrayContent.substring(start, pos));
                } else {
                    while (pos < arrayContent.length() && arrayContent.charAt(pos) != ',' && arrayContent.charAt(pos) != ']') pos++;
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return result;
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
            while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '"')) {
                start++;
            }
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.')) {
                end++;
            }
            if (end > start) {
                float value = Float.parseFloat(json.substring(start, end));
                return (int) (value * 100);
            }
        } catch (Exception e) {
            // ignore
        }
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
            while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '"')) {
                start++;
            }
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.')) {
                end++;
            }
            if (end > start) {
                return Float.parseFloat(json.substring(start, end));
            }
        } catch (Exception e) {
            // ignore
        }
        return defaultValue;
    }

    private void initViews() {
        // Volume
        TextView volumePercent = findViewById(R.id.volumePercent);
        volumePercent.setText(selectedVolume + "%");

        android.widget.SeekBar systemVolumeSeekBar = findViewById(R.id.systemVolumeSeekBar);
        systemVolumeSeekBar.setProgress(selectedVolume);
        systemVolumeSeekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    selectedVolume = progress;
                    volumePercent.setText(progress + "%");
                }
            }
            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });

        // Key Volume
        TextView keyVolumePercent = findViewById(R.id.keyVolumePercent);
        keyVolumePercent.setText(selectedKeyVolume + "%");

        android.widget.SeekBar keyVolumeSeekBar = findViewById(R.id.keyVolumeSeekBar);
        keyVolumeSeekBar.setProgress(selectedKeyVolume);
        keyVolumeSeekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    selectedKeyVolume = progress;
                    keyVolumePercent.setText(progress + "%");
                }
            }
            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });

        // BGM Volume
        TextView bgmVolumePercent = findViewById(R.id.bgmVolumePercent);
        bgmVolumePercent.setText(selectedBgmVolume + "%");

        android.widget.SeekBar bgmVolumeSeekBar = findViewById(R.id.bgmVolumeSeekBar);
        bgmVolumeSeekBar.setProgress(selectedBgmVolume);
        bgmVolumeSeekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    selectedBgmVolume = progress;
                    bgmVolumePercent.setText(progress + "%");
                }
            }
            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });

        // Player Spinner
        playerSpinner = findViewById(R.id.playerSpinner);
        availablePlayers = getAvailablePlayers();
        ArrayAdapter<String> playerAdapter = new ArrayAdapter<>(
            this, R.layout.spinner_item, availablePlayers);
        playerAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        playerSpinner.setAdapter(playerAdapter);

        // 设置当前选中的玩家
        int playerIndex = availablePlayers.indexOf(selectedPlayerName);
        if (playerIndex >= 0) {
            playerSpinner.setSelection(playerIndex);
        }

        playerSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                selectedPlayerName = availablePlayers.get(position);
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        // New Player Button
        Button newPlayerBtn = findViewById(R.id.newPlayerBtn);
        newPlayerBtn.setOnClickListener(v -> {
            showNewPlayerDialog();
        });

        // Delete Player Button
        Button deletePlayerBtn = findViewById(R.id.deletePlayerBtn);
        deletePlayerBtn.setOnClickListener(v -> {
            deleteCurrentPlayer();
        });

        // Export Score Button
        Button exportScoreBtn = findViewById(R.id.exportScoreBtn);
        exportScoreBtn.setOnClickListener(v -> {
            exportScoreDatabase();
        });

        // Import Player Button
        Button importPlayerBtn = findViewById(R.id.importPlayerBtn);
        importPlayerBtn.setOnClickListener(v -> {
            importPlayerConfig();
        });

        // BMS Path Container
        bmsPathContainer = findViewById(R.id.bmsPathContainer);
        Button addBmsPathBtn = findViewById(R.id.addBmsPathBtn);
        addBmsPathBtn.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 使用 SAF 选择文件夹
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(intent, REQUEST_CODE_PICK_FOLDER);
            } else {
                // Android 9 及以下使用传统方式
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra("localOnly", true);
                startActivityForResult(intent, REQUEST_CODE_PICK_FOLDER_LEGACY);
            }
        });

        // IR Enable
        Switch irEnableSwitch = findViewById(R.id.irEnableSwitch);
        irEnableSwitch.setChecked(irEnable);
        irEnableSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            irEnable = isChecked;
        });

        // IR Send Count
        Spinner irSendCountSpinner = findViewById(R.id.irSendCountSpinner);
        List<String> irSendCountList = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            irSendCountList.add(String.valueOf(i));
        }
        ArrayAdapter<String> irSendAdapter = new ArrayAdapter<>(
            this, R.layout.spinner_item, irSendCountList);
        irSendAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        irSendCountSpinner.setAdapter(irSendAdapter);
        irSendCountSpinner.setSelection(Math.min(irSendCount - 1, 9));

        // Table URL Container
        tableUrlContainer = findViewById(R.id.tableUrlContainer);
        Button addTableUrlBtn = findViewById(R.id.addTableUrlBtn);
        addTableUrlBtn.setOnClickListener(v -> {
            tableUrls.add("");
            refreshTableUrlList();
        });

        // Update All Tables Button
        Button updateAllTablesBtn = findViewById(R.id.updateAllTablesBtn);
        updateAllTablesBtn.setOnClickListener(v -> {
            updateAllTables();
        });

        // Initial list population
        refreshBmsPathList();
        refreshTableUrlList();

        // Play Options - Gauge Auto Shift
        Spinner gaugeAutoShiftSpinner = findViewById(R.id.gaugeAutoShiftSpinner);
        List<String> gaugeAutoShiftOptions = Arrays.asList("None", "Continue", "Survival→Groove", "Best Clear", "Select→Under");
        ArrayAdapter<String> gasAdapter = new ArrayAdapter<>(this, R.layout.spinner_item, gaugeAutoShiftOptions);
        gasAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        gaugeAutoShiftSpinner.setAdapter(gasAdapter);
        gaugeAutoShiftSpinner.setSelection(Math.min(selectedGaugeAutoShift, gaugeAutoShiftOptions.size() - 1));

        // Play Options - Auto Save Replay
        Spinner autoSaveReplay1 = findViewById(R.id.autoSaveReplay1);
        Spinner autoSaveReplay2 = findViewById(R.id.autoSaveReplay2);
        Spinner autoSaveReplay3 = findViewById(R.id.autoSaveReplay3);
        Spinner autoSaveReplay4 = findViewById(R.id.autoSaveReplay4);
        List<String> autoSaveOptions = Arrays.asList("Nothing", "Score Update", "Score>=", "Miss Update", "Miss<=", "Combo Update", "Combo>=", "Clear Update", "Clear>=", "Best Clear");
        ArrayAdapter<String> asrAdapter = new ArrayAdapter<>(this, R.layout.spinner_item, autoSaveOptions);
        asrAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        autoSaveReplay1.setAdapter(asrAdapter);
        autoSaveReplay2.setAdapter(asrAdapter);
        autoSaveReplay3.setAdapter(asrAdapter);
        autoSaveReplay4.setAdapter(asrAdapter);
        autoSaveReplay1.setSelection(Math.min(selectedAutoSaveReplay[0], autoSaveOptions.size() - 1));
        autoSaveReplay2.setSelection(Math.min(selectedAutoSaveReplay[1], autoSaveOptions.size() - 1));
        autoSaveReplay3.setSelection(Math.min(selectedAutoSaveReplay[2], autoSaveOptions.size() - 1));
        autoSaveReplay4.setSelection(Math.min(selectedAutoSaveReplay[3], autoSaveOptions.size() - 1));

        // Play Options - Green Number
        EditText greenNumberInput = findViewById(R.id.greenNumberInput);
        greenNumberInput.setText(String.valueOf(selectedGreenNumber));

        // Play Options - HI-SPEED Fix
        Spinner hispeedFixSpinner = findViewById(R.id.hispeedFixSpinner);
        List<String> hispeedFixOptions = Arrays.asList("OFF", "Start BPM", "Max BPM", "Main BPM", "Min BPM");
        ArrayAdapter<String> hsfAdapter = new ArrayAdapter<>(this, R.layout.spinner_item, hispeedFixOptions);
        hsfAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        hispeedFixSpinner.setAdapter(hsfAdapter);
        hispeedFixSpinner.setSelection(Math.min(selectedHispeedFix, hispeedFixOptions.size() - 1));

        // Buttons
        Button saveButton = findViewById(R.id.saveButton);
        saveButton.setOnClickListener(v -> {
            // 更新 irSendCount from spinner
            String selected = (String) irSendCountSpinner.getSelectedItem();
            if (selected != null) {
                irSendCount = Integer.parseInt(selected);
            }
            // Gauge Auto Shift
            selectedGaugeAutoShift = gaugeAutoShiftSpinner.getSelectedItemPosition();
            // Auto Save Replay
            selectedAutoSaveReplay[0] = autoSaveReplay1.getSelectedItemPosition();
            selectedAutoSaveReplay[1] = autoSaveReplay2.getSelectedItemPosition();
            selectedAutoSaveReplay[2] = autoSaveReplay3.getSelectedItemPosition();
            selectedAutoSaveReplay[3] = autoSaveReplay4.getSelectedItemPosition();
            // Green Number
            try {
                selectedGreenNumber = Integer.parseInt(greenNumberInput.getText().toString());
            } catch (Exception e) {
                selectedGreenNumber = 0;
            }
            // HI-SPEED Fix
            selectedHispeedFix = hispeedFixSpinner.getSelectedItemPosition();
            saveConfigToJson();
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
            launchGame();
        });
    }

    private void refreshBmsPathList() {
        bmsPathContainer.removeAllViews();
        for (int i = 0; i < bmsPaths.size(); i++) {
            final int index = i;
            EditText editText = new EditText(this);
            editText.setText(bmsPaths.get(i));
            editText.setTextColor(0xFFFFFFFF);
            editText.setBackgroundColor(0xFF333333);
            editText.setPadding(16, 12, 16, 12);
            editText.setTextSize(14);
            editText.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);

            final LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setPadding(0, 4, 0, 4);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            editText.setLayoutParams(params);

            editText.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    bmsPaths.set(index, s.toString().trim());
                }
            });

            Button removeBtn = new Button(this);
            removeBtn.setText("X");
            removeBtn.setBackgroundColor(0xFFAA3333);
            removeBtn.setTextColor(0xFFFFFFFF);
            removeBtn.setTextSize(12);
            removeBtn.setMinWidth(128);
            removeBtn.setLayoutParams(new LinearLayout.LayoutParams(
                128,
                ViewGroup.LayoutParams.WRAP_CONTENT));
            removeBtn.setOnClickListener(v -> {
                bmsPaths.remove(index);
                refreshBmsPathList();
            });

            row.addView(editText);
            row.addView(removeBtn);
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
            row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setPadding(0, 4, 0, 4);

            LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            editText.setLayoutParams(editParams);

            final TextWatcher textWatcher = new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    tableUrls.set(index, s.toString().trim());
                }
            };
            editText.addTextChangedListener(textWatcher);

            Button updateBtn = new Button(this);
            updateBtn.setText("Update");
            updateBtn.setBackgroundColor(0xFF4CAF50);
            updateBtn.setTextColor(0xFFFFFFFF);
            updateBtn.setTextSize(12);
            updateBtn.setMinWidth(160);
            updateBtn.setLayoutParams(new LinearLayout.LayoutParams(
                160,
                ViewGroup.LayoutParams.WRAP_CONTENT));
            updateBtn.setOnClickListener(v -> {
                updateSingleTable(index, editText, updateBtn);
            });

            Button removeBtn = new Button(this);
            removeBtn.setText("X");
            removeBtn.setBackgroundColor(0xFFAA3333);
            removeBtn.setTextColor(0xFFFFFFFF);
            removeBtn.setTextSize(12);
            removeBtn.setMinWidth(128);
            removeBtn.setLayoutParams(new LinearLayout.LayoutParams(
                128,
                ViewGroup.LayoutParams.WRAP_CONTENT));
            removeBtn.setOnClickListener(v -> {
                tableUrls.remove(index);
                refreshTableUrlList();
            });

            row.addView(editText);
            row.addView(updateBtn);
            row.addView(removeBtn);
            tableUrlContainer.addView(row);
        }
    }

    private void updateSingleTable(int index, EditText editText, Button updateBtn) {
        String url = tableUrls.get(index);
        if (url == null || url.trim().isEmpty()) {
            Toast.makeText(this, "Please enter a valid URL", Toast.LENGTH_SHORT).show();
            return;
        }

        // Update UI to show progress
        updateBtn.setText("...");
        updateBtn.setEnabled(false);
        editText.setEnabled(false);

        new Thread(() -> {
            try {
                // Create table directory if not exists
                File filesDir = getExternalFilesDir(null);
                File tableDir = new File(filesDir, "table");
                if (!tableDir.exists()) {
                    tableDir.mkdirs();
                }

                // Download and parse the table
                java.net.URL tableUrl = new java.net.URL(url.trim());
                bms.table.DifficultyTable table = new bms.table.DifficultyTable(url.trim());
                bms.table.DifficultyTableParser parser = new bms.table.DifficultyTableParser();
                parser.decode(true, table);

                // Save to cache file (using sha256 hash of url as filename)
                String fileName = sha256(url.trim()) + ".bmt";
                File cacheFile = new File(tableDir, fileName);
                // Use GZIP compression like the game does
                java.io.FileOutputStream fos = new java.io.FileOutputStream(cacheFile);
                java.io.BufferedOutputStream bos = new java.io.BufferedOutputStream(
                    new java.util.zip.GZIPOutputStream(fos));
                java.io.OutputStreamWriter writer = new java.io.OutputStreamWriter(bos, StandardCharsets.UTF_8);
                com.badlogic.gdx.utils.Json json = new com.badlogic.gdx.utils.Json();
                json.setElementType(bms.player.beatoraja.TableData.class, "folder", java.util.ArrayList.class);
                json.setElementType(bms.player.beatoraja.TableData.TableFolder.class, "songs", java.util.ArrayList.class);
                json.setElementType(bms.player.beatoraja.TableData.class, "course", java.util.ArrayList.class);
                json.setElementType(bms.player.beatoraja.CourseData.class, "trophy", java.util.ArrayList.class);
                json.setOutputType(com.badlogic.gdx.utils.JsonWriter.OutputType.json);
                writer.write(json.prettyPrint(convertToTableData(table)));
                writer.flush();
                writer.close();

                runOnUiThread(() -> {
                    updateBtn.setText("Update");
                    updateBtn.setEnabled(true);
                    editText.setEnabled(true);
                    Toast.makeText(this, "Table updated successfully", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                Log.e("SettingsActivity", "Failed to update table", e);
                runOnUiThread(() -> {
                    updateBtn.setText("Update");
                    updateBtn.setEnabled(true);
                    editText.setEnabled(true);
                    Toast.makeText(this, "Update failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private String sha256(String input) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }

    private bms.player.beatoraja.TableData convertToTableData(bms.table.DifficultyTable dt) {
        bms.player.beatoraja.TableData td = new bms.player.beatoraja.TableData();
        td.setUrl(dt.getSourceURL() != null ? dt.getSourceURL() : dt.getHeadURL());
        td.setName(dt.getName());
        td.setTag(dt.getTag());

        // Convert level descriptions to folders
        String[] levels = dt.getLevelDescription();
        bms.player.beatoraja.TableData.TableFolder[] folders = new bms.player.beatoraja.TableData.TableFolder[levels.length];
        for (int i = 0; i < levels.length; i++) {
            bms.player.beatoraja.TableData.TableFolder folder = new bms.player.beatoraja.TableData.TableFolder();
            folder.setName(td.getTag() + levels[i]);
            // Filter elements by level
            java.util.List<bms.player.beatoraja.song.SongData> songs = new java.util.ArrayList<>();
            for (bms.table.DifficultyTableElement dte : dt.getElements()) {
                if (levels[i].equals(dte.getLevel())) {
                    songs.add(convertToSongData(dte));
                }
            }
            folder.setSong(songs.toArray(new bms.player.beatoraja.song.SongData[0]));
            folders[i] = folder;
        }
        td.setFolder(folders);

        // Convert courses if present
        if (dt.getCourse() != null && dt.getCourse().length > 0) {
            java.util.List<bms.player.beatoraja.CourseData> courses = new java.util.ArrayList<>();
            for (bms.table.Course[] courseArr : dt.getCourse()) {
                for (bms.table.Course c : courseArr) {
                    bms.player.beatoraja.CourseData cd = new bms.player.beatoraja.CourseData();
                    cd.setName(c.getName());
                    java.util.List<bms.player.beatoraja.song.SongData> songs = new java.util.ArrayList<>();
                    if (c.getCharts() != null) {
                        for (bms.table.BMSTableElement chart : c.getCharts()) {
                            songs.add(convertToSongData(chart));
                        }
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
        if (te.getMD5() != null) {
            song.setMd5(te.getMD5().toLowerCase());
        }
        if (te.getSHA256() != null) {
            song.setSha256(te.getSHA256().toLowerCase());
        }
        song.setTitle(te.getTitle());
        song.setArtist(te.getArtist());
        song.setUrl(te.getURL());
        if (te instanceof bms.table.DifficultyTableElement) {
            bms.table.DifficultyTableElement dte = (bms.table.DifficultyTableElement) te;
            song.setAppendurl(dte.getAppendURL());
        }
        return song;
    }

    private void updateAllTables() {
        // Count non-empty URLs
        int count = 0;
        for (String url : tableUrls) {
            if (url != null && !url.trim().isEmpty()) {
                count++;
            }
        }
        if (count == 0) {
            Toast.makeText(this, "No table URLs to update", Toast.LENGTH_SHORT).show();
            return;
        }

        final int[] updated = {0};
        final int[] failed = {0};
        final int total = count;

        // Disable the Update All button
        Button updateAllBtn = findViewById(R.id.updateAllTablesBtn);
        updateAllBtn.setText("Updating...");
        updateAllBtn.setEnabled(false);

        Toast.makeText(this, "Updating " + total + " table(s)...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            for (int i = 0; i < tableUrls.size(); i++) {
                String url = tableUrls.get(i);
                if (url == null || url.trim().isEmpty()) {
                    continue;
                }

                try {
                    java.net.URL tableUrl = new java.net.URL(url.trim());
                    bms.table.DifficultyTable table = new bms.table.DifficultyTable(url.trim());
                    bms.table.DifficultyTableParser parser = new bms.table.DifficultyTableParser();
                    parser.decode(true, table);

                    File filesDir = getExternalFilesDir(null);
                    File tableDir = new File(filesDir, "table");
                    if (!tableDir.exists()) {
                        tableDir.mkdirs();
                    }
                    String fileName = sha256(url.trim()) + ".bmt";
                    File cacheFile = new File(tableDir, fileName);
                    try (java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(new java.io.FileOutputStream(cacheFile))) {
                        oos.writeObject(table);
                    }
                    updated[0]++;
                } catch (Exception e) {
                    Log.e("SettingsActivity", "Failed to update table: " + url, e);
                    failed[0]++;
                }
            }

            final int success = updated[0];
            final int fail = failed[0];
            runOnUiThread(() -> {
                Button btn = findViewById(R.id.updateAllTablesBtn);
                btn.setText("Update All");
                btn.setEnabled(true);
                Toast.makeText(this, "Updated " + success + " table(s), " + fail + " failed", Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    private void saveConfigToJson() {
        try {
            File filesDir = getExternalFilesDir(null);
            File configFile = new File(filesDir, "config_sys.json");

            // Read existing config as JSONObject
            org.json.JSONObject existingConfig = new org.json.JSONObject();
            if (configFile.exists()) {
                StringBuilder content = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        content.append(line);
                    }
                }
                existingConfig = new org.json.JSONObject(content.toString());
            }

            // Update only the fields that UI controls
            existingConfig.put("playername", selectedPlayerName);

            // audio object
            org.json.JSONObject audio = existingConfig.optJSONObject("audio");
            if (audio == null) audio = new org.json.JSONObject();
            audio.put("systemvolume", String.format("%.2f", selectedVolume / 100f));
            audio.put("keyvolume", String.format("%.2f", selectedKeyVolume / 100f));
            audio.put("bgvolume", String.format("%.2f", selectedBgmVolume / 100f));
            existingConfig.put("audio", audio);

            // bmsroot array
            org.json.JSONArray bmsrootArray = new org.json.JSONArray();
            for (String path : bmsPaths) {
                bmsrootArray.put(path);
            }
            existingConfig.put("bmsroot", bmsrootArray);

            // irSendCount
            existingConfig.put("irSendCount", irSendCount);

            // Play Options
            existingConfig.put("gaugeautoshift", selectedGaugeAutoShift);
            org.json.JSONArray autoSaveReplayArray = new org.json.JSONArray();
            for (int i = 0; i < 4; i++) {
                autoSaveReplayArray.put(selectedAutoSaveReplay[i]);
            }
            existingConfig.put("autosavereplay", autoSaveReplayArray);
            existingConfig.put("greennumber", selectedGreenNumber);
            existingConfig.put("hispeedfix", selectedHispeedFix);

            // irconfig - keep existing if present, or empty array
            if (!existingConfig.has("irconfig")) {
                existingConfig.put("irconfig", new org.json.JSONArray());
            }

            // tableURL array
            org.json.JSONArray tableArray = new org.json.JSONArray();
            for (String url : tableUrls) {
                tableArray.put(url);
            }
            existingConfig.put("tableURL", tableArray);

            try (FileWriter writer = new FileWriter(configFile)) {
                writer.write(existingConfig.toString(2));
                Log.d("SettingsActivity", "Config saved: " + existingConfig.toString());
            }
        } catch (Exception e) {
            Log.e("SettingsActivity", "Failed to save config", e);
            e.printStackTrace();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;

            if (requestCode == REQUEST_CODE_PICK_FOLDER) {
                // Android 10+ SAF
                String path = getPathFromSAFUri(uri);
                if (path != null) {
                    bmsPaths.add(path);
                    refreshBmsPathList();
                }
            } else if (requestCode == REQUEST_CODE_PICK_FOLDER_LEGACY) {
                // Android 9 及以下
                String path = getPathFromFileUri(uri);
                if (path != null) {
                    // 如果选择的是文件，获取其父目录
                    File file = new File(path);
                    if (file.isFile()) {
                        path = file.getParent();
                    }
                    if (path != null) {
                        bmsPaths.add(path);
                        refreshBmsPathList();
                    }
                }
            } else if (requestCode == REQUEST_CODE_EXPORT_SCORE) {
                // 导出分数数据库
                if (resultCode == RESULT_OK && data != null) {
                    Uri targetUri = data.getData();
                    if (targetUri != null) {
                        exportScoreToUri(targetUri);
                    }
                }
            } else if (requestCode == REQUEST_CODE_IMPORT_PLAYER) {
                // 导入玩家配置
                if (resultCode == RESULT_OK && data != null) {
                    Uri sourceUri = data.getData();
                    if (sourceUri != null) {
                        importPlayerFromUri(sourceUri);
                    }
                }
            }
        }
    }

    /**
     * 导出分数数据库
     */
    private void exportScoreDatabase() {
        File filesDir = getExternalFilesDir(null);
        File scoreFile = new File(filesDir, "player/" + selectedPlayerName + "/score.db");
        if (!scoreFile.exists()) {
            Toast.makeText(this, "Score database not found for " + selectedPlayerName, Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/x-sqlite3");
        intent.putExtra(Intent.EXTRA_TITLE, selectedPlayerName + "_score.db");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_CODE_EXPORT_SCORE);
    }

    /**
     * 将分数数据库复制到用户选择的目标 URI
     */
    private void exportScoreToUri(Uri targetUri) {
        File filesDir = getExternalFilesDir(null);
        final File scoreFile = new File(filesDir, "player/" + selectedPlayerName + "/score.db");

        new Thread(() -> {
            try {
                try (java.io.InputStream in = new java.io.FileInputStream(scoreFile);
                     java.io.OutputStream out = getContentResolver().openOutputStream(targetUri)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }

                runOnUiThread(() -> {
                    Toast.makeText(this, "Score exported successfully", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                Log.e("SettingsActivity", "Failed to export score database", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /**
     * 打开文件选择器导入玩家配置
     */
    private void importPlayerConfig() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "*/*"});
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_CODE_IMPORT_PLAYER);
    }

    /**
     * 从 URI 导入玩家配置
     */
    private void importPlayerFromUri(Uri sourceUri) {
        new Thread(() -> {
            try {
                // 读取 JSON 文件内容
                StringBuilder jsonContent = new StringBuilder();
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(getContentResolver().openInputStream(sourceUri), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        jsonContent.append(line);
                    }
                }

                // 解析 JSON (简单解析 player id)
                String json = jsonContent.toString();
                String playerId = extractPlayerIdFromJson(json);
                if (playerId == null || playerId.isEmpty()) {
                    playerId = "imported_player";
                }

                // 检查是否已存在同名玩家
                File playerDir = new File(getExternalFilesDir(null), "player/" + playerId);
                String resolvedPlayerId = playerId;
                if (playerDir.exists()) {
                    resolvedPlayerId = playerId + "_" + System.currentTimeMillis();
                }
                final String finalResolvedPlayerId = resolvedPlayerId;

                // 创建玩家目录
                File newPlayerDir = new File(getExternalFilesDir(null), "player/" + finalResolvedPlayerId);
                if (!newPlayerDir.exists()) {
                    newPlayerDir.mkdirs();
                }

                // 创建 replay 目录
                File replayDir = new File(newPlayerDir, "replay");
                if (!replayDir.exists()) {
                    replayDir.mkdirs();
                }

                // 复制配置文件
                File configFile = new File(newPlayerDir, "config_player.json");
                try (java.io.InputStream in = getContentResolver().openInputStream(sourceUri);
                     java.io.OutputStream out = new java.io.FileOutputStream(configFile)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }

                // 更新玩家列表
                runOnUiThread(() -> {
                    availablePlayers.add(finalResolvedPlayerId);
                    ((ArrayAdapter) playerSpinner.getAdapter()).notifyDataSetChanged();
                    int newIndex = availablePlayers.indexOf(finalResolvedPlayerId);
                    if (newIndex >= 0) {
                        playerSpinner.setSelection(newIndex);
                    }
                    selectedPlayerName = finalResolvedPlayerId;
                    Toast.makeText(this, "Player '" + finalResolvedPlayerId + "' imported successfully", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                Log.e("SettingsActivity", "Failed to import player config", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /**
     * 从 JSON 字符串提取 player id
     */
    private String extractPlayerIdFromJson(String json) {
        // 简单解析: "id":"xxx"
        int idIndex = json.indexOf("\"id\"");
        if (idIndex >= 0) {
            int colonIndex = json.indexOf(":", idIndex);
            if (colonIndex >= 0) {
                int start = colonIndex + 1;
                while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '"')) start++;
                int end = start;
                while (end < json.length() && json.charAt(end) != '"' && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
                if (end > start) {
                    return json.substring(start, end).trim();
                }
            }
        }
        return null;
    }

    /**
     * 从 SAF URI 获取真实路径 (Android 10+)
     */
    private String getPathFromSAFUri(Uri uri) {
        if (uri == null) return null;

        try {
            // 方法1: 直接从 Uri 的 path 获取 (格式: /tree/primary:Download/xxx)
            String path = uri.getPath();
            if (path != null && path.startsWith("/tree/")) {
                String treePart = path.substring(7); // 去掉 "/tree/"
                int colonIndex = treePart.indexOf(':');
                if (colonIndex > 0) {
                    String storageName = treePart.substring(0, colonIndex);
                    String relativePath = treePart.substring(colonIndex + 1);

                    // 处理 URL 编码的路径
                    try {
                        relativePath = java.net.URLDecoder.decode(relativePath, "UTF-8");
                    } catch (Exception e) {
                        // ignore
                    }
                    // 移除首尾空白
                    relativePath = relativePath.trim();

                    String fullPath;
                    if ("primary".equals(storageName)) {
                        // Android 10+ 的 primary 对应 /storage/emulated/0/
                        fullPath = "/storage/emulated/0/" + relativePath;
                    } else {
                        fullPath = "/storage/" + storageName + "/" + relativePath;
                    }

                    // 规范路径（处理 .. 或 . ）
                    File normDir = new File(fullPath).getAbsoluteFile();
                    fullPath = normDir.getAbsolutePath();

                    // 检查目录是否存在
                    if (new File(fullPath).exists()) {
                        return fullPath;
                    }

                    // 如果 /storage/emulated/0/ 格式不行，尝试 /storage/emulated/legacy/
                    String altPath = fullPath.replace("/storage/emulated/0/", "/storage/emulated/legacy/");
                    if (new File(altPath).exists()) {
                        return altPath;
                    }

                    // 尝试添加 /storage/emulated/0/ 前缀（有些设备需要）
                    if (!relativePath.startsWith("storage")) {
                        altPath = "/storage/emulated/0/" + relativePath;
                        if (new File(altPath).exists()) {
                            return altPath;
                        }
                    }
                }
            }

            // 方法2: 从 DocumentsContract 获取 document id 再解析
            if (DocumentsContract.isDocumentUri(this, uri)) {
                try {
                    String docId = DocumentsContract.getDocumentId(uri);
                    if (docId != null && docId.contains(":")) {
                        String[] parts = docId.split(":");
                        if (parts.length >= 2) {
                            String type = parts[0];
                            String relativePath = parts[1];
                            if ("primary".equals(type)) {
                                return Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + relativePath;
                            } else {
                                return "/storage/" + type + "/" + relativePath;
                            }
                        }
                    }
                } catch (Exception e) {
                    // ignore
                }
            }

            // 方法3: 直接返回 path，让用户手动编辑
            // 经过上面的处理，如果还是无法访问，至少给用户看到选择了什么路径
            if (path != null) {
                Toast.makeText(this, "已选择: " + path + " (如有问题请手动编辑)", Toast.LENGTH_LONG).show();
                return path;
            }
        } catch (Exception e) {
            // 任何异常都捕获，避免崩溃
            Toast.makeText(this, "路径解析出错: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }

        // 如果无法解析路径，显示一个 toast 让用户知道
        Toast.makeText(this, "已选择文件夹，但无法解析路径", Toast.LENGTH_SHORT).show();
        return null;
    }

    private String getPathFromTreeUri(String path) {
        if (path == null || !path.startsWith("/tree/")) return null;
        String treePart = path.substring(7);
        int colonIndex = treePart.indexOf(':');
        if (colonIndex > 0) {
            String storageName = treePart.substring(0, colonIndex);
            String relativePath = treePart.substring(colonIndex + 1);
            String fullPath;
            if ("primary".equals(storageName)) {
                fullPath = "/storage/emulated/0/" + relativePath;
            } else {
                fullPath = "/storage/" + storageName + "/" + relativePath;
            }
            File f = new File(fullPath);
            if (f.exists()) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }

    /**
     * 从文件 URI 获取真实路径 (Android 9 及以下)
     */
    private String getPathFromFileUri(Uri uri) {
        if (uri == null) return null;
        // 处理 content:// URI
        if (uri.getScheme() != null && uri.getScheme().equals("content")) {
            String[] projection = { android.provider.OpenableColumns.DISPLAY_NAME };
            try (android.database.Cursor cursor = getContentResolver().query(uri, projection, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (nameIndex >= 0) {
                        String name = cursor.getString(nameIndex);
                        // 获取父目录路径
                        String parentPath = new File(uri.getPath()).getParent();
                        if (parentPath != null) {
                            return parentPath;
                        }
                    }
                }
            } catch (Exception e) {
                // ignore
            }
            // 尝试从 DocumentsContract 获取
            if (DocumentsContract.isDocumentUri(this, uri)) {
                String docId = DocumentsContract.getDocumentId(uri);
                if (docId.contains(":")) {
                    String[] parts = docId.split(":");
                    if (parts.length >= 2) {
                        String type = parts[0];
                        String relativePath = parts[1];
                        if ("primary".equals(type)) {
                            return Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + relativePath;
                        } else {
                            return "/storage/" + type + "/" + relativePath;
                        }
                    }
                }
            }
        }
        // 处理 file:// URI
        if (uri.getScheme() != null && uri.getScheme().equals("file")) {
            return uri.getPath();
        }
        return null;
    }

    /**
     * 显示新建玩家对话框
     */
    private void showNewPlayerDialog() {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("New Player");

        final EditText input = new EditText(this);
        input.setHint("Player name");
        input.setTextColor(0xFFFFFFFF);
        input.setBackgroundColor(0xFF333333);
        input.setPadding(32, 16, 32, 16);
        input.setTextSize(16);
        input.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(32, 16, 32, 16);
        container.addView(input);
        builder.setView(container);

        builder.setPositiveButton("Create", (dialog, which) -> {
            String newName = input.getText().toString().trim();
            if (!newName.isEmpty() && !availablePlayers.contains(newName)) {
                // 创建玩家目录
                createPlayerDirectory(newName);
                // 添加到列表
                availablePlayers.add(newName);
                ((ArrayAdapter) playerSpinner.getAdapter()).notifyDataSetChanged();
                // 选中新玩家
                int newIndex = availablePlayers.indexOf(newName);
                playerSpinner.setSelection(newIndex);
                selectedPlayerName = newName;
                Toast.makeText(this, "Player '" + newName + "' created", Toast.LENGTH_SHORT).show();
            } else if (newName.isEmpty()) {
                Toast.makeText(this, "Player name cannot be empty", Toast.LENGTH_SHORT).show();
            } else if (availablePlayers.contains(newName)) {
                Toast.makeText(this, "Player already exists", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    /**
     * 创建玩家目录
     */
    private void createPlayerDirectory(String playerName) {
        try {
            File filesDir = getExternalFilesDir(null);
            File playerDir = new File(filesDir, "player/" + playerName);
            if (!playerDir.exists()) {
                playerDir.mkdirs();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 删除当前选中的玩家
     */
    private void deleteCurrentPlayer() {
        if (availablePlayers.size() <= 1) {
            Toast.makeText(this, "Cannot delete the last player", Toast.LENGTH_SHORT).show();
            return;
        }

        String toDelete = selectedPlayerName;
        int currentIndex = playerSpinner.getSelectedItemPosition();

        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("Delete Player");
        builder.setMessage("Are you sure you want to delete player '" + toDelete + "'? This will delete all player data including scores.");
        builder.setPositiveButton("Delete", (dialog, which) -> {
            // 删除玩家目录
            deletePlayerDirectory(toDelete);
            // 从列表移除
            availablePlayers.remove(toDelete);
            ((ArrayAdapter) playerSpinner.getAdapter()).notifyDataSetChanged();
            // 选中其他玩家
            int newSelection = currentIndex > 0 ? currentIndex - 1 : 0;
            playerSpinner.setSelection(newSelection);
            selectedPlayerName = availablePlayers.get(newSelection);
            Toast.makeText(this, "Player deleted", Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    /**
     * 删除玩家目录
     */
    private void deletePlayerDirectory(String playerName) {
        try {
            File filesDir = getExternalFilesDir(null);
            File playerDir = new File(filesDir, "player/" + playerName);
            if (playerDir.exists()) {
                deleteRecursive(playerDir);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 递归删除目录
     */
    private void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] files = fileOrDirectory.listFiles();
            if (files != null) {
                for (File child : files) {
                    deleteRecursive(child);
                }
            }
        }
        fileOrDirectory.delete();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Save config when leaving activity without playing
        readAllOptionsFromUI();
        saveConfigToJson();
    }

    @Override
    public void onBackPressed() {
        readAllOptionsFromUI();
        saveConfigToJson();
        super.onBackPressed();
    }

    /**
     * Read all UI options and save to config
     */
    private void readAllOptionsFromUI() {
        // Volume
        selectedVolume = ((android.widget.SeekBar) findViewById(R.id.systemVolumeSeekBar)).getProgress();
        selectedKeyVolume = ((android.widget.SeekBar) findViewById(R.id.keyVolumeSeekBar)).getProgress();
        selectedBgmVolume = ((android.widget.SeekBar) findViewById(R.id.bgmVolumeSeekBar)).getProgress();

        // Player
        selectedPlayerName = (String) ((Spinner) findViewById(R.id.playerSpinner)).getSelectedItem();

        // BMS Paths
        bmsPaths.clear();
        for (int i = 0; i < bmsPathContainer.getChildCount(); i++) {
            View row = bmsPathContainer.getChildAt(i);
            if (row instanceof LinearLayout) {
                View editText = ((LinearLayout) row).getChildAt(0);
                if (editText instanceof EditText) {
                    String path = ((EditText) editText).getText().toString().trim();
                    if (!path.isEmpty()) {
                        bmsPaths.add(path);
                    }
                }
            }
        }

        // IR
        irEnable = ((android.widget.Switch) findViewById(R.id.irEnableSwitch)).isChecked();
        String irSendCountStr = (String) ((Spinner) findViewById(R.id.irSendCountSpinner)).getSelectedItem();
        if (irSendCountStr != null) {
            try {
                irSendCount = Integer.parseInt(irSendCountStr);
            } catch (Exception e) {}
        }

        // Table URLs
        tableUrls.clear();
        for (int i = 0; i < tableUrlContainer.getChildCount(); i++) {
            View row = tableUrlContainer.getChildAt(i);
            if (row instanceof LinearLayout) {
                View editText = ((LinearLayout) row).getChildAt(0);
                if (editText instanceof EditText) {
                    String url = ((EditText) editText).getText().toString().trim();
                    tableUrls.add(url);
                }
            }
        }

        // Play Options
        selectedGaugeAutoShift = ((Spinner) findViewById(R.id.gaugeAutoShiftSpinner)).getSelectedItemPosition();
        selectedAutoSaveReplay[0] = ((Spinner) findViewById(R.id.autoSaveReplay1)).getSelectedItemPosition();
        selectedAutoSaveReplay[1] = ((Spinner) findViewById(R.id.autoSaveReplay2)).getSelectedItemPosition();
        selectedAutoSaveReplay[2] = ((Spinner) findViewById(R.id.autoSaveReplay3)).getSelectedItemPosition();
        selectedAutoSaveReplay[3] = ((Spinner) findViewById(R.id.autoSaveReplay4)).getSelectedItemPosition();
        try {
            selectedGreenNumber = Integer.parseInt(((EditText) findViewById(R.id.greenNumberInput)).getText().toString());
        } catch (Exception e) {}
        selectedHispeedFix = ((Spinner) findViewById(R.id.hispeedFixSpinner)).getSelectedItemPosition();
    }

    private void launchGame() {
        Intent intent = new Intent(this, AndroidLauncher.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }
}