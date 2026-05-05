package com.starxh.beatoraja.android;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.content.res.AssetManager;
import android.util.Log;
import android.view.KeyEvent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import com.badlogic.gdx.Audio;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.badlogic.gdx.backends.android.AndroidAudio;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.controllers.Controllers;
import com.starxh.beatoraja.BeatorajaGame;
import barsoosayque.libgdxoboe.OboeAudio;

import java.io.*;

import bms.player.beatoraja.Config;
import bms.player.beatoraja.PlayerConfig;
import bms.player.beatoraja.BMSPlayerMode;
import bms.player.beatoraja.input.KeyBoardInputProcesseor;

public class AndroidLauncher extends AndroidApplication {
    private static final String TAG = "AndroidLauncher";
    private InputMethodManager inputMethodManager;
    private volatile boolean isTextInputActive = false;  // 标记是否有文本输入框活跃

    @Override
    public AndroidAudio createAudio(Context context, AndroidApplicationConfiguration config) {
        try {
            OboeAudio audio = new OboeAudio(context.getAssets());
            Log.i(TAG, "OboeAudio initialized successfully (low-latency audio)");
            return audio;
        } catch (Throwable t) {
            Log.w(TAG, "OboeAudio initialization failed, falling back to default AndroidAudio: " + t.getMessage());
            return super.createAudio(context, config);
        }
    }

    /**
     * Android 13+ (API 33) 全面屏手势返回支持。
     * Android 10+ 引入手势导航后，边缘滑动返回不会触发 onKeyDown(KEYCODE_BACK)，
     * 而是通过 OnBackInvokedDispatcher 回调。这里注册回调将其映射为 Escape 键。
     */
    private Object backInvokedCallback;

    /**
     * 增强型周期性用户活跃度保活：每 1 秒向 DecorView 派发一个模拟的 ACTION_DOWN + ACTION_UP 事件序列。
     * 相比单一的 ACTION_CANCEL，完整触摸序列更难被系统识别为"伪造"，能更可靠地重置 DVFS 空闲计时器。
     * 触摸坐标在屏幕中心附近微小随机偏移，避免检测出固定坐标模式。
     */
    private Handler keepAliveHandler;
    private final java.util.Random keepAliveRandom = new java.util.Random();
    private volatile long lastUserTouchTime = 0; // 记录最后一次真实用户触摸的时间
    private volatile boolean isUserTouching = false; // 记录用户当前是否正在触摸屏幕
    private boolean isSimulatingTouch = false; // 标记当前是否正在执行保活模拟触摸

    private final Runnable keepAliveRunnable = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            // 策略优化：如果正在文本输入，或者用户当前正在触摸，或者最近 2 秒内有过任何真实操作，
            // 则跳过本轮模拟。真实的操作已经起到了保活作用，避免不必要的模拟。
            if (isTextInputActive || isUserTouching || (now - lastUserTouchTime < 2000)) {
                if (keepAliveHandler != null) {
                    keepAliveHandler.postDelayed(this, 1000);
                }
                return;
            }
            try {
                Window w = getWindow();
                if (w != null && w.getDecorView() != null) {
                    isSimulatingTouch = true;
                    // 使用 Generic Motion Event (ACTION_SCROLL) 替代 Touch Event (ACTION_DOWN/UP)
                    // 1. 它是合法的 InputEvent，能有效触发系统 DVFS 唤醒，保持 CPU/GPU 高频运行防止掉帧。
                    // 2. 它不属于“触摸指针序列”，没有指针位置的概念，不会干扰点击/滑动轨迹，也不会导致指针跳变。
                    // 3. 通过 setSource 设置为 SOURCE_MOUSE，与常规触摸屏事件物理隔离。
                    MotionEvent scrollEvent = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 0, 0, 0);
                    scrollEvent.setSource(android.view.InputDevice.SOURCE_MOUSE);
                    w.getDecorView().dispatchGenericMotionEvent(scrollEvent);
                    scrollEvent.recycle();
                    isSimulatingTouch = false;
                }
            } catch (Throwable t) {
                isSimulatingTouch = false;
            }
            if (keepAliveHandler != null) {
                keepAliveHandler.postDelayed(this, 1000);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 注册 OnBackInvokedCallback（API 33+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.window.OnBackInvokedCallback callback = () -> {
                Log.d(TAG, "Edge swipe back gesture detected (API 33+)");
                setAndroidBackPressedFlag();
            };
            backInvokedCallback = callback;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
        }

        Log.i(TAG, "=== Application starting - Strict initialization sequence ===");

        // ===================================================================
        // 阶段 1: 请求存储权限（必须在所有文件操作之前）
        // ===================================================================
        if (!checkAndRequestStoragePermissions()) {
            Log.e(TAG, "Storage permissions not granted, cannot continue");
            // 注意：这里不应该直接 finish()，因为用户可能返回后授权
            // 我们将在 onResume 中检查权限后再继续初始化
            pendingInitialization = true;
            return;
        }
        Log.i(TAG, "Step 1: Storage permissions granted");

        // ===================================================================
        // 阶段 2: 设置基础路径和目录
        // ===================================================================
        AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        config.useImmersiveMode = true;
        config.useCompass = false;
        config.useGyroscope = false;
        config.useAccelerometer = false;
        config.useWakelock = true;
        config.numSamples = 0; // 2D游戏不需要MSAA，关闭以大幅降低GPU填充率开销

        // ── Android设备GPU优化标志 ──
        // 检测是否为低端设备（API 27）
        boolean isLowEndDevice = Build.VERSION.SDK_INT <= 27 ||
            (Runtime.getRuntime().maxMemory() / 1024 / 1024) < 512; // 内存小于512MB视为低端设备
        if (isLowEndDevice) {
            Log.i(TAG, "Low-end device detected, enabling GPU optimizations");
            System.setProperty("beatoraja.low_memory", "true");
        }

        // ── 启用Android GPU渲染优化标志 ──
        try {
            // 设置窗口像素格式优化（减少像素格式转换）
            getWindow().setFormat(android.graphics.PixelFormat.RGBA_8888);
            // 启用硬件加速标志
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
            Log.i(TAG, "Window hardware acceleration flags set");
        } catch (Exception e) {
            Log.w(TAG, "Failed to set window pixel format", e);
        }

        File filesDir = getExternalFilesDir(null);
        String root = filesDir.getAbsolutePath();
        System.setProperty("beatoraja.root", root);
        Log.i(TAG, "Step 2: Root path set to: " + root);

        // 预创建目录
        String[] dirs = {
            "player", "skin", "font", "table", "glsl", "favorite", "course", "random", "folder",
            "skin/default/keyconfig"
        };
        for (String d : dirs) {
            File dir = new File(filesDir, d);
            if (!dir.exists()) {
                dir.mkdirs();
            }
        }

        // 搬运 assets 资源
        copyAssetsToFilesDir("glsl", new File(filesDir, "glsl"));
        copyAssetsToFilesDir("skin", new File(filesDir, "skin"));
        copyAssetsToFilesDir("folder", new File(filesDir, "folder"));
        copyAssetsToFilesDir("random", new File(filesDir, "random"));
        copyAssetsToFilesDir("font", new File(filesDir, "font"));
        copyAssetsToFilesDir("sound", new File(filesDir, "sound"));

        // ===================================================================
        // 阶段 3: 设置 BMS Root Path
        // ===================================================================
        String defaultBmsRoot = getDownloadPath() + "/oraja_bms";
        Log.i(TAG, "Step 3: BMS root path set to: " + defaultBmsRoot);

        // 确保 BMS 目录存在
        File bmsDir = new File(defaultBmsRoot);
        if (!bmsDir.exists()) {
            boolean created = bmsDir.mkdirs();
            Log.i(TAG, "Created BMS directory: " + created + " - " + defaultBmsRoot);
        }

        // 创建 .nomedia 文件，防止系统媒体扫描器扫描 BMS 文件夹
        File nomediaFile = new File(defaultBmsRoot, ".nomedia");
        if (!nomediaFile.exists()) {
            try {
                boolean created = nomediaFile.createNewFile();
                Log.i(TAG, "Created .nomedia file: " + created + " - " + nomediaFile.getAbsolutePath());
            } catch (IOException e) {
                Log.e(TAG, "Failed to create .nomedia file", e);
            }
        } else {
            Log.i(TAG, ".nomedia file already exists");
        }

        // 从 assets 复制默认 BMS 谱面到 BMS 目录（仅首次运行）
        copyBmsAssets(defaultBmsRoot);

        // 复制 inochi_ogg 目录到 BMS 目录（inochi 系列 BMS 谱面，包含 .bms 和 .ogg 资源）
        copyInochiOggAssets(defaultBmsRoot);

        // ===================================================================
        // 阶段 4: 初始化配置文件路径
        // ===================================================================
        Config.updateConfigPath();
        PlayerConfig.updateConfigPath();
        Log.i(TAG, "Step 4: Config paths updated");

        // ===================================================================
        // 阶段 4.5: 读取配置中的 player name 和 bmsroot，手动解析（避免在 LibGDX 初始化前调用 Gdx.files）
        // ===================================================================
        String playerName = "player1";
        String[] bmsrootFromConfig = new String[0];
        try {
            File configFile = new File(filesDir, "config_sys.json");
            if (configFile.exists()) {
                String content = new String(java.nio.file.Files.readAllBytes(configFile.toPath()), "UTF-8");

                // 解析 playername
                int nameIndex = content.indexOf("\"playername\"");
                if (nameIndex >= 0) {
                    int colonIndex = content.indexOf(":", nameIndex);
                    if (colonIndex >= 0) {
                        int start = colonIndex + 1;
                        while (start < content.length() && (content.charAt(start) == ' ' || content.charAt(start) == '"')) start++;
                        int end = start;
                        while (end < content.length() && content.charAt(end) != '"' && content.charAt(end) != ',' && content.charAt(end) != '}') end++;
                        if (end > start) {
                            playerName = content.substring(start, end).trim();
                            // Handle JSON escaped slashes
                            playerName = playerName.replace("\\/", "/");
                        }
                    }
                }

                // 解析 bmsroot 数组
                int bmsrootIndex = content.indexOf("\"bmsroot\"");
                if (bmsrootIndex >= 0) {
                    int bracketStart = content.indexOf("[", bmsrootIndex);
                    int bracketEnd = content.indexOf("]", bracketStart);
                    if (bracketStart >= 0 && bracketEnd >= 0) {
                        String arrayContent = content.substring(bracketStart + 1, bracketEnd);
                        java.util.List<String> paths = new java.util.ArrayList<>();
                        int pos = 0;
                        while (pos < arrayContent.length()) {
                            while (pos < arrayContent.length() && (arrayContent.charAt(pos) == ' ' || arrayContent.charAt(pos) == ',' || arrayContent.charAt(pos) == '\n' || arrayContent.charAt(pos) == '\r')) pos++;
                            if (pos >= arrayContent.length()) break;
                            if (arrayContent.charAt(pos) == '"') {
                                pos++;
                                int start = pos;
                                while (pos < arrayContent.length() && arrayContent.charAt(pos) != '"') pos++;
                                if (pos < arrayContent.length()) {
                                    String path = arrayContent.substring(start, pos).trim();
                                    // Handle JSON escaped slashes
                                    path = path.replace("\\/", "/");
                                    // Collapse any duplicate slashes
                                    while (path.contains("//")) {
                                        path = path.replace("//", "/");
                                    }
                                    paths.add(path);
                                    pos++;
                                }
                            } else {
                                while (pos < arrayContent.length() && arrayContent.charAt(pos) != ',' && arrayContent.charAt(pos) != ']') pos++;
                            }
                        }
                        if (!paths.isEmpty()) {
                            bmsrootFromConfig = paths.toArray(new String[0]);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read config: " + e.getMessage());
        }
        Log.i(TAG, "Step 4.5: Player name = " + playerName + ", bmsroot count = " + bmsrootFromConfig.length);

        // 使用配置的 bmsroot（如果有），否则使用默认路径
        String[] allBmsRoots;
        if (bmsrootFromConfig.length > 0) {
            allBmsRoots = bmsrootFromConfig;
        } else {
            allBmsRoots = new String[]{defaultBmsRoot};
        }
        StringBuilder bmsRootsLog = new StringBuilder("BMS roots: ");
        for (String r : allBmsRoots) {
            bmsRootsLog.append(r).append(", ");
        }
        Log.i(TAG, bmsRootsLog.toString());

        String playerDirPath = filesDir.getAbsolutePath() + "/player/" + playerName;
        File playerDir = new File(playerDirPath);
        if (!playerDir.exists()) {
            boolean created = playerDir.mkdirs();
            Log.i(TAG, "Created player directory: " + created + " - " + playerDirPath);
        } else {
            Log.i(TAG, "Player directory already exists: " + playerDirPath);
        }

        // ===================================================================
        // 阶段 5: 预初始化 WAL 模式（防止后续多线程死锁）
        // ===================================================================
        preInitWal(filesDir, playerDirPath);
        Log.i(TAG, "Step 5: WAL mode pre-initialized");

        // ===================================================================
        // 阶段 6: 初始化 SQLite 数据库（包含完整表结构）
        // ===================================================================
        try {
            String defaultDbPath = filesDir.getAbsolutePath() + "/songdata.db";
            bms.player.beatoraja.song.AndroidSQLiteSongDatabaseAccessor androidAccessor =
                    new bms.player.beatoraja.song.AndroidSQLiteSongDatabaseAccessor(this, defaultDbPath, allBmsRoots);
            bms.player.beatoraja.MainLoader.setSongDatabaseAccessor(androidAccessor);
            Log.i(TAG, "Step 6: Database initialized with bmsroot count: " + allBmsRoots.length);
        } catch (Throwable t) {
            Log.e(TAG, "Step 6: FATAL - Failed to initialize database", t);
            // 数据库初始化失败，不能继续
            return;
        }

        // ===================================================================
        // 阶段 7: 设置高刷新率
        // ===================================================================
        setupHighRefreshRate();


        getWindow().setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN |
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        );
        Log.i(TAG, "Soft keyboard initial state: ALWAYS_HIDDEN | ADJUST_NOTHING");

        // 初始化 InputMethodManager
        inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);

        // ===================================================================
        // 阶段 8: 启动 LibGDX（内部会触发异步扫描）
        // ===================================================================
        Log.i(TAG, "Step 8: Starting LibGDX game engine...");
        initialize(new BeatorajaGame(
            null,
            null,
            null,
            BMSPlayerMode.AUTOPLAY,
            true
        ), config);

        // 初始化后检测控制器
        detectAndLogControllers();

        // ===================================================================
        // 阶段 9: 设置持续高性能模式（防止无触控时降频）
        // ===================================================================
        setupSustainedPerformance();

        Log.i(TAG, "=== Initialization sequence completed ===");
    }

    private boolean pendingInitialization = false;

    /**
     * 当窗口焦点变化时，确保软键盘保持隐藏状态。
     * 这是防止 EGL 操作等触发软键盘的关键方法。
     * 注意：如果有文本输入框活跃（如搜索歌曲），则不隐藏软键盘。
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        Log.i(TAG, "onWindowFocusChanged: hasFocus=" + hasFocus + ", isTextInputActive=" + isTextInputActive);
        if (hasFocus && inputMethodManager != null && !isTextInputActive) {
            // 窗口获得焦点时立即隐藏软键盘（但如果有文本输入则不隐藏）
            inputMethodManager.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "onResume called, isTextInputActive=" + isTextInputActive);

        // 关键：只在非文本输入时设置 SOFT_INPUT_STATE_ALWAYS_HIDDEN
        // 如果正在文本输入，不要设置这个标志，否则会立即隐藏键盘
        if (!isTextInputActive) {
            getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN |
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            );
            // 确保软键盘隐藏
            if (inputMethodManager != null) {
                inputMethodManager.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);
            }
        } else {
            // 文本输入活跃时，只设置 ADJUST_NOTHING，不设置隐藏状态
            getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            );
        }

        // 如果之前因为权限问题暂停了初始化，现在重试
        if (pendingInitialization) {
            if (checkAndRequestStoragePermissions()) {
                Log.i(TAG, "Permissions now granted, resuming initialization");
                pendingInitialization = false;
                recreate(); // 重新触发 onCreate
            } else {
                Log.w(TAG, "Still waiting for storage permissions");
            }
        } else {
            Log.i(TAG, "GL context may have been restored, font textures will be rebuilt by MainController.resume()");
            // 恢复后重新确认持续性能设置（resume 后窗口可能重建）
            setupSustainedPerformance();
            // 恢复后重新设置高刷新率（窗口可能重建导致之前设置失效）
            setupHighRefreshRate();
        }
    }

    /**
     * 设置持续高性能模式。
     * 解决问题：Android 节能调度器检测到无触摸事件后会降低 CPU/GPU 调度优先级，
     * 导致帧率从目标值（如 60fps）降至 ~40fps。
     *
     * 策略（四层保障）：
     * 1. FLAG_KEEP_SCREEN_ON: 让系统认为窗口始终活跃，避免触发节能调度
     * 2. ContinuousRendering: 确保 LibGDX 渲染循环持续运行，不因无 UI 变化而跳帧
     * 3. 周期性模拟触摸保活: 每 1 秒派发一个完整的 ACTION_DOWN+ACTION_UP 触摸序列，
     *    坐标在屏幕中心附近微小随机偏移，重置 Android DVFS 的用户空闲计时器
     * 4. FLAG_HARDWARE_ACCELERATED: 确保硬件加速始终开启，GPU 不被挂起
     *
     * 注意：不使用 SustainedPerformanceMode —— 该模式会主动将 CPU/GPU 限频到
     * "可持续"水平（防止过热），在 60Hz 设备上反而导致帧率从 60 降到 ~40。
     */
    private void setupSustainedPerformance() {
        try {
            Window window = getWindow();
            if (window != null) {
                // 明确关闭 SustainedPerformanceMode（它会限制 CPU/GPU 最大频率）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    window.setSustainedPerformanceMode(false);
                    Log.i(TAG, "SustainedPerformanceMode explicitly DISABLED (it caps CPU/GPU clocks)");
                }

                // 1. FLAG_KEEP_SCREEN_ON — 配合 useWakelock=true 双重保障
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                Log.i(TAG, "FLAG_KEEP_SCREEN_ON added");

                // 2. FLAG_HARDWARE_ACCELERATED — 确保 GPU 不被挂起
                window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
                Log.i(TAG, "FLAG_HARDWARE_ACCELERATED added");

                // 3. 对于 Android 11+，设置 preferMinimalPostProcessing 减少后处理延迟
                if (Build.VERSION.SDK_INT >= 30) {
                    try {
                        window.setPreferMinimalPostProcessing(true);
                        Log.i(TAG, "setPreferMinimalPostProcessing(true) for reduced pipeline latency");
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to set preferMinimalPostProcessing", e);
                    }
                }
            } else {
                Log.w(TAG, "Window is null, cannot set performance flags");
            }

            // 4. ContinuousRendering — Gdx.graphics 在 onCreate 期间可能尚未初始化，
            //    因此在 onResume 中重试是必要的
            if (Gdx.graphics != null) {
                Gdx.graphics.setContinuousRendering(true);
                Log.i(TAG, "ContinuousRendering set to true");
            } else {
                Log.w(TAG, "Gdx.graphics not yet initialized, ContinuousRendering will be set in onResume");
            }

            // 5. 启动增强型周期性保活（模拟真实触摸序列防止 DVFS 空闲降频）
            startKeepAlive();

            // 6. 设置当前进程为前台优先级，防止被系统降低调度优先级
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND);
            Log.i(TAG, "Main thread priority set to FOREGROUND");

            // 7. 提高音频线程优先级，减少音频抖动
            if (Gdx.audio != null) {
                try {
                    // 确保音频线程有足够高的优先级
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
                    Log.i(TAG, "Audio thread priority set to AUDIO");
                } catch (Exception e) {
                    Log.w(TAG, "Failed to set audio thread priority", e);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to setup sustained performance", t);
        }
    }

    /**
     * 启动增强型周期性用户活跃度保活。
     * 每 1 秒向 DecorView 派发一个完整的 ACTION_DOWN + ACTION_UP 触摸序列，
     * 触摸坐标在屏幕中心附近微小随机偏移，
     * 让 Android 电源管理器认为用户仍在交互，防止 DVFS 降频。
     */
    private void startKeepAlive() {
        if (keepAliveHandler == null) {
            keepAliveHandler = new Handler(Looper.getMainLooper());
        }
        keepAliveHandler.removeCallbacks(keepAliveRunnable);
        keepAliveHandler.postDelayed(keepAliveRunnable, 1000);
        // Log.i(TAG, "Enhanced KeepAlive started (simulated touch sequence every 1s to prevent DVFS idle throttle)");
    }

    /**
     * 停止周期性保活。
     */
    private void stopKeepAlive() {
        if (keepAliveHandler != null) {
            keepAliveHandler.removeCallbacks(keepAliveRunnable);
            // Log.i(TAG, "KeepAlive stopped");
        }
    }

    /**
     * 检查并请求存储权限（兼容 Android 16）
     * @return true 如果权限已授予
     */
    private boolean checkAndRequestStoragePermissions() {
        // Android 11+ (API 30+): 需要 MANAGE_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                Log.w(TAG, "MANAGE_EXTERNAL_STORAGE not granted, requesting...");
                android.content.Intent intent = new android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, 100);
                return false;
            }
            Log.i(TAG, "MANAGE_EXTERNAL_STORAGE permission granted");
            return true;
        }

        // Android 6.0 - 10 (API 23-29): 需要 READ/WRITE_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "READ/WRITE_EXTERNAL_STORAGE not granted, requesting...");
                requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, 1);
                return false;
            }
            Log.i(TAG, "READ/WRITE_EXTERNAL_STORAGE permissions granted");
            return true;
        }

        // Android 5.x 及以下：不需要运行时权限
        Log.i(TAG, "Android version < 6.0, no runtime permissions needed");
        return true;
    }

    @Override
    protected void onPause() {
        Log.i(TAG, "onPause: Pausing Oboe audio stream");
        stopKeepAlive();
        super.onPause();
    }

    private void copyAssetsToFilesDir(String assetPath, File targetDir) {
        AssetManager assetManager = getAssets();
        try {
            String[] assets = assetManager.list(assetPath);
            if (assets == null) {
                Log.w(TAG, "assetPath not found: " + assetPath);
                return;
            }
            if (assets.length == 0) {
                // This is a file, not a directory, should have been handled by parent
                Log.d(TAG, "Empty directory listing for: " + assetPath);
                return;
            }

            if (!targetDir.exists()) targetDir.mkdirs();

            for (String asset : assets) {
                String subAssetPath = (assetPath.isEmpty()) ? asset : assetPath + "/" + asset;
                String[] subAssets = assetManager.list(subAssetPath);

                File targetFile = new File(targetDir, asset);
                if (subAssets != null && subAssets.length > 0) {
                    // 递归目录
                    copyAssetsToFilesDir(subAssetPath, targetFile);
                } else {
                    // 复制文件
                    copyFileAsset(subAssetPath, targetFile);
                }
            }
            Log.i(TAG, "Processed directory: " + assetPath + " -> " + targetDir.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy assets: " + assetPath, e);
        }
    }

    private void copyFileAsset(String assetPath, File targetFile) {
        if (targetFile.exists()) {
            Log.d(TAG, "Already exists: " + targetFile.getAbsolutePath());
            return;
        }

        try (InputStream in = getAssets().open(assetPath);
             OutputStream out = new FileOutputStream(targetFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            Log.d(TAG, "Copied: " + assetPath + " -> " + targetFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy file: " + assetPath + " -> " + targetFile.getAbsolutePath(), e);
        }
    }

    /**
     * 从 assets/BMS 目录复制默认谱面到 BMS 根目录
     * 仅首次运行时复制（已存在的文件/文件夹会跳过）
     * @param bmsRoot BMS 根目录路径
     */
    private void copyBmsAssets(String bmsRoot) {
        File bmsDir = new File(bmsRoot);

        // 检查 BMS 目录是否为空（首次运行）
        File[] existingFiles = bmsDir.listFiles();
        boolean isFirstRun = (existingFiles == null || existingFiles.length == 0);

        if (!isFirstRun) {
            Log.i(TAG, "BMS directory already has files, skipping assets copy");
            return;
        }

        Log.i(TAG, "First run detected, copying BMS assets to: " + bmsRoot);

        try {
            AssetManager assetManager = getAssets();
            String[] bmsAssets = assetManager.list("BMS");

            if (bmsAssets == null || bmsAssets.length == 0) {
                Log.w(TAG, "No BMS assets found in assets/BMS directory");
                return;
            }

            Log.i(TAG, "Found " + bmsAssets.length + " items in assets/BMS");

            // 递归复制 BMS 目录
            copyAssetsToFilesDir("BMS", bmsDir);

            Log.i(TAG, "BMS assets copy completed");
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy BMS assets", e);
        }
    }

    /**
     * 从 assets/inochi_ogg 目录复制 inochi 系列谱面到 BMS 根目录
     * inochi_ogg 目录包含 .bms 谱面文件和对应的 .ogg 音频文件
     * @param bmsRoot BMS 根目录路径
     */
    private void copyInochiOggAssets(String bmsRoot) {
        File inochiDir = new File(new File(bmsRoot), "inochi_ogg");

        Log.i(TAG, "Copying inochi_ogg assets to: " + bmsRoot);
        try {
            AssetManager assetManager = getAssets();
            String[] inochiAssets = assetManager.list("inochi_ogg");

            if (inochiAssets == null || inochiAssets.length == 0) {
                Log.w(TAG, "No assets found in assets/inochi_ogg directory");
                return;
            }

            Log.i(TAG, "Found " + inochiAssets.length + " items in assets/inochi_ogg");

            // 递归复制 inochi_ogg 目录到 BMS 目录
            copyAssetsToFilesDir("inochi_ogg", inochiDir);

            Log.i(TAG, "inochi_ogg assets copy completed");
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy inochi_ogg assets", e);
        }
    }

    /**
     * 在主线程（单线程阶段）预热 SQLite 共享连接池。
     * DatabaseUtils 现已对每个数据库 URL 复用单一长生命周期连接，
     * 此方法提前创建共享连接并设置 WAL + busy_timeout，
     * 避免后续游戏线程首次访问时产生延迟。
     */
    private void preInitWal(File filesDir, String playerDirPath) {
        String[] dbNames = {"score.db", "songdata.db"};
        for (String dbName : dbNames) {
            File dbFile = new File(filesDir, dbName);
            String path = dbFile.getAbsolutePath();
            try {
                javax.sql.DataSource ds = bms.player.beatoraja.DatabaseUtils.getDataSource(path);
                java.sql.Connection c = ds.getConnection();
                c.close();
                Log.i(TAG, "WAL pre-initialized for: " + path);
            } catch (Throwable t) {
                Log.w(TAG, "WAL pre-init skipped for " + path + ": " + t.getMessage());
            }
        }
        // 预初始化玩家分数数据库
        File playerScoreDb = new File(playerDirPath, "score.db");
        String playerScorePath = playerScoreDb.getAbsolutePath();
        try {
            javax.sql.DataSource ds = bms.player.beatoraja.DatabaseUtils.getDataSource(playerScorePath);
            java.sql.Connection c = ds.getConnection();
            c.close();
            Log.i(TAG, "WAL pre-initialized for player score: " + playerScorePath);
        } catch (Throwable t) {
            Log.w(TAG, "WAL pre-init skipped for player score " + playerScorePath + ": " + t.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                Log.i(TAG, "Storage permissions granted");
                if (pendingInitialization) {
                    Log.i(TAG, "Resuming initialization after permission grant");
                    pendingInitialization = false;
                    recreate();
                }
            } else {
                Log.e(TAG, "Storage permissions DENIED - app cannot function without them");
            }
        }
    }

    /**
     * 获取兼容多版本 Android 的 Download 目录路径
     */
    private String getDownloadPath() {
        try {
            // Android 10 (API 29) 以下使用传统方式
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                String path = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();
                Log.i(TAG, "Download path (API < 29): " + path);
                return path;
            } else {
                // Android 10+ 使用外部存储的公共 Download 目录
                // 由于我们已经请求了 MANAGE_EXTERNAL_STORAGE 权限，可以直接访问
                String path = "/storage/emulated/0/Download";
                Log.i(TAG, "Download path (API >= 29): " + path);

                // 验证路径是否可访问
                File downloadDir = new File(path);
                if (!downloadDir.exists()) {
                    Log.w(TAG, "Download directory does not exist: " + path);
                    // 尝试创建
                    if (downloadDir.mkdirs()) {
                        Log.i(TAG, "Created Download directory: " + path);
                    } else {
                        Log.e(TAG, "Failed to create Download directory: " + path);
                    }
                } else if (!downloadDir.canRead()) {
                    Log.e(TAG, "Download directory not readable: " + path);
                } else {
                    Log.i(TAG, "Download directory verified: exists and readable");
                }

                return path;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get Download path, using fallback", e);
            return "/storage/emulated/0/Download";
        }
    }

    private void setupHighRefreshRate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.view.Window window = getWindow();
                if (window == null) return;

                android.view.Display display = window.getWindowManager().getDefaultDisplay();
                if (display == null) return;

                // 第一步：获取当前实际刷新率（不是API报告的，而是物理显示的）
                float currentRefreshRate = display.getRefreshRate();
                Log.i(TAG, "Current display refresh rate: " + currentRefreshRate + "Hz");

                android.view.Display.Mode[] modes = display.getSupportedModes();
                if (modes == null || modes.length == 0) return;

                // 打印所有支持的模式帮助调试
                StringBuilder modeLog = new StringBuilder("Supported display modes: ");
                for (int i = 0; i < modes.length; i++) {
                    android.view.Display.Mode m = modes[i];
                    modeLog.append("[").append(i).append("] ")
                        .append(m.getPhysicalWidth()).append("x").append(m.getPhysicalHeight())
                        .append("@").append(m.getRefreshRate()).append("Hz ");
                }
                Log.i(TAG, modeLog.toString());

                android.view.Display.Mode bestMode = null;
                float highestRefreshRate = 0;

                for (android.view.Display.Mode mode : modes) {
                    if (mode == null) continue;
                    float rr = mode.getRefreshRate();
                    // 寻找最高刷新率模式，若刷新率相同则选分辨率更高的
                    if (rr > highestRefreshRate + 0.1f) {
                        highestRefreshRate = rr;
                        bestMode = mode;
                    } else if (Math.abs(rr - highestRefreshRate) < 0.1f) {
                        if (bestMode == null || mode.getPhysicalWidth() > bestMode.getPhysicalWidth()) {
                            bestMode = mode;
                        }
                    }
                }

                if (bestMode != null) {
                    android.view.WindowManager.LayoutParams params = window.getAttributes();
                    params.preferredDisplayModeId = bestMode.getModeId();
                    window.setAttributes(params);
                    Log.i(TAG, "Selected mode: " + bestMode.getPhysicalWidth() + "x" + bestMode.getPhysicalHeight() + " @ " + highestRefreshRate + "Hz");
                }

                // API 30+ 开启最小后处理以降低延迟
                if (Build.VERSION.SDK_INT >= 30) {
                    window.setPreferMinimalPostProcessing(true);
                    Log.i(TAG, "PreferMinimalPostProcessing enabled for lower latency");
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to set high refresh rate", t);
        }
    }

    /**
     * 检测并打印已连接的控制器信息
     */
    private void detectAndLogControllers() {
        try {
            Gdx.app.postRunnable(() -> {
                try {
                    bms.player.beatoraja.input.XboxControllerHelper.printConnectedControllers();

                    if (bms.player.beatoraja.input.XboxControllerHelper.hasXboxController()) {
                        Log.i(TAG, "✓ XBOX controller detected and ready!");
                    } else {
                        Log.i(TAG, "No XBOX controller detected. Connect one and restart the app.");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Controller detection failed: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "Failed to schedule controller detection: " + e.getMessage());
        }
    }

    /**
     * Android 8-12 (API 26-32) onBackPressed 支持。
     * 全面屏手势返回或传统返回键都会触发此方法。
     */
    @Override
    public void onBackPressed() {
        Log.d(TAG, "onBackPressed triggered (edge swipe or hardware back key)");
        setAndroidBackPressedFlag();
        // 不调用 super.onBackPressed()，阻止默认返回行为（如退出应用）
    }

    /**
     * 统一设置 Android 返回键映射为 Escape 的方法
     * 改用 simulateKeyPress(Keys.ESCAPE) 直接注入按键，
     * 利用新的 deadline 机制（50ms）确保渲染线程可靠读取。
     */
    private void setAndroidBackPressedFlag() {
        try {
            com.badlogic.gdx.Application app = com.badlogic.gdx.Gdx.app;
            if (app != null && com.badlogic.gdx.Gdx.input != null) {
                com.starxh.beatoraja.BeatorajaGame game = (com.starxh.beatoraja.BeatorajaGame) app.getApplicationListener();
                if (game != null) {
                    bms.player.beatoraja.MainController main = game.getMainController();
                    if (main != null) {
                        bms.player.beatoraja.input.BMSPlayerInputProcessor inputProcessor = main.getInputProcessor();
                        if (inputProcessor != null) {
                            bms.player.beatoraja.input.KeyBoardInputProcesseor keyboardInput = inputProcessor.getKeyBoardInputProcesseor();
                            if (keyboardInput != null) {
                                keyboardInput.simulateKeyPress(Keys.ESCAPE);
                                Log.d(TAG, "Android Back key -> simulateKeyPress(ESCAPE)");
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to simulate ESCAPE via back key: " + e.getMessage());
        }
    }

    /**
     * 重写 onKeyDown 方法，将 Android 返回键映射为 Escape 键
     * 这样在 Android 设备上，无论是使用触摸屏返回键还是物理键盘的 Escape 键，
     * 都能触发相同的返回行为（退出当前界面、返回上级菜单等）
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        lastUserTouchTime = SystemClock.uptimeMillis();
        // 当按下 Android 返回键时，将其映射为 Escape 键
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            Log.d(TAG, "Android BACK key pressed - triggering ESCAPE behavior");
            setAndroidBackPressedFlag();
            // 返回 true 表示我们已处理此事件，阻止默认的返回键行为（如退出应用）
            return true;
        }

        // 如果正在进行文本输入，允许系统处理按键事件（如软键盘输入）
        if (isTextInputActive) {
            return super.onKeyDown(keyCode, event);
        }

        // 其他情况拦截按键事件，让 LibGDX 处理，防止触发系统默认行为（如在某些设备上弹出搜索框）
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // 如果是保活机制触发的模拟触摸，直接在此处消费并返回
        // 系统仍会记录此次“用户交互”以保持高性能模式，但 LibGDX View 不会收到该事件，
        // 从而彻底解决了模拟触摸导致的坐标跳变（指针重新定位）问题。
        if (isSimulatingTouch) {
            return true;
        }

        // 记录用户真实的交互状态
        lastUserTouchTime = SystemClock.uptimeMillis();
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            isUserTouching = true;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            // 只有当所有手指都离开屏幕时，才认为触摸结束
            if (ev.getPointerCount() <= 1) {
                isUserTouching = false;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        // 拦截并消耗保活模拟事件
        if (isSimulatingTouch) {
            return true;
        }
        lastUserTouchTime = SystemClock.uptimeMillis();
        return super.dispatchGenericMotionEvent(ev);
    }

    /**
     * 重写 dispatchKeyEvent 方法，在按键时确保软键盘隐藏
     * 但如果有文本输入框活跃，则允许软键盘显示
     * 同时处理软键盘的Enter键事件
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        lastUserTouchTime = SystemClock.uptimeMillis();
        // 当文本输入活跃时，捕获Enter键并模拟按键事件
        if (isTextInputActive && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                Log.d(TAG, "Enter key pressed in text input mode - simulating ENTER key");
                simulateEnterKeyPress();
                return true; // 消耗此事件
            }
        }

        // 移除了在 ACTION_DOWN 时强制隐藏软键盘的逻辑，
        // 避免在 MIUI 等系统上拦截合法的软键盘弹出请求。
        // 现在仅依靠 onWindowFocusChanged 进行后台静默隐藏。
        return super.dispatchKeyEvent(event);
    }

    /**
     * 模拟Enter键按下事件，传递给LibGDX的InputProcessor
     */
    private void simulateEnterKeyPress() {
        try {
            com.badlogic.gdx.Application app = com.badlogic.gdx.Gdx.app;
            if (app != null && com.badlogic.gdx.Gdx.input != null) {
                com.starxh.beatoraja.BeatorajaGame game = (com.starxh.beatoraja.BeatorajaGame) app.getApplicationListener();
                if (game != null) {
                    bms.player.beatoraja.MainController main = game.getMainController();
                    if (main != null) {
                        bms.player.beatoraja.input.BMSPlayerInputProcessor inputProcessor = main.getInputProcessor();
                        if (inputProcessor != null) {
                            bms.player.beatoraja.input.KeyBoardInputProcesseor keyboardInput = inputProcessor.getKeyBoardInputProcesseor();
                            if (keyboardInput != null) {
                                // 使用simulateKeyPress来确保渲染线程能可靠读取
                                keyboardInput.simulateKeyPress(com.badlogic.gdx.Input.Keys.ENTER);
                                Log.d(TAG, "ENTER key simulated successfully");
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to simulate ENTER key: " + e.getMessage());
        }
    }

    /**
     * 设置文本输入状态。
     * 当搜索框等文本输入组件活跃时调用此方法，允许软键盘显示。
     *
     * @param active true 表示文本输入框活跃，false 表示不活跃
     */
    public void setTextInputActive(boolean active) {
        isTextInputActive = active;
        Log.i(TAG, "setTextInputActive: " + active + ", thread: " + Thread.currentThread().getName());

        if (active) {
            // 激活文本输入时，立即停止保活触摸
            stopKeepAlive();

            // 切换 SoftInputMode 允许显示键盘
            runOnUiThread(() -> {
                Log.d(TAG, "Switching to SOFT_INPUT_ADJUST_NOTHING");
                getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
            });
        } else {
            // 文本输入结束，恢复保活
            startKeepAlive();

            if (inputMethodManager != null) {
                inputMethodManager.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);
            }
            // 恢复隐藏键盘模式
            runOnUiThread(() -> {
                Log.d(TAG, "Switching to SOFT_INPUT_STATE_ALWAYS_HIDDEN");
                getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN |
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                );
            });
        }
    }
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        lastUserTouchTime = SystemClock.uptimeMillis();
        // 当释放 Android 返回键时，不需要做任何事
        // 标志会在下一帧被poll()方法自动清除
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            Log.d(TAG, "Android BACK key released - flag will be auto-cleared by poll()");
            // 返回 true 表示我们已处理此事件
            return true;
        }
        // 其他按键使用默认处理
        return super.onKeyUp(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        stopKeepAlive();
        // 注销 OnBackInvokedCallback（API 33+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backInvokedCallback != null) {
            try {
                getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    (android.window.OnBackInvokedCallback) backInvokedCallback);
            } catch (Exception e) {
                Log.w(TAG, "Failed to unregister OnBackInvokedCallback", e);
            }
        }
        super.onDestroy();
    }

    /**
     * Open a URL in the browser from the game
     */
    public void openUrl(String url) {
        openUrl(url, null);
    }

    /**
     * Open a URL in the browser from the game with optional message
     */
    public void openUrl(String url, String message) {
        try {
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            intent.setData(android.net.Uri.parse(url));
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);

            if (message != null && !message.isEmpty()) {
                runOnUiThread(() -> {
                    try {
                        new android.app.AlertDialog.Builder(this)
                            .setTitle("Download")
                            .setMessage(message)
                            .setPositiveButton("Open", (dialog, which) -> {
                                startActivity(intent);
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to show dialog", e);
                    }
                });
            } else {
                startActivity(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to open URL: " + url, e);
        }
    }
}
