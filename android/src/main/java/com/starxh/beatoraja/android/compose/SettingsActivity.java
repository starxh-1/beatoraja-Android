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
import android.view.KeyEvent;
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
    private int audioVisualizationMode = 1; // 0=off, 1=spectrum, 2=waveform
    private List<String> tableUrls = new ArrayList<>();
    private List<String> availablePlayers = new ArrayList<>();
    private int selectedGaugeAutoShift = 3;
    private int[] selectedAutoSaveReplay = {0, 0, 0, 0};
    private int selectedGreenNumber = 500;
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
    private int selectedFloatingMenuPosition = 0;
    private boolean selectedStretchFullscreen = false;
    private boolean selectedScanSongsOnLaunch = true;
    private int selectedInputDuration = 16;
    private boolean selectedJkocHack = false;
    private boolean selectedAnalogScratch = false;
    private int selectedAnalogScratchThreshold = 100;
    private int selectedAnalogScratchMode = 0;
    private boolean selectedMouseScratch = false;
    private int selectedMouseScratchThreshold = 150;
    private int selectedMouseScratchDistance = 12;
    private int selectedMouseScratchMode = 0;

    private String[] targetScoreOptions = {"MAX", "RATE_MAX-", "RATE_AAA", "RATE_AA", "RATE_A"};

    private LinearLayout bmsPathContainer;
    private LinearLayout tableUrlContainer;
    private Spinner playerSpinner;

    private View focusIndicator;
    private boolean gamepadMode = false;
    private long lastGamepadInputTime = 0;
    private android.widget.ScrollView settingsScrollView;
    private java.util.List<View> focusableControls = new java.util.ArrayList<>();

    private android.view.ViewTreeObserver.OnGlobalFocusChangeListener focusChangeListener;
    private android.view.ViewTreeObserver.OnScrollChangedListener scrollChangedListener;
    private CharacterWheelDialog currentCharacterWheel;

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

    private Object backInvokedCallback;

    /** Activity 销毁标志，用于停止后台线程 */
    private volatile boolean destroyed = false;

    /** 后台线程列表，用于 onDestroy 时停止 */
    private final List<Thread> backgroundThreads = new java.util.ArrayList<>();

    /** 首次启动引导：SharedPreferences 持久化标记 */
    private static final String PREFS_NAME = "beatoraja_prefs";
    private static final String KEY_ONBOARDED = "first_launch_onboarded";
    private android.app.AlertDialog onboardDialog;
    /** 引导第二步的内容由用户在第一步选择的角色决定 */
    private Boolean onboardIsNewbie = null;

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
        settingsScrollView = findViewById(R.id.settingsScrollView);
        initViews();

        // 首次启动引导：询问用户身份并给出对应说明。仅在第一次显示，之后不再出现。
        maybeShowOnboarding();

        // 构建手柄导航用的焦点控件列表
        buildFocusableControlsList();

        // 监听焦点变化，显示手柄高光
        focusChangeListener = (oldFocus, newFocus) -> {
            if (gamepadMode && newFocus != null) {
                updateFocusIndicator(newFocus);
                ensureViewVisible(newFocus);
            }
        };
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener(focusChangeListener);

        // 监听滚动，确保高光跟随并支持手动触发滚动
        scrollChangedListener = () -> {
            if (gamepadMode) {
                updateFocusIndicator(getCurrentFocus());
            }
        };
        getWindow().getDecorView().getViewTreeObserver().addOnScrollChangedListener(scrollChangedListener);

        // 手柄模式下触摸屏幕则退出手柄模式
        android.view.View touchInterceptor = findViewById(android.R.id.content);
        touchInterceptor.setOnTouchListener((v, event) -> {
            if (gamepadMode) {
                gamepadMode = false;
                if (focusIndicator != null) focusIndicator.setVisibility(View.GONE);
                Log.i("SettingsActivity", "Touch detected, exiting gamepad mode");
            }
            return false;
        });

        // API 33+ (targetSdk 36): 使用 OnBackInvokedDispatcher 处理返回手势
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.window.OnBackInvokedCallback callback = () -> {
                readAllOptionsFromUI();
                saveConfigToJson();
                finish();
            };
            backInvokedCallback = callback;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
        }
    }

    /**
     * First-launch onboarding. Shows a two-step dialog asking the user whether they are new
     * to BMS or already familiar with beatoraja from another platform, then displays a brief
     * explanation tailored to their answer. The dialog is shown only once per install.
     */
    private void maybeShowOnboarding() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(KEY_ONBOARDED, false)) return;

        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.onboard_welcome_title)
                .setMessage(R.string.onboard_welcome_message)
                .setCancelable(false)
                .setPositiveButton(R.string.onboard_newbie, (d, w) -> {
                    onboardIsNewbie = Boolean.TRUE;
                    showOnboardContent();
                })
                .setNegativeButton(R.string.onboard_veteran, (d, w) -> {
                    onboardIsNewbie = Boolean.FALSE;
                    showOnboardContent();
                })
                .show();
    }

    private void showOnboardContent() {
        if (onboardIsNewbie == null) return;
        if (onboardIsNewbie) {
            showOnboardDialog(R.string.onboard_newbie_title, R.string.onboard_newbie_content,
                    R.string.onboard_got_it, true);
        } else {
            showOnboardVeteranLayer1();
        }
    }

    /**
     * Veteran branch, layer 1. Shows a blank-titled dialog with the message "(^^)" and two
     * buttons: "你就没啥要说的吗？" reveals the real veteran welcome in layer 2,
     * "关闭" ends onboarding immediately.
     */
    private void showOnboardVeteranLayer1() {
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                .setMessage(R.string.onboard_veteran_layer1_message)
                .setCancelable(true)
                .setPositiveButton(R.string.onboard_veteran_layer1_more, (d, w) -> showOnboardVeteranLayer2())
                .setNegativeButton(R.string.onboard_veteran_layer1_close, (d, w) -> markOnboarded())
                .setOnDismissListener(d -> markOnboarded());
        // layer1 title is intentionally blank — don't pass an empty resource, just skip the call
        onboardDialog = b.show();
    }

    private void showOnboardVeteranLayer2() {
        showOnboardDialog(R.string.onboard_veteran_title, R.string.onboard_veteran_content,
                R.string.onboard_got_it, true);
    }

    private void showOnboardDialog(int titleRes, int messageRes, int positiveButtonRes, boolean dismissMarksOnboarded) {
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                .setMessage(messageRes)
                .setCancelable(true)
                .setPositiveButton(positiveButtonRes, (d, w) -> markOnboarded());
        // Only set title if the resource resolves to non-empty text
        CharSequence title = getText(titleRes);
        if (title != null && title.length() > 0) {
            b.setTitle(title);
        }
        if (dismissMarksOnboarded) {
            b.setOnDismissListener(d -> markOnboarded());
        }
        onboardDialog = b.show();
    }

    private void markOnboarded() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_ONBOARDED, true).apply();
        onboardDialog = null;
    }

    private void updateContextLanguage() {
        Locale systemLocale = Locale.getDefault();
        String lang = systemLocale.getLanguage();
        String country = systemLocale.getCountry();
        Log.i("SettingsActivity", "System Locale: " + systemLocale.toString() + " (lang: " + lang + ", country: " + country + ")");

        // 支持 ja, jp, zh, ko
        if (lang.equalsIgnoreCase("ja") || lang.equalsIgnoreCase("jp") || lang.equalsIgnoreCase("zh") || lang.equalsIgnoreCase("ko")) {
            Locale targetLocale = lang.equalsIgnoreCase("zh") ? Locale.SIMPLIFIED_CHINESE :
                                 (lang.equalsIgnoreCase("ko") ? Locale.KOREAN : Locale.JAPANESE);

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
                audioVisualizationMode = findJsonIntValue(json, "audioVisualizationMode", -1);
                if (audioVisualizationMode < 0) {
                    audioVisualizationMode = findJsonBooleanValue(json, "showAudioSpectrum", true) ? 1 : 0;
                }
                showAudioSpectrum = audioVisualizationMode != 0;
                selectedFloatingMenuPosition = findJsonIntValue(json, "floatingMenuPosition", 0);
                selectedBga = findJsonIntValue(json, "bga", 0);
                selectedBgaExpand = findJsonIntValue(json, "bgaExpand", 1);
                selectedStretchFullscreen = findJsonBooleanValue(json, "stretchFullscreen", false);
                selectedScanSongsOnLaunch = findJsonBooleanValue(json, "updatesong", true);
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
                        selectedGreenNumber = findJsonIntValueFrom(json, "duration", playconfigStart, 500);

                        // 从 controller 数组读取输入消抖时间 (duration)
                        int controllerStart = json.indexOf("\"controller\"", playconfigStart);
                        if (controllerStart >= 0) {
                            selectedInputDuration = findJsonIntValueFrom(json, "duration", controllerStart, 16);
                            selectedJkocHack = findJsonBooleanValueFrom(json, "jkoc_hack", controllerStart, false);
                            selectedAnalogScratch = findJsonBooleanValueFrom(json, "analogScratch", controllerStart, false);
                            selectedAnalogScratchThreshold = findJsonIntValueFrom(json, "analogScratchThreshold", controllerStart, 100);
                            selectedAnalogScratchMode = findJsonIntValueFrom(json, "analogScratchMode", controllerStart, 0);
                        }

                        // 读取鼠标转盘配置
                        int kbConfigStart = json.indexOf("\"keyboardConfig\"", playconfigStart);
                        if (kbConfigStart >= 0) {
                            int mouseConfigStart = json.indexOf("\"mouseScratchConfig\"", kbConfigStart);
                            if (mouseConfigStart >= 0) {
                                selectedMouseScratch = findJsonBooleanValueFrom(json, "mouseScratch", mouseConfigStart, false);
                                selectedMouseScratchThreshold = findJsonIntValueFrom(json, "mouseScratchThreshold", mouseConfigStart, 150);
                                selectedMouseScratchDistance = findJsonIntValueFrom(json, "mouseScratchDistance", mouseConfigStart, 12);
                                selectedMouseScratchMode = findJsonIntValueFrom(json, "mouseScratchMode", mouseConfigStart, 0);
                            }
                        }
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
            int signStart = start;
            if (start < json.length() && json.charAt(start) == '-') start++;
            int end = start;
            while (end < json.length() && json.charAt(end) >= '0' && json.charAt(end) <= '9') end++;
            if (end > start) return Integer.parseInt(json.substring(signStart, end));
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
            int signStart = start;
            if (start < json.length() && json.charAt(start) == '-') start++;
            int end = start;
            while (end < json.length() && json.charAt(end) >= '0' && json.charAt(end) <= '9') end++;
            if (end > start) return Integer.parseInt(json.substring(signStart, end));
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
            int signStart = start;
            if (start < json.length() && json.charAt(start) == '-') start++;
            int end = start;
            while (end < json.length() && json.charAt(end) >= '0' && json.charAt(end) <= '9') end++;
            if (end > start) return Integer.parseInt(json.substring(signStart, end));
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
        setupGamepadFocusable(systemVolumeSeekBar);
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
        setupGamepadFocusable(keyVolumeSeekBar);
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
        setupGamepadFocusable(bgmVolumeSeekBar);
        bgmVolumeSeekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) { selectedBgmVolume = progress; bgmVolumePercent.setText(progress + "%"); }
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });

        // Player Spinner
        playerSpinner = findViewById(R.id.playerSpinner);
        setupGamepadFocusable(playerSpinner);
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
        setupGamepadFocusable(findViewById(R.id.newPlayerBtn));
        findViewById(R.id.deletePlayerBtn).setOnClickListener(v -> deleteCurrentPlayer());
        setupGamepadFocusable(findViewById(R.id.deletePlayerBtn));
        findViewById(R.id.renamePlayerBtn).setOnClickListener(v -> showRenamePlayerDialog());
        setupGamepadFocusable(findViewById(R.id.renamePlayerBtn));
        findViewById(R.id.exportScoreBtn).setOnClickListener(v -> exportScoreDatabase());
        setupGamepadFocusable(findViewById(R.id.exportScoreBtn));
        findViewById(R.id.importPlayerBtn).setOnClickListener(v -> importScoreDatabase());
        setupGamepadFocusable(findViewById(R.id.importPlayerBtn));

        // Help buttons
        findViewById(R.id.playerHelp).setOnClickListener(v -> showHelpDialog(getString(R.string.player_help_title), getString(R.string.player_help)));
        setupGamepadFocusable(findViewById(R.id.playerHelp));
        findViewById(R.id.bmsPathHelp).setOnClickListener(v -> showHelpDialog(getString(R.string.bms_path_help_title), getString(R.string.bms_path_help)));
        setupGamepadFocusable(findViewById(R.id.bmsPathHelp));
        findViewById(R.id.audioSpectrumHelp).setOnClickListener(v -> showHelpDialog(getString(R.string.audio_spectrum_help_title), getString(R.string.audio_spectrum_help)));
        setupGamepadFocusable(findViewById(R.id.audioSpectrumHelp));
        findViewById(R.id.gaugeAutoShiftHelp).setOnClickListener(v -> showHelpDialog(getString(R.string.gauge_auto_shift_help_title), getString(R.string.gauge_auto_shift_help)));
        setupGamepadFocusable(findViewById(R.id.gaugeAutoShiftHelp));
        findViewById(R.id.stretchFullscreenHelp).setOnClickListener(v -> showHelpDialog(getString(R.string.stretch_fullscreen_help_title), getString(R.string.stretch_fullscreen_help)));
        setupGamepadFocusable(findViewById(R.id.stretchFullscreenHelp));
        findViewById(R.id.jkocHackHelp).setOnClickListener(v -> showHelpDialog(getString(R.string.jkoc_hack_help_title), getString(R.string.jkoc_hack_help)));
        setupGamepadFocusable(findViewById(R.id.jkocHackHelp));
        findViewById(R.id.analogScratchHelp).setOnClickListener(v -> showHelpDialog(getString(R.string.analog_scratch_help_title), getString(R.string.analog_scratch_help)));
        setupGamepadFocusable(findViewById(R.id.analogScratchHelp));
        findViewById(R.id.greenNumberHelp).setOnClickListener(v -> showHelpDialog(getString(R.string.green_number_help_title), getString(R.string.green_number_help)));
        setupGamepadFocusable(findViewById(R.id.greenNumberHelp));
        findViewById(R.id.noteTimingOffsetHelp).setOnClickListener(v -> showHelpDialog(getString(R.string.note_timing_offset_help_title), getString(R.string.note_timing_offset_help)));
        setupGamepadFocusable(findViewById(R.id.noteTimingOffsetHelp));
        findViewById(R.id.inputOptionsHelp).setOnClickListener(v -> showHelpDialog(getString(R.string.input_options_help_title), getString(R.string.input_options_help)));
        setupGamepadFocusable(findViewById(R.id.inputOptionsHelp));

        // Display section switch — 锁定音频频谱开关（拉伸至全屏开启时强制关闭并禁用）
        // 注：Switch 初始化在更下方的 Play Options 之后，与 Show Audio Spectrum 一起设置以确保正确的锁定顺序。

        // Play Options expandable section
        final LinearLayout playOptionsContent = findViewById(R.id.playOptionsContent);
        final TextView playOptionsArrow = findViewById(R.id.playOptionsArrow);
        View playOptionsHeader = findViewById(R.id.playOptionsHeader);
        setupGamepadFocusable(playOptionsHeader);
        playOptionsHeader.setOnClickListener(v -> {
            if (playOptionsContent.getVisibility() == View.VISIBLE) {
                playOptionsContent.setVisibility(View.GONE);
                playOptionsArrow.setText("▶");
                // Move focus back to header if it was inside the collapsed section
                View currentFocus = getCurrentFocus();
                if (currentFocus != null && isDescendantOf(playOptionsContent, currentFocus)) {
                    v.requestFocus();
                }
            } else {
                playOptionsContent.setVisibility(View.VISIBLE);
                playOptionsArrow.setText("▼");
            }
            buildFocusableControlsList();
        });

        // Input Options expandable section
        final LinearLayout inputOptionsContent = findViewById(R.id.inputOptionsContent);
        final TextView inputOptionsArrow = findViewById(R.id.inputOptionsArrow);
        View inputOptionsHeader = findViewById(R.id.inputOptionsHeader);
        setupGamepadFocusable(inputOptionsHeader);
        inputOptionsHeader.setOnClickListener(v -> {
            if (inputOptionsContent.getVisibility() == View.VISIBLE) {
                inputOptionsContent.setVisibility(View.GONE);
                inputOptionsArrow.setText("▶");
                View currentFocus = getCurrentFocus();
                if (currentFocus != null && isDescendantOf(inputOptionsContent, currentFocus)) {
                    v.requestFocus();
                }
            } else {
                inputOptionsContent.setVisibility(View.VISIBLE);
                inputOptionsArrow.setText("▼");
            }
            buildFocusableControlsList();
        });

        // BMS Path Container
        bmsPathContainer = findViewById(R.id.bmsPathContainer);
        setupGamepadFocusable(findViewById(R.id.addBmsPathBtn));

        // Scan Songs On Launch Switch
        Switch scanSongsSwitch = findViewById(R.id.scanSongsOnLaunchSwitch);
        scanSongsSwitch.setChecked(selectedScanSongsOnLaunch);
        scanSongsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> selectedScanSongsOnLaunch = isChecked);
        setupGamepadFocusable(scanSongsSwitch);

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

        // Audio Visualization Mode (0=Off, 1=Spectrum, 2=Waveform)
        Spinner audioVisualizationModeSpinner = findViewById(R.id.audioVisualizationModeSpinner);
        String[] visualizationOptions = getResources().getStringArray(R.array.audio_visualization_mode_options);
        ArrayAdapter<String> vizAdapter = new ArrayAdapter<>(this, R.layout.spinner_item, visualizationOptions);
        vizAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        audioVisualizationModeSpinner.setAdapter(vizAdapter);
        if (audioVisualizationMode < 0) audioVisualizationMode = 1;
        if (audioVisualizationMode > 2) audioVisualizationMode = 1;
        audioVisualizationModeSpinner.setSelection(audioVisualizationMode);
        audioVisualizationModeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                audioVisualizationMode = position;
                showAudioSpectrum = position != 0;
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        setupGamepadFocusable(audioVisualizationModeSpinner);

        // Display section switch — 锁定音频频谱开关（拉伸至全屏开启时强制关闭并禁用）
        Switch stretchFullscreenSwitch = findViewById(R.id.stretchFullscreenSwitch);
        stretchFullscreenSwitch.setChecked(selectedStretchFullscreen);
        setupGamepadFocusable(stretchFullscreenSwitch);
        stretchFullscreenSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            selectedStretchFullscreen = isChecked;
            applyStretchFullscreenLockState();
        });
        applyStretchFullscreenLockState();

        // Floating Menu Position
        Spinner floatingMenuPositionSpinner = findViewById(R.id.floatingMenuPositionSpinner);
        String[] floatingMenuPositionOptions = getResources().getStringArray(R.array.floating_menu_position_options);
        ArrayAdapter<String> fmpAdapter = new ArrayAdapter<>(this, R.layout.spinner_item, floatingMenuPositionOptions);
        fmpAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        floatingMenuPositionSpinner.setAdapter(fmpAdapter);
        floatingMenuPositionSpinner.setSelection(Math.min(selectedFloatingMenuPosition, floatingMenuPositionOptions.length - 1));
        setupGamepadFocusable(floatingMenuPositionSpinner);

        // BGA
        Spinner bgaDisplaySpinner = findViewById(R.id.bgaDisplaySpinner);
        String[] bgaDisplayOptions = getResources().getStringArray(R.array.bga_display_options);
        ArrayAdapter<String> bgaDisplayAdapter = new ArrayAdapter<>(this, R.layout.spinner_item, bgaDisplayOptions);
        bgaDisplayAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        bgaDisplaySpinner.setAdapter(bgaDisplayAdapter);
        bgaDisplaySpinner.setSelection(selectedBga);
        setupGamepadFocusable(bgaDisplaySpinner);

        // BGA Expand
        Spinner bgaExpandSpinner = findViewById(R.id.bgaExpandSpinner);
        String[] bgaExpandOptions = getResources().getStringArray(R.array.bga_expand_options);
        ArrayAdapter<String> bgaExpandAdapter = new ArrayAdapter<>(this, R.layout.spinner_item, bgaExpandOptions);
        bgaExpandAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        bgaExpandSpinner.setAdapter(bgaExpandAdapter);
        bgaExpandSpinner.setSelection(Math.min(selectedBgaExpand, bgaExpandOptions.length - 1));
        setupGamepadFocusable(bgaExpandSpinner);

        // Table URL
        tableUrlContainer = findViewById(R.id.tableUrlContainer);
        setupGamepadFocusable(findViewById(R.id.addTableUrlBtn));
        findViewById(R.id.addTableUrlBtn).setOnClickListener(v -> { tableUrls.add(""); refreshTableUrlList(); });
        setupGamepadFocusable(findViewById(R.id.updateAllTablesBtn));
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
        setupGamepadFocusable(gaugeAutoShiftSpinner);

        Spinner[] asrSpinners = {findViewById(R.id.autoSaveReplay1), findViewById(R.id.autoSaveReplay2), findViewById(R.id.autoSaveReplay3), findViewById(R.id.autoSaveReplay4)};
        String[] asrOptions = getResources().getStringArray(R.array.auto_save_options);
        ArrayAdapter<String> asrAdapter = new ArrayAdapter<>(this, R.layout.spinner_item, asrOptions);
        asrAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        for (int i = 0; i < 4; i++) {
            asrSpinners[i].setAdapter(asrAdapter);
            asrSpinners[i].setSelection(Math.min(selectedAutoSaveReplay[i], asrOptions.length - 1));
            setupGamepadFocusable(asrSpinners[i]);
        }

        ((EditText) findViewById(R.id.greenNumberInput)).setText(String.valueOf(selectedGreenNumber));
        setupGamepadFocusable(findViewById(R.id.greenNumberInput));

        ((EditText) findViewById(R.id.inputDurationInput)).setText(String.valueOf(selectedInputDuration));
        setupGamepadFocusable(findViewById(R.id.inputDurationInput));

        Switch jkocHackSwitch = findViewById(R.id.jkocHackSwitch);
        jkocHackSwitch.setChecked(selectedJkocHack);
        setupGamepadFocusable(jkocHackSwitch);

        Switch analogScratchSwitch = findViewById(R.id.analogScratchSwitch);
        analogScratchSwitch.setChecked(selectedAnalogScratch);
        setupGamepadFocusable(analogScratchSwitch);

        ((EditText) findViewById(R.id.analogScratchThresholdInput)).setText(String.valueOf(selectedAnalogScratchThreshold));
        setupGamepadFocusable(findViewById(R.id.analogScratchThresholdInput));

        Spinner analogScratchAlgorithmSpinner = findViewById(R.id.analogScratchAlgorithmSpinner);
        String[] asAlgoOptions = getResources().getStringArray(R.array.analog_scratch_algorithm_options);
        ArrayAdapter<String> asAlgoAdapter = new ArrayAdapter<>(this, R.layout.spinner_item, asAlgoOptions);
        asAlgoAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        analogScratchAlgorithmSpinner.setAdapter(asAlgoAdapter);
        analogScratchAlgorithmSpinner.setSelection(Math.min(selectedAnalogScratchMode, asAlgoOptions.length - 1));
        setupGamepadFocusable(analogScratchAlgorithmSpinner);

        Switch mouseScratchSwitch = findViewById(R.id.mouseScratchSwitch);
        mouseScratchSwitch.setChecked(selectedMouseScratch);
        setupGamepadFocusable(mouseScratchSwitch);

        ((EditText) findViewById(R.id.mouseScratchThresholdInput)).setText(String.valueOf(selectedMouseScratchThreshold));
        setupGamepadFocusable(findViewById(R.id.mouseScratchThresholdInput));

        ((EditText) findViewById(R.id.mouseScratchDistanceInput)).setText(String.valueOf(selectedMouseScratchDistance));
        setupGamepadFocusable(findViewById(R.id.mouseScratchDistanceInput));

        Spinner mouseScratchAlgorithmSpinner = findViewById(R.id.mouseScratchAlgorithmSpinner);
        // Reuse same algo options for mouse if they are same, else define new ones. PC screenshot shows same.
        mouseScratchAlgorithmSpinner.setAdapter(asAlgoAdapter);
        mouseScratchAlgorithmSpinner.setSelection(Math.min(selectedMouseScratchMode, asAlgoOptions.length - 1));
        setupGamepadFocusable(mouseScratchAlgorithmSpinner);

        Spinner targetScoreSpinner = findViewById(R.id.targetScoreSpinner);
        ArrayAdapter<String> tsAdapter = new ArrayAdapter<>(this, R.layout.spinner_item, targetScoreOptions);
        tsAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        targetScoreSpinner.setAdapter(tsAdapter);
        targetScoreSpinner.setSelection(Math.min(selectedTargetScore, targetScoreOptions.length - 1));
        setupGamepadFocusable(targetScoreSpinner);

        Spinner gaugeTypeSpinner = findViewById(R.id.gaugeTypeSpinner);
        String[] gaugeTypeOptions = getResources().getStringArray(R.array.gauge_type_options);
        ArrayAdapter<String> gtAdapter = new ArrayAdapter<>(this, R.layout.spinner_item, gaugeTypeOptions);
        gtAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        gaugeTypeSpinner.setAdapter(gtAdapter);
        gaugeTypeSpinner.setSelection(Math.min(selectedGaugeType, gaugeTypeOptions.length - 1));
        setupGamepadFocusable(gaugeTypeSpinner);

        ((EditText) findViewById(R.id.noteTimingOffsetInput)).setText(String.valueOf(selectedNoteTimingOffset));
        setupGamepadFocusable(findViewById(R.id.noteTimingOffsetInput));

        ((Switch) findViewById(R.id.autoTimingAdjustSwitch)).setChecked(selectedAutoTimingAdjust);
        setupGamepadFocusable(findViewById(R.id.autoTimingAdjustSwitch));

        Spinner noteModifierSpinner = findViewById(R.id.noteModifierSpinner);
        String[] noteModifierOptions = getResources().getStringArray(R.array.note_modifier_options);
        ArrayAdapter<String> nmAdapter = new ArrayAdapter<>(this, R.layout.spinner_item, noteModifierOptions);
        nmAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        noteModifierSpinner.setAdapter(nmAdapter);
        noteModifierSpinner.setSelection(Math.min(selectedNoteModifier, noteModifierOptions.length - 1));
        setupGamepadFocusable(noteModifierSpinner);

        Spinner hispeedFixSpinner = findViewById(R.id.hispeedFixSpinner);
        String[] hsfOptions = getResources().getStringArray(R.array.hispeed_fix_options);
        ArrayAdapter<String> hsfAdapter = new ArrayAdapter<>(this, R.layout.spinner_item, hsfOptions);
        hsfAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        hispeedFixSpinner.setAdapter(hsfAdapter);
        hispeedFixSpinner.setSelection(Math.min(selectedHispeedFix, hsfOptions.length - 1));
        setupGamepadFocusable(hispeedFixSpinner);

        ((Switch) findViewById(R.id.enableLanecoverSwitch)).setChecked(selectedEnableLanecover);
        setupGamepadFocusable(findViewById(R.id.enableLanecoverSwitch));
        ((Switch) findViewById(R.id.enableLiftSwitch)).setChecked(selectedEnableLift);
        setupGamepadFocusable(findViewById(R.id.enableLiftSwitch));

        findViewById(R.id.saveButton).setOnClickListener(v -> {
            readAllOptionsFromUI();
            saveConfigToJson();
            Toast.makeText(this, getString(R.string.msg_settings_saved), Toast.LENGTH_SHORT).show();
            launchGame();
        });
        setupGamepadFocusable(findViewById(R.id.saveButton));
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
            editText.setBackgroundResource(R.drawable.focus_highlight);
            editText.setPadding(16, 12, 16, 12);
            editText.setTextSize(14);
            editText.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
            editText.setFocusable(true);
            editText.setFocusableInTouchMode(true);
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
                setupGamepadFocusable(removeBtn);
                removeBtn.setOnClickListener(v -> { bmsPaths.remove(index); refreshBmsPathList(); saveConfigToJson(); });
                row.addView(removeBtn);
            } else {
                editText.setEnabled(false);
                View spacer = new View(this);
                spacer.setLayoutParams(new LinearLayout.LayoutParams(128, ViewGroup.LayoutParams.MATCH_PARENT));
                row.addView(spacer);
            }
            bmsPathContainer.addView(row);
        }
        buildFocusableControlsList();
    }

    private void refreshTableUrlList() {
        tableUrlContainer.removeAllViews();
        for (int i = 0; i < tableUrls.size(); i++) {
            final int index = i;
            EditText editText = new EditText(this);
            editText.setText(tableUrls.get(i));
            editText.setTextColor(0xFFFFFFFF);
            editText.setBackgroundResource(R.drawable.focus_highlight);
            editText.setPadding(16, 12, 16, 12);
            editText.setTextSize(14);
            editText.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
            editText.setFocusable(true);
            editText.setFocusableInTouchMode(true);
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
            setupGamepadFocusable(updateBtn);
            updateBtn.setOnClickListener(v -> updateSingleTable(index, editText, updateBtn));
            Button removeBtn = new Button(this);
            removeBtn.setText(getString(R.string.btn_remove));
            removeBtn.setBackgroundColor(0xFFAA3333);
            removeBtn.setTextColor(0xFFFFFFFF);
            setupGamepadFocusable(removeBtn);
            removeBtn.setOnClickListener(v -> { tableUrls.remove(index); refreshTableUrlList(); saveConfigToJson(); });
            row.addView(editText); row.addView(updateBtn); row.addView(removeBtn);
            tableUrlContainer.addView(row);
        }
        buildFocusableControlsList();
    }

    private void updateSingleTable(int index, EditText editText, Button updateBtn) {
        String url = tableUrls.get(index);
        if (url == null || url.trim().isEmpty()) {
            Toast.makeText(this, getString(R.string.msg_invalid_url), Toast.LENGTH_SHORT).show();
            return;
        }
        updateBtn.setText("..."); updateBtn.setEnabled(false); editText.setEnabled(false);
        Thread updateThread = new Thread(() -> {
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
                saveTableCache(table, url);
                runOnUiThread(() -> { updateBtn.setText(getString(R.string.btn_update)); updateBtn.setEnabled(true); editText.setEnabled(true); Toast.makeText(this, getString(R.string.msg_table_updated), Toast.LENGTH_SHORT).show(); });
            } catch (Exception e) {
                runOnUiThread(() -> { updateBtn.setText(getString(R.string.btn_update)); updateBtn.setEnabled(true); editText.setEnabled(true); Toast.makeText(this, getString(R.string.msg_update_failed, e.getMessage()), Toast.LENGTH_LONG).show(); });
            }
        });
        synchronized (backgroundThreads) { backgroundThreads.add(updateThread); }
        updateThread.start();
    }

    private void saveTableCache(bms.table.DifficultyTable table, String url) throws IOException {
        File tableDir = new File(getExternalFilesDir(null), "table");
        if (!tableDir.exists()) tableDir.mkdirs();
        File cacheFile = new File(tableDir, sha256(url.trim()) + ".bmt");
        try (java.io.OutputStreamWriter writer = new java.io.OutputStreamWriter(
                new java.util.zip.GZIPOutputStream(new java.io.FileOutputStream(cacheFile)),
                StandardCharsets.UTF_8)) {
            com.badlogic.gdx.utils.Json json = new com.badlogic.gdx.utils.Json();
            json.setElementType(bms.player.beatoraja.TableData.class, "folder", java.util.ArrayList.class);
            json.setElementType(bms.player.beatoraja.TableData.TableFolder.class, "songs", java.util.ArrayList.class);
            json.setElementType(bms.player.beatoraja.TableData.class, "course", java.util.ArrayList.class);
            json.setElementType(bms.player.beatoraja.CourseData.class, "trophy", java.util.ArrayList.class);
            json.setOutputType(com.badlogic.gdx.utils.JsonWriter.OutputType.json);
            writer.write(json.prettyPrint(convertToTableData(table)));
        }
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
        Thread updateThread = new Thread(() -> {
            int updated = 0, failed = 0;
            for (String url : tableUrls) {
                if (url == null || url.trim().isEmpty()) continue;
                try {
                    bms.table.DifficultyTable table = new bms.table.DifficultyTable(url.trim());
                    new bms.table.DifficultyTableParser().decode(true, table);
                    saveTableCache(table, url);
                    updated++;
                } catch (Exception e) {
                    failed++;
                    Log.e("SettingsActivity", "updateAllTables: failed for url=" + url, e);
                }
            }
            final int s = updated, f = failed;
            runOnUiThread(() -> { updateAllBtn.setText(getString(R.string.btn_update)); updateAllBtn.setEnabled(true); Toast.makeText(this, getString(R.string.msg_tables_updated_summary, s, f), Toast.LENGTH_LONG).show(); });
        });
        synchronized (backgroundThreads) { backgroundThreads.add(updateThread); }
        updateThread.start();
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
            config.put("audioVisualizationMode", audioVisualizationMode);
            config.put("showAudioSpectrum", audioVisualizationMode != 0);
            config.put("floatingMenuPosition", selectedFloatingMenuPosition);
            config.put("bga", selectedBga);
            config.put("bgaExpand", selectedBgaExpand);
            config.put("stretchFullscreen", selectedStretchFullscreen);
            config.put("updatesong", selectedScanSongsOnLaunch);

            // 自动同步当前系统语言给游戏内核
            String currentLang = Locale.getDefault().getLanguage();
            if (currentLang.equals("zh") || currentLang.equals("ja") || currentLang.equals("ko")) {
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
            config.put("name", selectedPlayerName);
            config.put("gaugeAutoShift", selectedGaugeAutoShift);
            org.json.JSONArray asr = new org.json.JSONArray();
            for (int val : selectedAutoSaveReplay) asr.put(val);
            config.put("autosavereplay", asr);
            config.put("targetid", targetScoreOptions[selectedTargetScore]);
            config.put("gauge", selectedGaugeType);
            config.put("judgetiming", selectedNoteTimingOffset);
            config.put("notesDisplayTimingAutoAdjust", selectedAutoTimingAdjust);
            config.put("random", selectedNoteModifier);
            // 写入 hispeedFix 到根目录（兼容模式），同时也写入 mode*.playconfig
            config.put("hispeedFix", selectedHispeedFix);
            String[] modes = {"mode5", "mode7", "mode9", "mode10", "mode14", "mode24", "mode24double"};
            for (String m : modes) {
                org.json.JSONObject mo = config.optJSONObject(m); if (mo == null) mo = new org.json.JSONObject();
                org.json.JSONObject pc = mo.optJSONObject("playconfig"); if (pc == null) pc = new org.json.JSONObject();
                pc.put("enablelanecover", selectedEnableLanecover); pc.put("enablelift", selectedEnableLift);
                pc.put("fixhispeed", selectedHispeedFix);
                pc.put("duration", selectedGreenNumber);

                // 更新所有控制器的配置
                org.json.JSONArray controllers = pc.optJSONArray("controller");
                if (controllers != null) {
                    for (int i = 0; i < controllers.length(); i++) {
                        org.json.JSONObject con = controllers.optJSONObject(i);
                        if (con != null) {
                            con.put("duration", selectedInputDuration);
                            con.put("jkoc_hack", selectedJkocHack);
                            con.put("analogScratch", selectedAnalogScratch);
                            con.put("analogScratchThreshold", selectedAnalogScratchThreshold);
                            con.put("analogScratchMode", selectedAnalogScratchMode);
                        }
                    }
                }

                // 更新键盘配置 (鼠标转盘)
                org.json.JSONObject kbConfig = pc.optJSONObject("keyboardConfig");
                if (kbConfig != null) {
                    org.json.JSONObject mouseConfig = kbConfig.optJSONObject("mouseScratchConfig");
                    if (mouseConfig == null) mouseConfig = new org.json.JSONObject();
                    mouseConfig.put("mouseScratch", selectedMouseScratch);
                    mouseConfig.put("mouseScratchThreshold", selectedMouseScratchThreshold);
                    mouseConfig.put("mouseScratchDistance", selectedMouseScratchDistance);
                    mouseConfig.put("mouseScratchMode", selectedMouseScratchMode);
                    kbConfig.put("mouseScratchConfig", mouseConfig);
                }

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
        Thread thread = new Thread(() -> {
            try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(getContentResolver().openOutputStream(uri))) {
                File playerDir = new File(getExternalFilesDir(null), "player/" + selectedPlayerName);
                byte[] buf = new byte[8192];
                addDirectoryToZip(zos, playerDir, "", buf);
                runOnUiThread(() -> Toast.makeText(this, getString(R.string.msg_score_exported), Toast.LENGTH_SHORT).show());
            } catch (Exception e) { runOnUiThread(() -> Toast.makeText(this, getString(R.string.msg_export_failed, e.getMessage()), Toast.LENGTH_LONG).show()); }
        });
        synchronized (backgroundThreads) { backgroundThreads.add(thread); }
        thread.start();
    }

    private void addDirectoryToZip(java.util.zip.ZipOutputStream zos, File dir, String basePath, byte[] buf) throws java.io.IOException {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                // 跳过 config_player.json，避免导出的 score.zip 覆盖目标玩家的键位/选项配置
                if (file.getName().equals("config_player.json")) continue;
                String entryPath = basePath.isEmpty() ? file.getName() : basePath + "/" + file.getName();
                if (file.isDirectory()) {
                    addDirectoryToZip(zos, file, entryPath, buf);
                } else {
                    zos.putNextEntry(new java.util.zip.ZipEntry(entryPath));
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                        int r;
                        while ((r = fis.read(buf)) != -1) zos.write(buf, 0, r);
                    }
                    zos.closeEntry();
                }
            }
        }
    }

    private void importScoreDatabase() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("application/zip");
        startActivityForResult(intent, REQUEST_CODE_IMPORT_SCORE);
    }

    private void importScoreFromUri(Uri uri) {
        Thread thread = new Thread(() -> {
            try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(getContentResolver().openInputStream(uri))) {
                File playerDir = new File(getExternalFilesDir(null), "player/" + selectedPlayerName);
                byte[] buf = new byte[8192];
                java.util.zip.ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String name = entry.getName();
                    // 防止路径遍历攻击：确保文件不会写到目标目录之外
                    File outFile = new File(playerDir, name);
                    String canonicalDir = playerDir.getCanonicalPath();
                    String canonicalFile = outFile.getCanonicalPath();
                    if (!canonicalFile.startsWith(canonicalDir + java.io.File.separator)) {
                        zis.closeEntry();
                        continue;
                    }
                    // 确保父目录存在
                    if (entry.isDirectory()) {
                        outFile.mkdirs();
                    } else {
                        outFile.getParentFile().mkdirs();
                        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile)) {
                            int r;
                            while ((r = zis.read(buf)) != -1) fos.write(buf, 0, r);
                        }
                    }
                    zis.closeEntry();
                }
                runOnUiThread(() -> Toast.makeText(this, getString(R.string.msg_score_imported), Toast.LENGTH_SHORT).show());
            } catch (Exception e) { runOnUiThread(() -> Toast.makeText(this, getString(R.string.msg_score_import_failed, e.getMessage()), Toast.LENGTH_LONG).show()); }
        });
        synchronized (backgroundThreads) { backgroundThreads.add(thread); }
        thread.start();
    }

    private void importPlayerConfig() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("application/json");
        startActivityForResult(intent, REQUEST_CODE_IMPORT_PLAYER);
    }

    private void importPlayerFromUri(Uri uri) {
        Thread thread = new Thread(() -> {
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
        });
        synchronized (backgroundThreads) { backgroundThreads.add(thread); }
        thread.start();
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

    private void showRenamePlayerDialog() {
        final String oldName = selectedPlayerName;
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
        b.setTitle(getString(R.string.dialog_rename_player));
        final EditText input = new EditText(this); input.setHint(getString(R.string.hint_player_name));
        input.setText(oldName); input.setSelection(oldName.length());
        input.setTextColor(0xFFFFFFFF); input.setBackgroundColor(0xFF333333);
        LinearLayout c = new LinearLayout(this); c.setPadding(32, 16, 32, 16); c.addView(input); b.setView(c);
        b.setPositiveButton(getString(R.string.rename_player), (d, w) -> {
            String newName = input.getText().toString().trim();
            if (newName.isEmpty()) {
                Toast.makeText(this, getString(R.string.msg_player_empty), Toast.LENGTH_SHORT).show();
                return;
            }
            if (newName.equals(oldName)) {
                Toast.makeText(this, getString(R.string.msg_player_rename_same), Toast.LENGTH_SHORT).show();
                return;
            }
            if (availablePlayers.contains(newName)) {
                Toast.makeText(this, getString(R.string.msg_player_rename_exists, newName), Toast.LENGTH_SHORT).show();
                return;
            }
            File playerRoot = getExternalFilesDir(null);
            File oldDir = new File(playerRoot, "player/" + oldName);
            File newDir = new File(playerRoot, "player/" + newName);
            if (!oldDir.renameTo(newDir)) {
                Log.e("SettingsActivity", "Rename player folder failed: " + oldDir + " -> " + newDir);
                Toast.makeText(this, getString(R.string.msg_player_rename_same), Toast.LENGTH_SHORT).show();
                return;
            }
            int idx = availablePlayers.indexOf(oldName);
            if (idx >= 0) availablePlayers.set(idx, newName);
            selectedPlayerName = newName;
            ((ArrayAdapter) playerSpinner.getAdapter()).notifyDataSetChanged();
            playerSpinner.setSelection(idx >= 0 ? idx : 0);
            readPlayOptionsFromPlayerConfig();
            saveConfigToJson();
            Toast.makeText(this, getString(R.string.msg_player_renamed, newName), Toast.LENGTH_SHORT).show();
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
        gamepadMode = false;
        readAllOptionsFromUI();
        saveConfigToJson();
    }

    @Override protected void onPause() {
        stopKeepAlive();
        super.onPause();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            if (isGamepadKeyCode(keyCode)) {
                if (!gamepadMode) {
                    gamepadMode = true;
                    updateTouchModeForGamepad();
                }
                lastGamepadInputTime = SystemClock.uptimeMillis();
            }
            if (gamepadMode) {
                // 优先让系统处理 DPAD 事件，如果系统处理了（例如 Spinner 下拉框或者标准焦点切换），则不再执行自定义逻辑
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                    keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (super.dispatchKeyEvent(event)) return true;
                }

                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) { moveFocus(MoveDirection.UP); return true; }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) { moveFocus(MoveDirection.DOWN); return true; }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { moveFocus(MoveDirection.LEFT); return true; }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { moveFocus(MoveDirection.RIGHT); return true; }
                if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
                    readAllOptionsFromUI(); saveConfigToJson(); finish(); return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A || keyCode == KeyEvent.KEYCODE_ENTER) {
                    activateCurrentFocus(); return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private enum MoveDirection { UP, DOWN, LEFT, RIGHT }

    private boolean isGamepadKeyCode(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_BUTTON_A || keyCode == KeyEvent.KEYCODE_BUTTON_B ||
               keyCode == KeyEvent.KEYCODE_BUTTON_X || keyCode == KeyEvent.KEYCODE_BUTTON_Y ||
               keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
               keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
               keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER;
    }

    private void moveFocus(MoveDirection direction) {
        buildFocusableControlsList();
        View current = getCurrentFocus();
        if (current == null) {
            if (!focusableControls.isEmpty()) {
                focusableControls.get(0).requestFocus();
                updateFocusIndicator(focusableControls.get(0));
            }
            return;
        }
        int currentIndex = focusableControls.indexOf(current);
        int nextIndex = -1;
        switch (direction) {
            case DOWN: nextIndex = findNextDown(current, currentIndex); break;
            case UP: nextIndex = findPrevUp(current, currentIndex); break;
            case RIGHT: nextIndex = findNextRight(current, currentIndex); break;
            case LEFT: nextIndex = findPrevLeft(current, currentIndex); break;
        }
        if (nextIndex >= 0 && nextIndex < focusableControls.size()) {
            View next = focusableControls.get(nextIndex);
            next.requestFocus();
            ensureViewVisible(next);
        }
    }

    private int findNextDown(View current, int currentIndex) {
        int bestIndex = -1; long bestScore = Long.MAX_VALUE;
        int[] currLoc = new int[2]; current.getLocationOnScreen(currLoc);
        int currentBottom = currLoc[1] + current.getHeight();
        int currentCenterX = currLoc[0] + current.getWidth() / 2;
        for (int i = 0; i < focusableControls.size(); i++) {
            if (i == currentIndex) continue;
            View v = focusableControls.get(i);
            int[] vLoc = new int[2]; v.getLocationOnScreen(vLoc);
            if (vLoc[1] <= currentBottom - 5) continue;
            int vCenterX = vLoc[0] + v.getWidth() / 2;
            int distX = Math.abs(vCenterX - currentCenterX);
            int distY = vLoc[1] - currentBottom;
            long score = (long) distY * 1000 + distX;
            if (score < bestScore) { bestScore = score; bestIndex = i; }
        }
        return bestIndex >= 0 ? bestIndex : currentIndex;
    }

    private int findPrevUp(View current, int currentIndex) {
        int bestIndex = -1; long bestScore = Long.MAX_VALUE;
        int[] currLoc = new int[2]; current.getLocationOnScreen(currLoc);
        int currentTop = currLoc[1];
        int currentCenterX = currLoc[0] + current.getWidth() / 2;
        for (int i = 0; i < focusableControls.size(); i++) {
            if (i == currentIndex) continue;
            View v = focusableControls.get(i);
            int[] vLoc = new int[2]; v.getLocationOnScreen(vLoc);
            int vBottom = vLoc[1] + v.getHeight();
            if (vBottom >= currentTop + 5) continue;
            int vCenterX = vLoc[0] + v.getWidth() / 2;
            int distX = Math.abs(vCenterX - currentCenterX);
            int distY = currentTop - vBottom;
            long score = (long) distY * 1000 + distX;
            if (score < bestScore) { bestScore = score; bestIndex = i; }
        }
        return bestIndex >= 0 ? bestIndex : currentIndex;
    }

    private int findNextRight(View current, int currentIndex) {
        int bestIndex = -1; long bestScore = Long.MAX_VALUE;
        int[] currLoc = new int[2]; current.getLocationOnScreen(currLoc);
        int currentRight = currLoc[0] + current.getWidth();
        int currentCenterY = currLoc[1] + current.getHeight() / 2;
        for (int i = 0; i < focusableControls.size(); i++) {
            if (i == currentIndex) continue;
            View v = focusableControls.get(i);
            int[] vLoc = new int[2]; v.getLocationOnScreen(vLoc);
            if (vLoc[0] <= currentRight - 5) continue;
            int vCenterY = vLoc[1] + v.getHeight() / 2;
            int distY = Math.abs(vCenterY - currentCenterY);
            int distX = vLoc[0] - currentRight;
            long score = (long) distX * 1000 + distY;
            if (score < bestScore) { bestScore = score; bestIndex = i; }
        }
        return bestIndex >= 0 ? bestIndex : currentIndex;
    }

    private int findPrevLeft(View current, int currentIndex) {
        int bestIndex = -1; long bestScore = Long.MAX_VALUE;
        int[] currLoc = new int[2]; current.getLocationOnScreen(currLoc);
        int currentLeft = currLoc[0];
        int currentCenterY = currLoc[1] + current.getHeight() / 2;
        for (int i = 0; i < focusableControls.size(); i++) {
            if (i == currentIndex) continue;
            View v = focusableControls.get(i);
            int[] vLoc = new int[2]; v.getLocationOnScreen(vLoc);
            int vRight = vLoc[0] + v.getWidth();
            if (vRight >= currentLeft + 5) continue;
            int vCenterY = vLoc[1] + v.getHeight() / 2;
            int distY = Math.abs(vCenterY - currentCenterY);
            int distX = currentLeft - vRight;
            long score = (long) distX * 1000 + distY;
            if (score < bestScore) { bestScore = score; bestIndex = i; }
        }
        return bestIndex >= 0 ? bestIndex : currentIndex;
    }

    private void buildFocusableControlsList() {
        focusableControls.clear();
        View root = findViewById(android.R.id.content);
        if (root != null) collectFocusableViews((ViewGroup) root);
    }

    private void collectFocusableViews(ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE) {
                if (child.isFocusable() && !(child instanceof android.widget.ScrollView)) focusableControls.add(child);
                if (child instanceof ViewGroup) collectFocusableViews((ViewGroup) child);
            }
        }
    }

    private void ensureViewVisible(View view) {
        if (settingsScrollView == null || view == null) return;
        int[] scrollLoc = new int[2];
        settingsScrollView.getLocationOnScreen(scrollLoc);
        int[] viewLoc = new int[2];
        view.getLocationOnScreen(viewLoc);

        int viewTop = viewLoc[1];
        int viewBottom = viewLoc[1] + view.getHeight();
        int scrollTop = scrollLoc[1];
        int scrollBottom = scrollLoc[1] + settingsScrollView.getHeight();

        if (viewTop < scrollTop + 150) {
            settingsScrollView.smoothScrollBy(0, viewTop - scrollTop - 200);
        } else if (viewBottom > scrollBottom - 150) {
            settingsScrollView.smoothScrollBy(0, viewBottom - scrollBottom + 200);
        }
    }

    private void activateCurrentFocus() {
        View focused = getCurrentFocus();
        if (focused != null) {
            focused.performClick();
            if (focused instanceof EditText && gamepadMode) showCharacterWheelForEditText((EditText) focused);
        }
    }

    private void updateTouchModeForGamepad() {
        buildFocusableControlsList();
        View focused = getCurrentFocus();
        if (focused != null) {
            updateFocusIndicator(focused);
            ensureViewVisible(focused);
        } else if (!focusableControls.isEmpty()) {
            View first = focusableControls.get(0);
            first.requestFocus();
            updateFocusIndicator(first);
            ensureViewVisible(first);
        }
    }

    private void updateFocusIndicator(View focusedView) {
        if (focusedView == null || !gamepadMode) {
            if (focusIndicator != null) focusIndicator.setVisibility(View.GONE);
            return;
        }
        if (focusIndicator == null) {
            focusIndicator = new View(this);
            focusIndicator.setBackgroundResource(R.drawable.focus_highlight);
            focusIndicator.setFocusable(false);
            focusIndicator.setClickable(false);
            ((ViewGroup) findViewById(android.R.id.content)).addView(focusIndicator);
        }
        focusIndicator.setVisibility(View.VISIBLE);
        int[] loc = new int[2]; focusedView.getLocationInWindow(loc);
        int[] rootLoc = new int[2]; findViewById(android.R.id.content).getLocationInWindow(rootLoc);
        focusIndicator.setX(loc[0] - rootLoc[0]);
        focusIndicator.setY(loc[1] - rootLoc[1]);
        ViewGroup.LayoutParams lp = focusIndicator.getLayoutParams();
        lp.width = focusedView.getWidth(); lp.height = focusedView.getHeight();
        focusIndicator.setLayoutParams(lp);
        focusIndicator.bringToFront();
    }

    private void setupGamepadFocusable(View view) {
        if (view == null) return;
        view.setFocusable(true);
        // 只有输入框允许在触摸模式下获取焦点，否则普通按钮需要点击两次才能触发（第一下获取焦点，第二下触发点击）
        view.setFocusableInTouchMode(view instanceof EditText);
    }

    private boolean isDescendantOf(ViewGroup ancestor, View descendant) {
        android.view.ViewParent parent = descendant.getParent();
        while (parent != null) {
            if (parent == ancestor) return true;
            parent = parent.getParent();
        }
        return false;
    }

    private void showCharacterWheelForEditText(EditText editText) {
        if (currentCharacterWheel != null && currentCharacterWheel.isShowing()) {
            currentCharacterWheel.dismiss();
        }
        currentCharacterWheel = new CharacterWheelDialog(this, editText.getText().toString(), text -> {
            editText.setText(text); editText.setSelection(text.length());
        });
        currentCharacterWheel.show();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;

        // 移除 ViewTreeObserver 监听器
        try {
            View decorView = getWindow().getDecorView();
            if (decorView != null) {
                if (focusChangeListener != null) {
                    decorView.getViewTreeObserver().removeOnGlobalFocusChangeListener(focusChangeListener);
                    focusChangeListener = null;
                }
                if (scrollChangedListener != null) {
                    decorView.getViewTreeObserver().removeOnScrollChangedListener(scrollChangedListener);
                    scrollChangedListener = null;
                }
            }
        } catch (Exception ignored) {}

        // 停止所有后台线程
        synchronized (backgroundThreads) {
            for (Thread t : backgroundThreads) {
                if (t != null && t.isAlive()) {
                    try {
                        t.interrupt();
                        t.join(500);
                    } catch (InterruptedException ignored) {}
                }
            }
            backgroundThreads.clear();
        }

        // 停止 Handler 的所有 callbacks
        if (keepAliveHandler != null) {
            keepAliveHandler.removeCallbacksAndMessages(null);
            keepAliveHandler = null;
        }

        // 关闭 Dialog
        if (currentCharacterWheel != null && currentCharacterWheel.isShowing()) {
            currentCharacterWheel.dismiss();
            currentCharacterWheel = null;
        }

        // 清理 View 引用，帮助 GC
        settingsScrollView = null;
        bmsPathContainer = null;
        tableUrlContainer = null;
        playerSpinner = null;
        focusIndicator = null;
        if (focusableControls != null) {
            focusableControls.clear();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backInvokedCallback != null) {
            try {
                getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    (android.window.OnBackInvokedCallback) backInvokedCallback);
            } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

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
        showAudioSpectrum = ((Spinner) findViewById(R.id.audioVisualizationModeSpinner)).getSelectedItemPosition() != 0;
        audioVisualizationMode = ((Spinner) findViewById(R.id.audioVisualizationModeSpinner)).getSelectedItemPosition();
        selectedScanSongsOnLaunch = ((Switch) findViewById(R.id.scanSongsOnLaunchSwitch)).isChecked();
        selectedFloatingMenuPosition = ((Spinner) findViewById(R.id.floatingMenuPositionSpinner)).getSelectedItemPosition();
        selectedBga = ((Spinner) findViewById(R.id.bgaDisplaySpinner)).getSelectedItemPosition();
        selectedBgaExpand = ((Spinner) findViewById(R.id.bgaExpandSpinner)).getSelectedItemPosition();
        selectedStretchFullscreen = ((Switch) findViewById(R.id.stretchFullscreenSwitch)).isChecked();
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
        try { selectedInputDuration = Integer.parseInt(((EditText) findViewById(R.id.inputDurationInput)).getText().toString()); } catch (Exception ignored) {}
        selectedJkocHack = ((Switch) findViewById(R.id.jkocHackSwitch)).isChecked();
        selectedAnalogScratch = ((Switch) findViewById(R.id.analogScratchSwitch)).isChecked();
        try { selectedAnalogScratchThreshold = Integer.parseInt(((EditText) findViewById(R.id.analogScratchThresholdInput)).getText().toString()); } catch (Exception ignored) {}
        selectedAnalogScratchMode = ((Spinner) findViewById(R.id.analogScratchAlgorithmSpinner)).getSelectedItemPosition();
        selectedMouseScratch = ((Switch) findViewById(R.id.mouseScratchSwitch)).isChecked();
        try { selectedMouseScratchThreshold = Integer.parseInt(((EditText) findViewById(R.id.mouseScratchThresholdInput)).getText().toString()); } catch (Exception ignored) {}
        try { selectedMouseScratchDistance = Integer.parseInt(((EditText) findViewById(R.id.mouseScratchDistanceInput)).getText().toString()); } catch (Exception ignored) {}
        selectedMouseScratchMode = ((Spinner) findViewById(R.id.mouseScratchAlgorithmSpinner)).getSelectedItemPosition();
        selectedTargetScore = ((Spinner) findViewById(R.id.targetScoreSpinner)).getSelectedItemPosition();
        selectedGaugeType = ((Spinner) findViewById(R.id.gaugeTypeSpinner)).getSelectedItemPosition();
        try { selectedNoteTimingOffset = Integer.parseInt(((EditText) findViewById(R.id.noteTimingOffsetInput)).getText().toString()); } catch (Exception ignored) {}
        selectedAutoTimingAdjust = ((Switch) findViewById(R.id.autoTimingAdjustSwitch)).isChecked();
        selectedNoteModifier = ((Spinner) findViewById(R.id.noteModifierSpinner)).getSelectedItemPosition();
        selectedHispeedFix = ((Spinner) findViewById(R.id.hispeedFixSpinner)).getSelectedItemPosition();
        selectedEnableLanecover = ((Switch) findViewById(R.id.enableLanecoverSwitch)).isChecked();
        selectedEnableLift = ((Switch) findViewById(R.id.enableLiftSwitch)).isChecked();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (isSimulatingTouch) return true;
        return super.dispatchTouchEvent(ev);
    }

    /**
     * 当「拉伸至全屏」开启时，强制禁用并关闭音频频谱开关；
     * 关闭时恢复可调，但 showAudioSpectrum 的值会保持锁定期间被强制设置的 false，
     * 用户可以重新手动开启。
     */
    private void applyStretchFullscreenLockState() {
        Spinner audioVisualizationModeSpinner = findViewById(R.id.audioVisualizationModeSpinner);
        if (selectedStretchFullscreen) {
            audioVisualizationModeSpinner.setEnabled(false);
            audioVisualizationModeSpinner.setSelection(0);
            audioVisualizationMode = 0;
            showAudioSpectrum = false;
        } else {
            audioVisualizationModeSpinner.setEnabled(true);
        }
    }

    private void updatePlayOptionsUI() {
        try {
            ((Spinner) findViewById(R.id.gaugeAutoShiftSpinner)).setSelection(selectedGaugeAutoShift);
            ((Spinner) findViewById(R.id.hispeedFixSpinner)).setSelection(selectedHispeedFix);
            Spinner[] s = {findViewById(R.id.autoSaveReplay1), findViewById(R.id.autoSaveReplay2), findViewById(R.id.autoSaveReplay3), findViewById(R.id.autoSaveReplay4)};
            for (int i = 0; i < 4; i++) s[i].setSelection(selectedAutoSaveReplay[i]);
            ((EditText) findViewById(R.id.greenNumberInput)).setText(String.valueOf(selectedGreenNumber));
            ((EditText) findViewById(R.id.inputDurationInput)).setText(String.valueOf(selectedInputDuration));
            ((Switch) findViewById(R.id.jkocHackSwitch)).setChecked(selectedJkocHack);
            ((Switch) findViewById(R.id.analogScratchSwitch)).setChecked(selectedAnalogScratch);
            ((EditText) findViewById(R.id.analogScratchThresholdInput)).setText(String.valueOf(selectedAnalogScratchThreshold));
            ((Spinner) findViewById(R.id.analogScratchAlgorithmSpinner)).setSelection(selectedAnalogScratchMode);
            ((Switch) findViewById(R.id.mouseScratchSwitch)).setChecked(selectedMouseScratch);
            ((EditText) findViewById(R.id.mouseScratchThresholdInput)).setText(String.valueOf(selectedMouseScratchThreshold));
            ((EditText) findViewById(R.id.mouseScratchDistanceInput)).setText(String.valueOf(selectedMouseScratchDistance));
            ((Spinner) findViewById(R.id.mouseScratchAlgorithmSpinner)).setSelection(selectedMouseScratchMode);
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
        // 在启动游戏前先清理所有资源，释放内存
        cleanupResources();

        Intent intent = new Intent(this, AndroidLauncher.class);
        // 不使用 FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP：
        // 这些标志会销毁 SettingsActivity 并重建任务栈，导致 Android 16
        // 将 AndroidLauncher 视为全新的顶层应用上下文，从而要求屏幕录像等
        // overlay 应用重新获取"显示在应用上层"权限。
        // 改为同任务内标准 Activity 切换，finish() 会关闭 SettingsActivity。
        startActivity(intent);
        finish();
    }

    /** 提前清理资源，用于 launchGame 时释放内存 */
    private void cleanupResources() {
        destroyed = true;

        // 移除 ViewTreeObserver 监听器
        try {
            View decorView = getWindow().getDecorView();
            if (decorView != null) {
                if (focusChangeListener != null) {
                    decorView.getViewTreeObserver().removeOnGlobalFocusChangeListener(focusChangeListener);
                    focusChangeListener = null;
                }
                if (scrollChangedListener != null) {
                    decorView.getViewTreeObserver().removeOnScrollChangedListener(scrollChangedListener);
                    scrollChangedListener = null;
                }
            }
        } catch (Exception ignored) {}

        // 停止所有后台线程
        synchronized (backgroundThreads) {
            for (Thread t : backgroundThreads) {
                if (t != null && t.isAlive()) {
                    try {
                        t.interrupt();
                        t.join(100);
                    } catch (InterruptedException ignored) {}
                }
            }
            backgroundThreads.clear();
        }

        // 停止 Handler
        if (keepAliveHandler != null) {
            keepAliveHandler.removeCallbacksAndMessages(null);
        }

        // 关闭 Dialog
        if (currentCharacterWheel != null && currentCharacterWheel.isShowing()) {
            currentCharacterWheel.dismiss();
            currentCharacterWheel = null;
        }

        // 清理 View 引用
        settingsScrollView = null;
        bmsPathContainer = null;
        tableUrlContainer = null;
        playerSpinner = null;
        focusIndicator = null;
        if (focusableControls != null) {
            focusableControls.clear();
        }

        // 清理 List 引用
        if (bmsPaths != null) bmsPaths.clear();
        if (tableUrls != null) tableUrls.clear();
        if (availablePlayers != null) availablePlayers.clear();
    }

    private void showHelpDialog(String title, String message) {
        new android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show();
    }
}
