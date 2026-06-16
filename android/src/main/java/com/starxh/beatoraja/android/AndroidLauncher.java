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
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.text.InputType;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.badlogic.gdx.backends.android.AndroidAudio;
import com.badlogic.gdx.backends.android.AndroidGraphics;
import com.badlogic.gdx.backends.android.DefaultAndroidInput;
import com.badlogic.gdx.backends.android.surfaceview.FillResolutionStrategy;
import com.badlogic.gdx.backends.android.surfaceview.GLSurfaceView20;
import com.badlogic.gdx.backends.android.surfaceview.ResolutionStrategy;
import com.badlogic.gdx.Input.Keys;
import com.starxh.beatoraja.BeatorajaGame;
import barsoosayque.libgdxoboe.OboeAudio;

import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.view.WindowInsets;
import org.json.JSONObject;

import bms.player.beatoraja.Config;
import bms.player.beatoraja.MainState;
import bms.player.beatoraja.MainStateListener;
import bms.player.beatoraja.PlayerConfig;
import bms.player.beatoraja.BMSPlayerMode;

public class AndroidLauncher extends AndroidApplication {

	/** 硬件最大刷新率，MainController 通过反射读取 */
	public static float maxRefreshRate = 60f;
    private static final String TAG = "AndroidLauncher";
    private static AndroidLauncher instance;
    private InputMethodManager inputMethodManager;
    private volatile boolean isTextInputActive = false;
    private OboeAudio oboeAudio;
    private String mLanguage = "en";

    @Override
    public AndroidAudio createAudio(Context context, AndroidApplicationConfiguration config) {
        try {
            oboeAudio = new OboeAudio(context.getAssets(), 44100);
            Log.i(TAG, "OboeAudio initialized successfully (FFT spectrum enabled)");

            // Set up spectrum provider adapter
            AudioSpectrumAdapter adapter = new AudioSpectrumAdapter(oboeAudio);
            adapter.setAsGlobalProvider();

            return oboeAudio;
        } catch (Throwable t) {
            Log.w(TAG, "OboeAudio initialization failed, falling back to default AndroidAudio: " + t.getMessage());
            return super.createAudio(context, config);
        }
    }

    /**
     * 覆写 createGraphics 以修复 libGDX GLSurfaceView20 的 password flag 问题。
     *
     * libGDX 的 GLSurfaceView20.onCreateInputConnection() 会设置
     * InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD 标志，
     * 导致 Android 系统将窗口视为"安全"窗口，从而阻止屏幕录制和截图。
     * 参考: https://github.com/libgdx/libgdx/issues/7754
     *
     * 修复方法：创建自定义 GLSurfaceView20，在 onCreateInputConnection 中
     * 省略 TYPE_TEXT_VARIATION_VISIBLE_PASSWORD 标志。
     */
    @Override
    protected AndroidGraphics createGraphics(AndroidApplicationConfiguration config) {
        return new AndroidGraphics(this, config,
                config.resolutionStrategy == null ? new FillResolutionStrategy() : config.resolutionStrategy) {
            @Override
            protected GLSurfaceView20 createGLSurfaceView(
                    com.badlogic.gdx.backends.android.AndroidApplicationBase application,
                    final ResolutionStrategy resolutionStrategy) {
                if (!checkGL20()) throw new com.badlogic.gdx.utils.GdxRuntimeException("libGDX requires OpenGL ES 2.0");

                android.opengl.GLSurfaceView.EGLConfigChooser configChooser = getEglConfigChooser();
                GLSurfaceView20 view = new GLSurfaceView20(application.getContext(), resolutionStrategy, config.useGL30 ? 3 : 2) {
                    @Override
                    public boolean onCheckIsTextEditor() {
                        // 防止物理键盘按键时 Android 系统自动弹出 IME。
                        // libGDX 的 DefaultAndroidInput 在 setOnscreenKeyboardVisible(true) 时
                        // 会把 GLSurfaceView 设为 focusable，加上 onCreateInputConnection 的存在，
                        // 导致系统认为此 View 是文本编辑器而自动显示软键盘。
                        // 返回 false 后系统不再为 GLSurfaceView 自动弹 IME；
                        // 搜索框使用独立的 EditText（AndroidOnscreenKeyboard），不受影响。
                        return false;
                    }

                    @Override
                    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
                        // 非文本输入态不提供 InputConnection，防止 IME 在物理键盘输入时自动弹出。
                        // 搜狗等第三方输入法即使 onCheckIsTextEditor() 返回 false，
                        // 也会在 onStartInputView 回调中主动显示输入界面。
                        if (!isTextInputActive) {
                            return null;
                        }
                        if (outAttrs != null) {
                            outAttrs.imeOptions = outAttrs.imeOptions
                                    | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                                    | EditorInfo.IME_FLAG_NO_FULLSCREEN;
                            if (onscreenKeyboardType == com.badlogic.gdx.Input.OnscreenKeyboardType.Default) {
                                outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
                            } else {
                                outAttrs.inputType = DefaultAndroidInput.getAndroidInputType(onscreenKeyboardType, true);
                            }
                        }
                        return super.onCreateInputConnection(null);
                    }
                };

                if (configChooser != null)
                    view.setEGLConfigChooser(configChooser);
                else
                    view.setEGLConfigChooser(config.r, config.g, config.b, config.a, config.depth, config.stencil);

                view.setRenderer(this);
                return view;
            }
        };
    }

    private void readConfigForLanguage() {
        try {
            File configFile = new File(getExternalFilesDir(null), "config_sys.json");
            if (configFile.exists()) {
                StringBuilder content = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
                    String line;
                    while ((line = reader.readLine()) != null) content.append(line);
                }
                JSONObject json = new JSONObject(content.toString());
                mLanguage = json.optString("language", "en");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to read language config", e);
        }
    }

    private void applyLanguage(String lang) {
        Locale locale = new Locale(lang);
        if (lang.equals("zh")) locale = Locale.SIMPLIFIED_CHINESE;
        Locale.setDefault(locale);
        Resources resources = getResources();
        Configuration config = resources.getConfiguration();
        config.setLocale(locale);
        resources.updateConfiguration(config, resources.getDisplayMetrics());
    }

    private Object backInvokedCallback;
    private Handler keepAliveHandler;
    private volatile long lastUserTouchTime = 0;
    private volatile boolean isUserTouching = false;
    private boolean isWaitingForPermissionResult = false;
    private volatile boolean isPlayStateActive = false;
    private final MainStateListener systemGestureExclusionListener = (state, status) -> {
        boolean isPlay = state instanceof bms.player.beatoraja.play.BMSPlayer;
        if (isPlay != isPlayStateActive) {
            isPlayStateActive = isPlay;
            runOnUiThread(() -> {
                applySystemGestureExclusion(isPlay);
                if (!isTextInputActive) suppressImeForGameInput();
            });
        }
    };

    private final Runnable keepAliveRunnable = new Runnable() {
        @Override
        public void run() {
            if (keepAliveHandler != null) keepAliveHandler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        readConfigForLanguage();
        applyLanguage(mLanguage);
        super.onCreate(savedInstanceState);
        instance = this;

        // 检测设备架构并设置系统属性，供core模块使用
        boolean is64Bit = false;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            is64Bit = android.os.Process.is64Bit();
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            is64Bit = android.os.Build.SUPPORTED_64_BIT_ABIS != null && android.os.Build.SUPPORTED_64_BIT_ABIS.length > 0;
        }

        if (!is64Bit) {
            System.setProperty("beatoraja.32bit", "true");
            Log.i(TAG, "Detected 32-bit device, using 30FPS limit for MusicSelect");
        } else {
            Log.i(TAG, "Detected 64-bit device, unlimited FPS enabled");
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.window.OnBackInvokedCallback callback = () -> {
                setAndroidBackPressedFlag();
            };
            backInvokedCallback = callback;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
        }

        if (!checkAndRequestStoragePermissions()) {
            pendingInitialization = true;
            initialize(new ApplicationAdapter() {}, new AndroidApplicationConfiguration());
            return;
        }

        AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        config.useImmersiveMode = true;
        config.useWakelock = true;

        // 使用默认的 FillResolutionStrategy（全屏填充）
        config.resolutionStrategy = new FillResolutionStrategy();

        File filesDir = getExternalFilesDir(null);
        String root = filesDir.getAbsolutePath();
        System.setProperty("beatoraja.root", root);

        // 首次启动时创建必要的目录
        createDefaultDirectories();

        // 检测并解压 skin zip 到外部存储
        ensureExternalSkinZip(filesDir);

        // 检测并解压 songs zip 到 Download 目录
        ensureExternalSongZip();

        Config.updateConfigPath();
        PlayerConfig.updateConfigPath();

        String playerName = "player1";
        String[] bmsrootFromConfig = new String[0];
        try {
            File configFile = new File(filesDir, "config_sys.json");
            if (configFile.exists()) {
                FileInputStream fis = new FileInputStream(configFile);
                byte[] bytes = new byte[(int) configFile.length()];
                fis.read(bytes);
                fis.close();
                String content = new String(bytes, "UTF-8");
                int nameIndex = content.indexOf("\"playername\"");
                if (nameIndex >= 0) {
                    int colonIndex = content.indexOf(":", nameIndex);
                    if (colonIndex >= 0) {
                        int start = colonIndex + 1;
                        while (start < content.length() && (content.charAt(start) == ' ' || content.charAt(start) == '"')) start++;
                        int end = start;
                        while (end < content.length() && content.charAt(end) != '"' && content.charAt(end) != ',' && content.charAt(end) != '}') end++;
                        if (end > start) {
                            playerName = content.substring(start, end).trim().replace("\\/", "/");
                        }
                    }
                }

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
                                pos++; int start = pos;
                                while (pos < arrayContent.length() && arrayContent.charAt(pos) != '"') pos++;
                                if (pos < arrayContent.length()) {
                                    String path = arrayContent.substring(start, pos).trim().replace("\\/", "/");
                                    while (path.contains("//")) path = path.replace("//", "/");
                                    paths.add(path);
                                    pos++;
                                }
                            } else {
                                while (pos < arrayContent.length() && arrayContent.charAt(pos) != ',' && arrayContent.charAt(pos) != ']') pos++;
                            }
                        }
                        if (!paths.isEmpty()) bmsrootFromConfig = paths.toArray(new String[0]);
                    }
                }
            }
        } catch (Exception e) { Log.w(TAG, "Read config fail: " + e.getMessage()); }

        // BMS 歌曲目录：Download/beatoraja/songs
        String defaultBmsRoot = new File(new File(getDownloadPath(), BEATORAJA_BASE), SONGS_FOLDER).getAbsolutePath();
        String[] allBmsRoots = (bmsrootFromConfig.length > 0) ? bmsrootFromConfig : new String[]{defaultBmsRoot};

        try {
            String defaultDbPath = filesDir.getAbsolutePath() + "/songdata.db";
            bms.player.beatoraja.song.AndroidSQLiteSongDatabaseAccessor androidAccessor =
                    new bms.player.beatoraja.song.AndroidSQLiteSongDatabaseAccessor(this, defaultDbPath, allBmsRoots);
            bms.player.beatoraja.MainLoader.setSongDatabaseAccessor(androidAccessor);
        } catch (Throwable t) { Log.e(TAG, "DB init fail", t); return; }

        setupHighRefreshRate();
        inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);

        // VSync 相位同步：使用 Choreographer 定时向 MainController 发送 VSync 时间戳，
        // 从而将应用层的绝对时间帧率控制与系统 VSync 锁定。
        android.view.Choreographer.getInstance().postFrameCallback(new android.view.Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                bms.player.beatoraja.MainController.setLastVsyncTimeNanos(frameTimeNanos);
                android.view.Choreographer.getInstance().postFrameCallback(this);
            }
        });

        // Set up unified score DB factory (Android native SQLite, not JDBC)
        bms.player.beatoraja.ScoreDatabaseAccessor.setFactory(
            path -> new bms.player.beatoraja.score.AndroidScoreDatabaseAccessor(AndroidLauncher.this, path));

        initialize(new BeatorajaGame(null, null, null, BMSPlayerMode.AUTOPLAY, true), config);

        // 反射清空 libGDX 内部在 initialize() 里注册的 audio LifecycleListener
        // (它在 onPause 时调 audio.pause() → Oboe requestStop stream,锁屏会让所有状态没声音)。
        // 移除后,Oboe stream 的 pause/resume 完全由 AndroidLauncher.onPause/onResume 控制,
        // 以便在 MusicPlayer 状态时不让流停,其他状态仍可走"锁屏音频停"的默认行为。
        try {
            com.badlogic.gdx.utils.SnapshotArray<com.badlogic.gdx.LifecycleListener> listeners = getLifecycleListeners();
            if (listeners != null) listeners.clear();
        } catch (Throwable t) {
            Log.w(TAG, "Failed to clear libGDX audio lifecycle listeners", t);
        }

        Gdx.input.setCatchKey(Keys.BACK, true);

        // 修复屏幕录像/overlay不可见问题：
        // libGDX 的 initialize() 会设置 FLAG_FULLSCREEN 窗口标志，
        // 该标志在某些 Android ROM（特别是国产ROM如MIUI/ColorOS等）上
        // 会阻止 TYPE_APPLICATION_OVERLAY 类型的悬浮窗（屏幕录像控件等）
        // 显示在游戏窗口上方。
        // 清除 FLAG_FULLSCREEN 后，沉浸式模式中的 SYSTEM_UI_FLAG_FULLSCREEN
        // 仍然会隐藏状态栏，保持视觉上的全屏效果。
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        Log.i(TAG, "Cleared FLAG_FULLSCREEN for overlay/screen recording compatibility");

        // 监听MainController状态变化，在PLAY界面启用全面屏边缘手势排除
        try {
            BeatorajaGame game = (BeatorajaGame) Gdx.app.getApplicationListener();
            if (game != null && game.getMainController() != null) {
                game.getMainController().addMainStateListener(systemGestureExclusionListener);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to register systemGestureExclusionListener: " + e.getMessage());
        }

        suppressImeForGameInput();

        // WindowManager 刷新率 + maxRefreshRate 检测
        setupHighRefreshRate();
        // Surface 级别帧速率（延迟等待 libGDX 创建 SurfaceView）
        new Handler(Looper.getMainLooper()).postDelayed(this::setupSurfaceFrameRate, 200);

        setupSustainedPerformance();
    }

    private boolean pendingInitialization = false;

    private static final String BEATORAJA_BASE = "beatoraja";
    private static final String SONGS_FOLDER = "songs";
    private static final String SKINS_FOLDER = "skins";

    /**
     * 首次启动时创建必要的目录
     */
    private void createDefaultDirectories() {
        File downloadBase = new File(getDownloadPath(), BEATORAJA_BASE);
        File songsDir = new File(downloadBase, SONGS_FOLDER);
        File skinsDir = new File(downloadBase, SKINS_FOLDER);

        if (!songsDir.exists()) {
            songsDir.mkdirs();
            Log.i(TAG, "Created songs directory: " + songsDir.getAbsolutePath());
        }

        // 第一次启动时，将 assets 中的 inochi_ogg 复制到默认歌曲目录
        File inochiDir = new File(songsDir, "inochi_ogg");
        if (!inochiDir.exists()) {
            Log.i(TAG, "First run: Copying inochi_ogg from assets to " + inochiDir.getAbsolutePath());
            inochiDir.mkdirs();
            copyAssetFolder(getAssets(), "inochi_ogg", inochiDir);
        }

        if (!skinsDir.exists()) {
            skinsDir.mkdirs();
            Log.i(TAG, "Created skins directory: " + skinsDir.getAbsolutePath());
        }

        // 创建内部存储中的必要目录 (table, songinfo, etc.)
        File filesDir = getExternalFilesDir(null);
        if (filesDir != null) {
            String[] internalDirs = {"table", "songinfo", "player", "irconfig", "sound", "bgm", "font"};
            for (String dir : internalDirs) {
                File d = new File(filesDir, dir);
                if (!d.exists()) {
                    d.mkdirs();
                    Log.i(TAG, "Created internal directory: " + d.getAbsolutePath());
                }
            }

            // 首次启动时将内置字体 VL-Gothic-Regular.ttf 复制到 beatoraja.root/font/，
            // 作为 skin / 系统的字体兜底（确保 resolveFontFileHandle 链的 absolute 分支能找到）
            File fallbackFont = new File(filesDir, "font/VL-Gothic-Regular.ttf");
            if (!fallbackFont.exists()) {
                try {
                    copyAssetFile(getAssets(), "font/VL-Gothic-Regular.ttf", fallbackFont);
                    Log.i(TAG, "Seeded fallback font: " + fallbackFont.getAbsolutePath());
                } catch (Exception e) {
                    Log.w(TAG, "Failed to seed fallback font: " + e.getMessage());
                }
            }
        }
    }

    /**
     * 检测外部 skin 并导入到 skin 目录
     */
    private void ensureExternalSkinZip(File filesDir) {
        File externalSkinsDir = new File(getDownloadPath(), BEATORAJA_BASE + "/" + SKINS_FOLDER);
        File internalSkinDir = new File(filesDir, "skin");

        if (externalSkinsDir.exists() && externalSkinsDir.isDirectory()) {
            boolean hasProcessed = false;

            // 处理 zip 文件
            File[] zipFiles = externalSkinsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".zip"));
            if (zipFiles != null && zipFiles.length > 0) {
                Log.i(TAG, "Found " + zipFiles.length + " skin zip(s) in: " + externalSkinsDir.getAbsolutePath());
                for (File zip : zipFiles) {
                    String skinName = zip.getName().replace(".zip", "");
                    File destFile = new File(internalSkinDir, skinName);

                    if (destFile.exists() && destFile.list() != null && destFile.list().length > 0) {
                        Log.i(TAG, "Skin already exists, skip: " + destFile.getAbsolutePath());
                    } else if (destFile.exists() && !destFile.isDirectory()) {
                        destFile.delete();
                    }

                    if (!destFile.exists() || !destFile.isDirectory()) {
                        File destParent = destFile.getParentFile();
                        if (destParent != null && !destParent.exists()) destParent.mkdirs();
                        File tempZip = new File(internalSkinDir, skinName + ".zip");
                        if (zip.renameTo(tempZip)) {
                            Log.i(TAG, "Moved skin zip to: " + tempZip.getAbsolutePath());
                            if (extractSkinZip(tempZip, internalSkinDir)) {
                                tempZip.delete();
                                Log.i(TAG, "Deleted skin zip after extract: " + zip.getName());
                            }
                        } else {
                            if (extractSkinZip(zip, internalSkinDir)) {
                                zip.delete();
                                Log.i(TAG, "Deleted skin zip after extract: " + zip.getName());
                            }
                        }
                    }
                }
                hasProcessed = true;
            }

            // 处理文件夹
            File[] skinFolders = externalSkinsDir.listFiles(File::isDirectory);
            if (skinFolders != null && skinFolders.length > 0) {
                for (File skinFolder : skinFolders) {
                    File destSkinDir = new File(internalSkinDir, skinFolder.getName());
                    if (destSkinDir.exists()) {
                        Log.i(TAG, "Skin folder already exists, skip: " + destSkinDir.getAbsolutePath());
                    } else {
                        if (skinFolder.renameTo(destSkinDir)) {
                            Log.i(TAG, "Moved skin folder to: " + destSkinDir.getAbsolutePath());
                        } else {
                            try {
                                copyDirectory(skinFolder, destSkinDir);
                                deleteRecursive(skinFolder);
                                Log.i(TAG, "Copied and deleted skin folder: " + skinFolder.getName());
                            } catch (IOException e) {
                                Log.e(TAG, "Failed to copy skin folder: " + skinFolder.getName(), e);
                            }
                        }
                    }
                }
                hasProcessed = true;
            }
            if (hasProcessed) return;
        }
        ensureSkinAssets(filesDir);
    }

    private boolean extractSkinZip(File zipFile, File destDir) {
        destDir.mkdirs();
        String skinName = zipFile.getName().replace(".zip", "");
        File skinExtractDir = new File(destDir, skinName);

        if (skinExtractDir.exists() && skinExtractDir.list() != null && skinExtractDir.list().length > 0) {
            Log.i(TAG, "Skin already extracted, skip: " + skinExtractDir.getAbsolutePath());
            return false;
        }

        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(zipFile)) {
            String prefixToStrip = findCommonZipPrefix(zip, skinName);
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (prefixToStrip != null && entryName.startsWith(prefixToStrip)) {
                    entryName = entryName.substring(prefixToStrip.length());
                }
                if (entryName.isEmpty()) continue;

                File outFile = new File(skinExtractDir, entryName);
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    outFile.getParentFile().mkdirs();
                    try (InputStream is = zip.getInputStream(entry);
                         OutputStream os = new FileOutputStream(outFile)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = is.read(buf)) > 0) os.write(buf, 0, len);
                    }
                }
            }
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to extract skin zip: " + zipFile.getName(), e);
            return false;
        }
    }

    private void ensureExternalSongZip() {
        File songsDir = new File(getDownloadPath(), BEATORAJA_BASE + "/" + SONGS_FOLDER);
        if (!songsDir.exists() || !songsDir.isDirectory()) return;

        File[] zipFiles = songsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".zip"));
        if (zipFiles == null || zipFiles.length == 0) return;

        for (File zip : zipFiles) {
            String songName = zip.getName().replace(".zip", "");
            File extractDir = new File(songsDir, songName);

            // If a non-directory file with the target name exists (e.g., a leftover file),
            // remove it so we can recreate the directory.
            if (extractDir.exists() && !extractDir.isDirectory()) {
                extractDir.delete();
            }

            // Skip only when extraction has actually completed (a non-empty dir exists).
            // If extractDir exists but is empty, retry extraction (in case a previous attempt failed).
            if (extractDir.exists() && extractDir.isDirectory()) {
                String[] children = extractDir.list();
                if (children != null && children.length > 0) {
                    continue;
                }
            }
            if (extractSongZip(zip, extractDir)) {
                zip.delete();
            }
        }
    }

    public static void checkAndExtractSongZips() {
        if (instance != null) {
            instance.ensureExternalSongZip();
        }
    }

    private boolean extractSongZip(File zipFile, File destDir) {
        destDir.mkdirs();
        String zipBaseName = zipFile.getName().replace(".zip", "");
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(zipFile)) {
            String prefixToStrip = findCommonZipPrefix(zip, zipBaseName);
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (prefixToStrip != null && entryName.startsWith(prefixToStrip)) {
                    entryName = entryName.substring(prefixToStrip.length());
                }
                if (entryName.isEmpty()) continue;

                File outFile = new File(destDir, entryName);
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    outFile.getParentFile().mkdirs();
                    try (InputStream is = zip.getInputStream(entry);
                         OutputStream os = new FileOutputStream(outFile)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = is.read(buf)) != -1) os.write(buf, 0, len);
                    }
                }
            }
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to extract song zip: " + zipFile.getName(), e);
            return false;
        }
    }

    private String findCommonZipPrefix(java.util.zip.ZipFile zip, String zipBaseName) {
        java.util.List<String> topLevelFolders = new java.util.ArrayList<>();
        java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            java.util.zip.ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            int slash = name.indexOf('/');
            if (slash > 0) {
                topLevelFolders.add(name.substring(0, slash + 1));
            }
        }
        if (topLevelFolders.isEmpty()) return null;

        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (String folder : topLevelFolders) {
            counts.put(folder, counts.getOrDefault(folder, 0) + 1);
        }
        String mostCommon = null;
        int maxCount = 0;
        for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > maxCount) {
                maxCount = e.getValue();
                mostCommon = e.getKey();
            }
        }
        if (mostCommon != null) {
            String candidate = mostCommon.endsWith("/") ? mostCommon.substring(0, mostCommon.length() - 1) : mostCommon;
            if (candidate.equalsIgnoreCase(zipBaseName)) {
                return mostCommon;
            }
        }
        return null;
    }

    private void copyDirectory(File src, File dest) throws IOException {
        if (!src.exists() || !src.isDirectory()) return;
        dest.mkdirs();
        File[] children = src.listFiles();
        if (children == null) return;

        for (File child : children) {
            File destChild = new File(dest, child.getName());
            if (child.isDirectory()) copyDirectory(child, destChild);
            else {
                try (InputStream is = new FileInputStream(child);
                     OutputStream os = new FileOutputStream(destChild)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = is.read(buf)) > 0) os.write(buf, 0, len);
                }
            }
        }
    }

    private boolean deleteRecursive(File path) {
        if (path == null || !path.exists()) return false;
        if (path.isDirectory()) {
            File[] children = path.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        return path.delete();
    }

    private void ensureSkinAssets(File filesDir) {
        File skinDir = new File(filesDir, "skin");
        if (skinDir.exists()) return;
        skinDir.mkdirs();
        copyAssetFolder(getAssets(), "skin", skinDir);
    }

    private void copyAssetFolder(AssetManager am, String srcPath, File destDir) {
        try {
            String[] assets = am.list(srcPath);
            if (assets == null || assets.length == 0) {
                copyAssetFile(am, srcPath, new File(destDir, new File(srcPath).getName()));
                return;
            }
            for (String asset : assets) {
                String src = srcPath + "/" + asset;
                File destSub = new File(destDir, asset);
                if (!destSub.exists()) {
                    if (asset.contains(".") && !asset.endsWith("/")) copyAssetFile(am, src, destSub);
                    else {
                        destSub.mkdirs();
                        copyAssetFolder(am, src, destSub);
                    }
                }
            }
        } catch (IOException e) { Log.w(TAG, "Asset copy fail: " + srcPath + " - " + e.getMessage()); }
    }

    private void copyAssetFile(AssetManager am, String srcPath, File destFile) {
        try (InputStream is = am.open(srcPath);
             FileOutputStream fos = new FileOutputStream(destFile)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) fos.write(buf, 0, len);
        } catch (IOException e) { Log.w(TAG, "Asset file copy fail: " + srcPath + " - " + e.getMessage()); }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !isTextInputActive) suppressImeForGameInput();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // libGDX 默认的 audio lifecycle listener 已被 onCreate 清空,
        // 这里手动确保 Oboe stream 处于 Started 状态。resume() 在已 Started 时是 noop。
        if (oboeAudio != null) {
            try { oboeAudio.resume(); } catch (Throwable t) { Log.w(TAG, "oboeAudio.resume failed", t); }
        }
        if (!isTextInputActive) {
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
            suppressImeForGameInput();
        }
        if (pendingInitialization) {
            if (checkAndRequestStoragePermissions()) {
                pendingInitialization = false;
                isWaitingForPermissionResult = false;
                recreate();
            }
        } else {
            setupSustainedPerformance();
            setupHighRefreshRate();
            new Handler(Looper.getMainLooper()).postDelayed(this::setupSurfaceFrameRate, 200);
        }
    }

    private void setupSustainedPerformance() {
        try {
            Window window = getWindow();
            if (window != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) window.setSustainedPerformanceMode(true);
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
            }
            if (Gdx.graphics != null) Gdx.graphics.setContinuousRendering(true);
            startKeepAlive();
        } catch (Throwable t) { Log.e(TAG, "Performance setup fail", t); }
    }

    private void startKeepAlive() {
        if (keepAliveHandler == null) keepAliveHandler = new Handler(Looper.getMainLooper());
        keepAliveHandler.removeCallbacks(keepAliveRunnable);
        keepAliveHandler.postDelayed(keepAliveRunnable, 1000);
    }

    private void stopKeepAlive() {
        if (keepAliveHandler != null) keepAliveHandler.removeCallbacks(keepAliveRunnable);
    }

    private boolean checkAndRequestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                android.content.Intent intent = new android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                isWaitingForPermissionResult = true;
                startActivityForResult(intent, 100);
                return false;
            }
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
                return false;
            }
        }
        return true;
    }

    @Override
    protected void onPause() {
        stopKeepAlive();
        // libGDX 默认的 audio lifecycle listener 已被 onCreate 清空,
        // 这里按状态手动控制 Oboe stream:
        //   - MusicPlayer 状态:不调 pause() → 锁屏/切后台 音频流保持 running
        //   - 其他状态(普通 BMSPlayer 等):调 pause() → 维持原本"锁屏音频停"的默认行为
        boolean isMusicPlayer = false;
        try {
            com.badlogic.gdx.ApplicationListener al = Gdx.app != null ? Gdx.app.getApplicationListener() : null;
            if (al instanceof com.starxh.beatoraja.BeatorajaGame) {
                bms.player.beatoraja.MainController mc = ((com.starxh.beatoraja.BeatorajaGame) al).getMainController();
                if (mc != null && mc.getCurrentState() instanceof bms.player.beatoraja.play.MusicPlayer) {
                    isMusicPlayer = true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to read current state in onPause", t);
        }
        if (oboeAudio != null) {
            try {
                if (isMusicPlayer) {
                    // 让流继续;resume() 幂等(已在 Started 时 noop)
                    oboeAudio.resume();
                } else {
                    oboeAudio.pause();
                }
            } catch (Throwable t) { Log.w(TAG, "oboeAudio pause/resume failed", t); }
        }
        super.onPause();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        isWaitingForPermissionResult = false;
        if (requestCode == 100) {
            pendingInitialization = false;
            recreate();
        }
    }

    private String getDownloadPath() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();
        }
        return "/storage/emulated/0/Download";
    }

    /**
     * 检测硬件最大刷新率并通过 WindowManager 请求。
     * 不设置 preferredDisplayModeId，避免与 MIUI DynamicFPS 冲突。
     * Surface 级别的帧速率由 setupSurfaceFrameRate() 单独处理。
     */
    private void setupHighRefreshRate() {
        try {
            Window window = getWindow();
            android.view.Display display = window.getWindowManager().getDefaultDisplay();
            float highestRR = 60f;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // API 23+: 遍历所有支持的显示模式获取最高刷新率
                android.view.Display.Mode[] modes = display.getSupportedModes();
                for (android.view.Display.Mode m : modes) {
                    if (m.getRefreshRate() > highestRR) highestRR = m.getRefreshRate();
                }
            } else {
                // API 21-22: 使用当前模式的刷新率
                highestRR = display.getRefreshRate();
            }

            maxRefreshRate = highestRR;
            Log.i(TAG, "Max refresh rate: " + highestRR + " Hz");

            WindowManager.LayoutParams params = window.getAttributes();
            params.preferredRefreshRate = highestRR;
            window.setAttributes(params);
        } catch (Throwable t) { Log.e(TAG, "setupHighRefreshRate fail", t); }
    }

    /**
     * 使用帧速率 API (Surface.setFrameRate) 在 Surface 级别声明预期帧速率。
     * 这是 Android 11+ 官方推荐的途径，对应文档中的「帧速率 API」：
     * "SurfaceFlinger 会尝试将刷新率设置为该帧速率的倍数"
     */
    private void setupSurfaceFrameRate() {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            android.view.SurfaceView sv = findSurfaceView(getWindow().getDecorView());
            if (sv == null) {
                Log.w(TAG, "SurfaceView not found for setFrameRate");
                return;
            }
            android.view.SurfaceHolder holder = sv.getHolder();
            if (holder == null) return;
            android.view.Surface surface = holder.getSurface();
            if (surface == null || !surface.isValid()) return;

            surface.setFrameRate(maxRefreshRate, android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
            Log.i(TAG, "Surface.setFrameRate(" + maxRefreshRate + ")");
        } catch (Throwable t) { Log.e(TAG, "setFrameRate fail", t); }
    }

    private static android.view.SurfaceView findSurfaceView(android.view.View view) {
        if (view instanceof android.view.SurfaceView) return (android.view.SurfaceView) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                android.view.SurfaceView sv = findSurfaceView(vg.getChildAt(i));
                if (sv != null) return sv;
            }
        }
        return null;
    }

    /**
     * 在 PLAY 状态时把屏幕左右边缘登记为系统手势排除区，避免全面屏上滑回桌面
     * 退出 PLAY 时清空排除区，其他界面行为不受影响
     */
    private void applySystemGestureExclusion(boolean enable) {
        if (Build.VERSION.SDK_INT < 29) return;
        try {
            android.view.SurfaceView sv = findSurfaceView(getWindow().getDecorView());
            if (sv == null || sv.getWidth() <= 0 || sv.getHeight() <= 0) return;
            List<Rect> rects;
            if (enable) {
                int edgePx = dpToPx(48);
                int w = sv.getWidth();
                int h = sv.getHeight();
                rects = new ArrayList<>(2);
                rects.add(new Rect(0, 0, Math.min(edgePx, w), h));
                rects.add(new Rect(Math.max(0, w - edgePx), 0, w, h));
            } else {
                rects = Collections.emptyList();
            }
            sv.setSystemGestureExclusionRects(rects);
            Log.i(TAG, "System gesture exclusion " + (enable ? "enabled" : "disabled") + ", rects=" + rects.size());
        } catch (Throwable t) {
            Log.e(TAG, "setSystemGestureExclusionRects fail", t);
        }
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    public void setAndroidBackPressedFlag() {
        try {
            BeatorajaGame game = (BeatorajaGame) Gdx.app.getApplicationListener();
            bms.player.beatoraja.input.KeyBoardInputProcesseor processor =
                game.getMainController().getInputProcessor().getKeyBoardInputProcesseor();

            // 同时设置两种标志，确保物理 poll 和 逻辑 check 都能捕获
            processor.setAndroidBackPressed(true);
            processor.simulateKeyPress(com.badlogic.gdx.Input.Keys.ESCAPE);
        } catch (Exception ignored) {}
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            lastUserTouchTime = SystemClock.uptimeMillis();

            // 无论是否在输入态，都触发 Back -> ESCAPE 的映射逻辑
            setAndroidBackPressedFlag();

            if (isTextInputActive) {
                // 如果处于文本输入模式，显式关闭输入态
                setTextInputActive(false);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        lastUserTouchTime = SystemClock.uptimeMillis();
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) isUserTouching = true;
        else if (ev.getActionMasked() == MotionEvent.ACTION_UP) isUserTouching = false;
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        lastUserTouchTime = SystemClock.uptimeMillis();
        return super.dispatchGenericMotionEvent(ev);
    }

    public void setTextInputActive(boolean active) {
        isTextInputActive = active;
        if (active) {
            stopKeepAlive();
            runOnUiThread(() -> {
                setOnscreenKeyboardFocusable(true);
                getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
                // 重新建立 IME 连接：isTextInputActive 变为 true 后需要让系统重新查询 InputConnection
                android.view.SurfaceView sv = findSurfaceView(getWindow().getDecorView());
                if (sv != null && inputMethodManager != null) {
                    sv.requestFocus();
                    inputMethodManager.restartInput(sv);
                    inputMethodManager.showSoftInput(sv, InputMethodManager.SHOW_IMPLICIT);
                }
            });
        } else {
            startKeepAlive();
            runOnUiThread(() -> {
                getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
                android.view.SurfaceView sv = findSurfaceView(getWindow().getDecorView());
                if (sv != null && inputMethodManager != null) {
                    inputMethodManager.hideSoftInputFromWindow(sv.getWindowToken(), 0);
                    // 核心修复：即使已经隐藏，也必须 restartInput。
                    // 这样系统会重新调用 onCreateInputConnection，而此时 isTextInputActive 为 false，
                    // 我们的实现会返回 null，从而彻底切断输入关联，防止物理按键误触。
                    inputMethodManager.restartInput(sv);
                }
                suppressImeForGameInput();
            });
        }
    }

    private void suppressImeForGameInput() {
        if (isTextInputActive) return;
        View currentFocus = getCurrentFocus();
        hideImeFromWindow(currentFocus);
        setOnscreenKeyboardFocusable(false);
        if (currentFocus instanceof EditText) currentFocus.clearFocus();
    }

    private void hideImeFromWindow(View tokenView) {
        try {
            Window window = getWindow();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && window.getInsetsController() != null) {
                window.getInsetsController().hide(WindowInsets.Type.ime());
            }
            if (inputMethodManager != null) {
                View view = tokenView != null ? tokenView : window.getDecorView();
                inputMethodManager.hideSoftInputFromWindow(view.getWindowToken(), 0);
            }
        } catch (Throwable t) {
            Log.w(TAG, "hideImeFromWindow fail: " + t.getMessage());
        }
    }

    private void setOnscreenKeyboardFocusable(boolean focusable) {
        try {
            View root = getWindow().getDecorView();
            if (root instanceof ViewGroup) {
                applyFocusableToEditTexts((ViewGroup) root, focusable);
            }
        } catch (Throwable t) {
            Log.w(TAG, "setOnscreenKeyboardFocusable fail: " + t.getMessage());
        }
    }

    private void applyFocusableToEditTexts(ViewGroup vg, boolean focusable) {
        for (int i = 0; i < vg.getChildCount(); i++) {
            View child = vg.getChildAt(i);
            if (child instanceof EditText) {
                child.setFocusable(focusable);
                child.setFocusableInTouchMode(focusable);
                if (!focusable && child.hasFocus()) {
                    child.clearFocus();
                }
            } else if (child instanceof ViewGroup) {
                applyFocusableToEditTexts((ViewGroup) child, focusable);
            }
        }
    }

    public void openUrl(String url, String message) {
        runOnUiThread(() -> {
            if (message != null && !message.isEmpty()) {
                new android.app.AlertDialog.Builder(this)
                    .setMessage(message)
                    .setPositiveButton("OK", (dialog, which) -> openUrlDirect(url))
                    .setNegativeButton("Cancel", null)
                    .show();
            } else {
                openUrlDirect(url);
            }
        });
    }

    private boolean isExitDialogShowing = false;

    public boolean isExitDialogShowing() {
        return isExitDialogShowing;
    }

    public void showNativeExitDialog() {
        if (isExitDialogShowing) return;
        runOnUiThread(() -> {
            isExitDialogShowing = true;
            new android.app.AlertDialog.Builder(this)
                .setTitle("Exit Game?")
                .setMessage("Are you sure you want to exit?")
                .setPositiveButton("YES", (dialog, which) -> {
                    isExitDialogShowing = false;
                    // 调用 exit() 会设置 disposing=true 并停止渲染线程，然后 dispose() 清理资源
                    BeatorajaGame game = (BeatorajaGame) Gdx.app.getApplicationListener();
                    if (game != null && game.getMainController() != null) {
                        game.getMainController().exit();
                    }
                    // 自然结束 Activity
                    finish();
                })
                .setNegativeButton("NO", (dialog, which) -> {
                    isExitDialogShowing = false;
                })
                .setOnCancelListener(dialog -> {
                    isExitDialogShowing = false;
                })
                .show();
        });
    }

    private void openUrlDirect(String url) {
        try {
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) { Log.e(TAG, "Failed to open URL: " + url, e); }
    }

    @Override
    protected void onDestroy() {
        try {
            BeatorajaGame game = (BeatorajaGame) Gdx.app.getApplicationListener();
            if (game != null && game.getMainController() != null) {
                game.getMainController().removeMainStateListener(systemGestureExclusionListener);
            }
        } catch (Exception ignored) {}
        stopKeepAlive();
        super.onDestroy();
    }
}
