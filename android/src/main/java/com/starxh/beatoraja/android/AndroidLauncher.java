package com.starxh.beatoraja.android;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
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
import com.starxh.beatoraja.PlaybackCpuLockManager;
import barsoosayque.libgdxoboe.OboeAudio;

import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.view.WindowInsets;
import org.json.JSONObject;

import bms.player.beatoraja.Config;
import bms.player.beatoraja.MainStateListener;
import bms.player.beatoraja.PlayerConfig;
import bms.player.beatoraja.BMSPlayerMode;

public class AndroidLauncher extends AndroidApplication {

	/** 记录"上次完成 assets 落盘时的 versionCode"的 SharedPreferences */
	private static final String PREFS_ASSET_VERSION = "asset_version";
	private static final String KEY_INSTALLED_VERSION_CODE = "installed_version_code";
	/**
	 * 版本更新后为 true：本次启动对 APK assets 落盘的内置皮肤（assets/skin →
	 * filesDir/skin，含 GenericTheme 的 Lua/JS 等代码文件）执行覆盖拷贝，
	 * 解决"用户更新版本但 filesDir/skin 里还是旧版皮肤"的问题。
	 * 只覆盖 skin：sound/bgm/默认歌曲/字体允许用户定制，保持补缺语义。
	 * walkure 等 WebView/Gdx.files.internal 直读资源不落盘，天然随 APK 更新。
	 * 在 onCreate 主线程计算，ZipExtractor 后台线程只读，volatile 保证可见性。
	 */
	private volatile boolean forceAssetsOverwrite;

	/**
	 * assets 由来の重い資産（内蔵曲パック inochi_ogg = 1082 ファイル / 26MB、
	 * 内蔵フォールバックフォント 4MB）を落とす背景スレッド。
	 * <p>
	 * 🔴 これを主スレッド（onCreate）でやると、ストレージ権限を許可した直後の
	 * {@code recreate()} で走る 2 回目の onCreate が 5 秒を超え、
	 * {@code Input dispatching timed out (Waited 5000ms for FocusEvent(hasFocus=true))}
	 * の ANR になる（LEGION Y700 / Android 15 で実測 6.8 秒）。
	 * 曲スキャンの直前に {@link #awaitAssetSeed()} で待ち合わせる。
	 */
	private static volatile Thread assetSeedThread;

	/** 内蔵曲パックの落盤完了マーカー（SharedPreferences のキー）。 */
	private static final String KEY_INOCHI_SEEDED = "inochi_ogg_seeded";

	/** {@link #awaitAssetSeed()} の待ち上限。超えたら諦めてスキャンへ進む（マーカー未書き込みなので次回補完される）。 */
	private static final long ASSET_SEED_JOIN_TIMEOUT_MS = 120_000L;

	/** {@link #awaitAssetSeed()} のポーリング間隔。 */
	private static final long ASSET_SEED_POLL_MS = 250L;

	/**
	 * 「進捗が止まった」と見なして待つのをやめるまでの時間。
	 * <p>
	 * 🔴 上限 120 秒をそのまま使うと、極端に遅いストレージで曲スキャンが 2 分間
	 * 固まって見える。コピー済みファイル数がこの時間まったく増えなければ
	 * 「もう進みそうにない」と判断して先へ進む（マーカーは立っていないので
	 * 次回起動時に差分が埋まる）。
	 */
	private static final long ASSET_SEED_STALL_TIMEOUT_MS = 20_000L;

	/** {@link #awaitAssetSeed()} が進捗をログに出す間隔。 */
	private static final long ASSET_SEED_PROGRESS_LOG_MS = 3_000L;

	/** AssetSeeder がコピーし終えたファイル数。書くのは AssetSeeder スレッドだけ。 */
	private static volatile int assetSeedCopied;
	/** AssetSeeder がコピーすべき総ファイル数（判明するまで 0）。 */
	private static volatile int assetSeedTotal;

	/** 硬件最大刷新率，MainController 通过反射读取 */
	public static float maxRefreshRate = 60f;
    private static final String TAG = "AndroidLauncher";
    private static AndroidLauncher instance;

    /** 静态 VSync 回调，防止持有 Activity 引用导致内存泄漏，同时保持全局同步稳定性 */
    private static boolean vsyncCallbackRegistered = false;
    private static final android.view.Choreographer.FrameCallback vsyncCallback = new android.view.Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            bms.player.beatoraja.MainController.setLastVsyncTimeNanos(frameTimeNanos);
            // 只要进程存活且窗口可见，Choreographer 就会持续触发此回调。
            // 静态引用不会阻止 Activity 被回收。
            android.view.Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private InputMethodManager inputMethodManager;
    private volatile boolean isTextInputActive = false;
    private OboeAudio oboeAudio;
    private String mLanguage = "en";

    /**
     * Oboe 流的 pause/resume 专用后台线程。
     *
     * <p>oboe_engine 的 resume()/stop() 都要抢它自己那把 lifecycle 递归锁，而锁屏 / 切后台的
     * 瞬间恰恰是音频设备变化的高发点（日志里的 "send audio device update msg"）：
     * Oboe 回调线程收到 ErrorDisconnected 之后，会拿着这把锁在 connect_to_device() 里
     * close + openStream，设备切换时这一步可以拖到秒级。</p>
     *
     * <p>在主线程（Activity 生命周期回调）同步调 resume()/pause() 就得等这把锁 ——
     * 表现就是 logcat 里的 {@code Skipped 189 frames!}（约 3 秒主线程停顿）。</p>
     *
     * <p>流的状态切换本来也不需要同步语义：晚几十毫秒生效完全无害，而主线程一卡就是明显
     * 掉帧。所以统一丢到这个 daemon 线程上执行；单线程保证 pause/resume 的相对顺序。</p>
     */
    private static final java.util.concurrent.ExecutorService OBOE_LIFECYCLE_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "OboeAudio-Lifecycle");
                t.setDaemon(true);
                return t;
            });

    /**
     * 在后台线程切换 Oboe 流状态。绝不在主线程同步等待 native 锁。
     *
     * @param resume true = 让流保持/恢复 running；false = 停流
     */
    private void postOboeLifecycle(final boolean resume) {
        // 在这里就把引用读进局部变量,让下面的 lambda 只捕获 OboeAudio 而不捕获
        // this(Activity)。否则任务在队列里排队的这段时间,Activity 会一直被引用着
        // 释放不掉。OboeAudio 是 createAudio() 里建出来的、与进程同寿命,不依赖 Activity。
        final OboeAudio audio = oboeAudio;
        if (audio == null) return;
        try {
            OBOE_LIFECYCLE_EXECUTOR.execute(() -> {
                long startNanos = System.nanoTime();
                try {
                    if (resume) audio.resume(); else audio.pause();
                } catch (Throwable t) {
                    Log.w(TAG, "oboeAudio lifecycle failed", t);
                }
                long ms = (System.nanoTime() - startNanos) / 1000000L;
                // 这条日志就是诊断依据:它越大,说明 native 侧重连/等锁越久。
                // 它跑在自己的线程上,多慢都不会再变成 "Skipped N frames"。
                if (ms > 50) {
                    Log.i(TAG, "oboeAudio " + (resume ? "resume" : "pause") + " took " + ms + "ms");
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "postOboeLifecycle rejected", t);
        }
    }

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

        // 把 core 模块的 java.util.logging 输出接到 logcat，否则真机上那条日志全丢进黑洞
        LogcatLogHandler.install();

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

        // 注入 CPU 保活实现(PARTIAL_WAKE_LOCK)。
        // 注意 config.useWakelock 用的是 libGDX 的 FULL_WAKE_LOCK,而它在 AndroidApplication
        // 的 onPause() 里会被 release —— 锁屏那一瞬间就没了,之后 CPU 进入低功耗,
        // 音符调度线程唤醒精度崩掉,表现为音频不同步/停顿。这把锁的生命周期由
        // MusicPlayer 自己管(create/terminate),与 Activity 生命周期无关。
        try {
            PlaybackCpuLockManager.setGlobalLock(new AndroidPlaybackCpuLock(this));
        } catch (Throwable t) {
            Log.w(TAG, "Failed to install playback CPU lock", t);
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

        // 使用默认的 FillResolutionStrategy（全屏填充，缓冲尺寸 = 屏幕原生分辨率）
        // 是否拉伸到全屏（去黑边）由 MainController.render() 根据 config.stretchFullscreen 决定
        config.resolutionStrategy = new FillResolutionStrategy();

        File filesDir = getExternalFilesDir(null);
        String root = filesDir.getAbsolutePath();
        System.setProperty("beatoraja.root", root);

        // 版本更新检测：APK versionCode 与上次记录不同(含首次安装)时,本次启动对
        // 内置皮肤(assets/skin)执行覆盖拷贝——解决"用户更新了版本,但 filesDir/skin
        // 里的皮肤(如 GenericTheme 代码文件)还是旧版"的问题。
        // 必须在 createDefaultDirectories() 和 ZipExtractor 线程启动之前完成计算。
        forceAssetsOverwrite = detectAndRecordVersionUpdate();

        // 首次启动时创建必要的目录
        createDefaultDirectories();

        // 检测并解压 zip 到外部存储 —— 这些都是同步 IO + CPU,放后台线程
        // 避免主线程阻塞 5+ 秒触发 ANR(尤其 song zip 大小通常几十~几百 MB)
        // checkAndExtractSongZips() 是公开静态入口,后续 SettingsActivity 可以重新触发
        final File filesDirForExtract = filesDir;
        new Thread(() -> {
            // 顶层兜底：这里是普通 Thread，任何未捕获异常都会杀掉整个进程（实测过一次
            // ZipCoder 抛 unchecked 的 IllegalArgumentException 把 App 打崩）。
            // 导入失败只应表现为"这次没导进去"，不该让用户丢进程。
            try {
                ensureExternalSkinZip(filesDirForExtract);
                ensureExternalBgmZip(filesDirForExtract);
                ensureExternalSoundZip(filesDirForExtract);
                ensureExternalSongZip();
            } catch (Throwable t) {
                Log.e(TAG, "Resource import failed", t);
            }
        }, "ZipExtractor").start();

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
            // 将设置页配置的 bmsroot 目录注册进 folder 表，使新增目录在进游戏后即可作为
            // [root] 的子项显示（如 "songs 0"），再由游戏内手动刷新扫描歌曲。
            // 必须在 Gdx.initialize() 之前调用，故 accessor 内部用 java.io.File 而非 Gdx.files。
            androidAccessor.registerBmsRootFolders();
        } catch (Throwable t) { Log.e(TAG, "DB init fail", t); return; }

        setupHighRefreshRate();
        inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);

        // VSync 相位同步：使用全局静态 Choreographer 回调向 MainController 发送 VSync 时间戳，
        // 从而将应用层的绝对时间帧率控制与系统 VSync 锁定。
        // 使用静态实例避免了匿名内部类持有 Activity 引用造成的内存泄漏。
        if (!vsyncCallbackRegistered) {
            android.view.Choreographer.getInstance().postFrameCallback(vsyncCallback);
            vsyncCallbackRegistered = true;
        }

        // Set up unified score DB factory (Android native SQLite, not JDBC)
        bms.player.beatoraja.ScoreDatabaseAccessor.setFactory(
            path -> new bms.player.beatoraja.score.AndroidScoreDatabaseAccessor(AndroidLauncher.this, path));

        initialize(new BeatorajaGame(null, null, null, BMSPlayerMode.AUTOPLAY, false), config);

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
    private static final String BGM_FOLDER = "bgm";
    private static final String SOUND_FOLDER = "sound";

    /**
     * 首次启动时创建必要的目录。
     * <p>
     * 🔴 这里**只做 mkdirs 之类的轻量操作**。内蔵曲パック(inochi_ogg)や内蔵フォントの
     * コピーは {@link #startAssetSeed(File, File)} で背景スレッドに逃がす。
     * onCreate で 1082 ファイルを書くと、権限許可後の recreate() で走る 2 回目の
     * onCreate が 5 秒を超えて ANR になる（LEGION Y700 / Android 15 で実測 6.8 秒）。
     */
    private void createDefaultDirectories() {
        File downloadBase = new File(getDownloadPath(), BEATORAJA_BASE);
        File songsDir = new File(downloadBase, SONGS_FOLDER);
        File skinsDir = new File(downloadBase, SKINS_FOLDER);
        File bgmDir = new File(downloadBase, BGM_FOLDER);
        File soundDir = new File(downloadBase, SOUND_FOLDER);

        if (!songsDir.exists()) {
            songsDir.mkdirs();
            Log.i(TAG, "Created songs directory: " + songsDir.getAbsolutePath());
        }

        if (!skinsDir.exists()) {
            skinsDir.mkdirs();
            Log.i(TAG, "Created skins directory: " + skinsDir.getAbsolutePath());
        }
        if (!bgmDir.exists()) {
            bgmDir.mkdirs();
            Log.i(TAG, "Created bgm directory: " + bgmDir.getAbsolutePath());
        }
        if (!soundDir.exists()) {
            soundDir.mkdirs();
            Log.i(TAG, "Created sound directory: " + soundDir.getAbsolutePath());
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
        }

        // 重い assets コピー（inochi_ogg 1082 ファイル / 内蔵フォント）は背景スレッドへ。
        // 曲スキャンは MainController 側が checkAndExtractSongZips() 経由で待ち合わせる。
        startAssetSeed(songsDir, filesDir);
    }

    /**
     * assets 由来の重い資産を背景スレッドで落とす。
     * <ul>
     * <li>内蔵曲パック {@code assets/inochi_ogg}（1082 ファイル / 26MB）→ songs/inochi_ogg/
     * <li>内蔵フォールバックフォント {@code assets/font/VL-Gothic-Regular.ttf} → filesDir/font/
     * </ul>
     * 落とす条件は「前回未完了（marker 無し）」または「ディレクトリごと消えた」のいずれか。
     * 中身の一部だけ消した場合は触らない — ユーザーが既定曲フォルダを整理する余地を残す
     * 既存セマンティク（copyAssetFolder 自体も既存ファイルはスキップする）。
     */
    private void startAssetSeed(File songsDir, File filesDir) {
        // 一度きり。recreate() や構成変更で onCreate が再走しても二重コピーしない
        // （onCreate は常にメインスレッドなので、この null チェックに競合は無い）。
        if (assetSeedThread != null) {
            return;
        }
        // Activity をスレッドに持ち込まないよう、必要なものはここで取り出しておく
        final AssetManager am = getAssets();
        final SharedPreferences sp = getApplicationContext()
                .getSharedPreferences(PREFS_ASSET_VERSION, Context.MODE_PRIVATE);

        Thread t = new Thread(() -> {
            try {
                long start = System.currentTimeMillis();

                File inochiDir = new File(songsDir, "inochi_ogg");
                // 落盘が必要なのは「前回が未完了」or「ディレクトリごと消えた」とき。
                // 旧実装は後者だけを見ていたので、mkdirs 直後に中断すると
                // 「ディレクトリは在るが中身が欠けたまま二度と補完されない」状態になった。
                // 逆に、一部のファイルだけ消した場合は marker が真なので触らない
                // （ユーザーが既定曲フォルダを整理する余地を残す既存セマンティク）。
                final boolean seeded = sp.getBoolean(KEY_INOCHI_SEEDED, false);
                if (!seeded || !inochiDir.exists()) {
                    Log.i(TAG, "Seeding inochi_ogg from assets to " + inochiDir.getAbsolutePath()
                            + " (marker=" + seeded + ", dirExists=" + inochiDir.exists() + ")");
                    inochiDir.mkdirs();
                    // 総数を先に公開する（awaitAssetSeed の進捗表示用。失敗しても 0 のまま）
                    try {
                        String[] entries = am.list("inochi_ogg");
                        assetSeedTotal = entries != null ? entries.length : 0;
                    } catch (IOException e) {
                        assetSeedTotal = 0;
                    }
                    final int failed = copyAssetFolder(am, "inochi_ogg", inochiDir, false);
                    final long elapsed = System.currentTimeMillis() - start;
                    if (failed == 0) {
                        sp.edit().putBoolean(KEY_INOCHI_SEEDED, true).apply();
                        Log.i(TAG, "Seeded inochi_ogg in " + elapsed + "ms ("
                                + assetSeedCopied + " files)");
                    } else {
                        // 🔴 1 ファイルでも失敗したらマーカーを立てない。立てると
                        // 「欠けたまま二度と補完されない」状態に固定される。
                        // 失敗したファイルは copyAssetFile 側で削除済みなので、
                        // 次回起動の copyAssetFolder が差分としてコピーし直す。
                        Log.w(TAG, "Seeding inochi_ogg finished with " + failed + " failure(s) in "
                                + elapsed + "ms; marker NOT set (diff will be filled next launch)");
                    }
                }

                if (filesDir != null) {
                    File fallbackFont = new File(filesDir, "font/VL-Gothic-Regular.ttf");
                    if (!fallbackFont.exists()) {
                        // 失败时 copyAssetFile 会删掉半端文件,下次启动自然会重试
                        if (copyAssetFile(am, "font/VL-Gothic-Regular.ttf", fallbackFont)) {
                            Log.i(TAG, "Seeded fallback font: " + fallbackFont.getAbsolutePath());
                        } else {
                            Log.w(TAG, "Failed to seed fallback font (will retry next launch)");
                        }
                    }
                }
            } catch (Throwable e) {
                // 普通の Thread なので未捕捉例外はプロセスを殺す。資産の補完失敗は
                // 「今回入らなかった」だけで済ませる（曲スキャンは待ち合わせを諦めて進む）。
                Log.e(TAG, "Asset seeding failed", e);
            }
        }, "AssetSeeder");
        assetSeedThread = t;
        t.start();
    }

    /**
     * 内蔵資産の落盤完了を待つ。MainController の曲スキャン（SongUpdateThread）が
     * {@link #checkAndExtractSongZips()} を通じて呼ぶ。初回起動でも内蔵曲パックが
     * スキャンに間に合うようにするための待ち合わせ。
     * <p>
     * 待ち方は 3 段構え:
     * <ol>
     * <li>{@link #ASSET_SEED_POLL_MS} ごとにコピー済みファイル数を見て進捗をログに出す
     * <li>{@link #ASSET_SEED_STALL_TIMEOUT_MS} のあいだ 1 ファイルも増えなければ諦める
     *     （極端に遅いストレージで 2 分間スキャンが固まって見えるのを避ける）
     * <li>それでも進み続ける場合は {@link #ASSET_SEED_JOIN_TIMEOUT_MS} で打ち切る
     * </ol>
     * いずれの打ち切りでもマーカーは立っていないので、次回起動時に差分が埋まる。
     * <p>
     * 🔴 主スレッドから呼ばれたら待たない（ANR を作らないため）。
     */
    private static void awaitAssetSeed() {
        final Thread t = assetSeedThread;
        if (t == null || t == Thread.currentThread()) {
            return;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "awaitAssetSeed() called on main thread - skipping wait");
            return;
        }
        if (!t.isAlive()) {
            // すでに落盤が終わっている（2 回目以降の曲スキャン）。ログも出さずに素通り
            return;
        }
        final long start = System.currentTimeMillis();
        long lastProgressAt = start;
        long lastLoggedAt = start;
        int lastCopied = assetSeedCopied;
        try {
            while (t.isAlive()) {
                t.join(ASSET_SEED_POLL_MS);
                final long now = System.currentTimeMillis();
                final int copied = assetSeedCopied;
                if (copied != lastCopied) {
                    lastCopied = copied;
                    lastProgressAt = now;
                    if (now - lastLoggedAt >= ASSET_SEED_PROGRESS_LOG_MS) {
                        lastLoggedAt = now;
                        Log.i(TAG, "AssetSeeder progress: " + copied + "/" + assetSeedTotal + " files");
                    }
                    continue;
                }
                if (now - lastProgressAt >= ASSET_SEED_STALL_TIMEOUT_MS) {
                    Log.w(TAG, "awaitAssetSeed() stalled: no new file for "
                            + (now - lastProgressAt) + "ms (copied " + copied + "/" + assetSeedTotal
                            + "); proceeding - remaining files will be filled next launch");
                    return;
                }
                if (now - start >= ASSET_SEED_JOIN_TIMEOUT_MS) {
                    Log.w(TAG, "awaitAssetSeed() timed out after " + (now - start) + "ms (copied "
                            + copied + "/" + assetSeedTotal
                            + "); proceeding - remaining files will be filled next launch");
                    return;
                }
            }
            Log.i(TAG, "AssetSeeder finished in " + (System.currentTimeMillis() - start)
                    + "ms; scan proceeds (copied " + assetSeedCopied + "/" + assetSeedTotal + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 检测外部 skin 并导入到 skin 目录
     */
    private void ensureExternalSkinZip(File filesDir) {
        File externalSkinsDir = new File(getDownloadPath(), BEATORAJA_BASE + "/" + SKINS_FOLDER);
        File internalSkinDir = new File(filesDir, "skin");
        // 先把 APK assets 里的内置皮肤(GenericTheme、default 等)落到 filesDir/skin/,
        // 再 overlay 用户从 Download/beatoraja/skins/ 导入的皮肤。
        // 否则只要用户放了任何外部皮肤,内置皮肤就会被跳过、列表里消失。
        // 平时 copyAssetFolder 按子项 if (!destSub.exists()) 补缺,重复调用安全;
        // 版本更新(forceAssetsOverwrite)时改为覆盖拷贝,内置皮肤跟随 APK 更新,
        // 但只覆盖 assets 里存在的同名文件,用户自行导入的皮肤不受影响。
        ensureSkinAssets(filesDir);
        ensureExternalResourceImport(externalSkinsDir, internalSkinDir, "skin");
    }

    /**
     * 检测外部 BGM 集并导入到 bgm 目录（与 skin 同样支持 zip 或文件夹）。
     * 用户把 <code>Download/beatoraja/bgm/&lt;setName&gt;.zip</code> 或
     * <code>Download/beatoraja/bgm/&lt;setName&gt;/</code> 放进来即可。
     */
    private void ensureExternalBgmZip(File filesDir) {
        File externalBgmDir = new File(getDownloadPath(), BEATORAJA_BASE + "/" + BGM_FOLDER);
        File internalBgmDir = new File(filesDir, "bgm");
        ensureExternalResourceImport(externalBgmDir, internalBgmDir, "bgm");
    }

    /**
     * 检测外部 sound 集并导入到 sound 目录（与 skin 同样支持 zip 或文件夹）。
     * 先把 APK assets/sound/default/ 落到 filesDir/sound/default/(作为永驻默认集),
     * 再 overlay 用户从 Download/beatoraja/sound/ 导入的集。
     * 否则只要用户放了任何外部集,内置的 default 就会被跳过、列表里消失。
     */
    private void ensureExternalSoundZip(File filesDir) {
        File externalSoundDir = new File(getDownloadPath(), BEATORAJA_BASE + "/" + SOUND_FOLDER);
        File internalSoundDir = new File(filesDir, "sound");
        ensureSoundAssets(filesDir);
        ensureExternalResourceImport(externalSoundDir, internalSoundDir, "sound");
    }

    /**
     * 把 APK assets/sound/default/ 拷到 filesDir/sound/default/。
     * 与 ensureSkinAssets 同模式:无早退出,每次启动都调用,copyAssetFolder 内部按子项
     * if (!destSub.exists()) 跳过已有文件,所以是幂等的"补缺"语义。
     * 注意:不做版本覆盖——sound 允许用户定制,覆盖会冲掉用户的修改。
     */
    private void ensureSoundAssets(File filesDir) {
        File soundDefaultDir = new File(filesDir, "sound/default");
        soundDefaultDir.mkdirs();
        copyAssetFolder(getAssets(), "sound/default", soundDefaultDir);
    }

    /**
     * 把 <code>externalDir</code> 下的 zip / 子文件夹导入到 <code>internalDir</code>。
     * 与 skin 一致:zip 优先 move(失败则 copy+extract),子文件夹 move(失败则 copy+delete)。
     * 导入完成后源文件/源目录会被删除,避免下次启动重复导入。
     *
     * @return true 表示有 zip 或子文件夹被处理过
     */
    private boolean ensureExternalResourceImport(File externalDir, File internalDir, String logTag) {
        if (!externalDir.exists() || !externalDir.isDirectory()) return false;

        if (!internalDir.exists() && !internalDir.mkdirs()) {
            Log.w(TAG, "Failed to create internal dir: " + internalDir.getAbsolutePath());
            return false;
        }

        boolean hasProcessed = false;

        File[] zipFiles = externalDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".zip"));
        if (zipFiles != null && zipFiles.length > 0) {
            Log.i(TAG, "Found " + zipFiles.length + " " + logTag + " zip(s) in: " + externalDir.getAbsolutePath());
            for (File zip : zipFiles) {
                String name = zip.getName().replace(".zip", "");
                File destFile = new File(internalDir, name);

                if (destFile.isDirectory() && destFile.list() != null && destFile.list().length > 0) {
                    Log.i(TAG, logTag + " already exists, skip: " + destFile.getAbsolutePath());
                    zip.delete();
                    continue;
                } else if (destFile.exists() && !destFile.isDirectory()) {
                    destFile.delete();
                }

                File tempZip = new File(internalDir, name + ".zip");
                if (zip.renameTo(tempZip)) {
                    Log.i(TAG, "Moved " + logTag + " zip to: " + tempZip.getAbsolutePath());
                    if (extractZip(tempZip, internalDir)) {
                        tempZip.delete();
                        Log.i(TAG, "Deleted " + logTag + " zip after extract: " + zip.getName());
                    }
                } else {
                    if (extractZip(zip, internalDir)) {
                        zip.delete();
                        Log.i(TAG, "Deleted " + logTag + " zip after extract: " + zip.getName());
                    }
                }
            }
            hasProcessed = true;
        }

        // 补一次对 internalDir 里残留 zip 的重试。
        // 上面的流程是"把外部 zip 搬进 internalDir 再解压"；一旦解压失败（编码异常、进程被杀、
        // 空间不足…）zip 就留在 internalDir，而外部目录里已经没有它了 —— 只扫外部目录的话
        // 用户只能重新拷贝一份进来。解压成功后 zip 必被删除，所以"internalDir 里还有 zip"
        // 等价于"上次没成功"，重试是安全且有意义的。
        File[] staleZips = internalDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".zip"));
        if (staleZips != null && staleZips.length > 0) {
            for (File stale : staleZips) {
                Log.i(TAG, "Retrying leftover " + logTag + " zip: " + stale.getName());
                if (extractZip(stale, internalDir)) {
                    stale.delete();
                    Log.i(TAG, "Deleted leftover " + logTag + " zip after extract: " + stale.getName());
                }
            }
            hasProcessed = true;
        }

        File[] folders = externalDir.listFiles(File::isDirectory);
        if (folders != null && folders.length > 0) {
            for (File folder : folders) {
                File destDir = new File(internalDir, folder.getName());
                if (destDir.exists()) {
                    Log.i(TAG, logTag + " folder already exists, skip: " + destDir.getAbsolutePath());
                } else if (folder.renameTo(destDir)) {
                    Log.i(TAG, "Moved " + logTag + " folder to: " + destDir.getAbsolutePath());
                } else {
                    try {
                        copyDirectory(folder, destDir);
                        deleteRecursive(folder);
                        Log.i(TAG, "Copied and deleted " + logTag + " folder: " + folder.getName());
                    } catch (IOException e) {
                        Log.e(TAG, "Failed to copy " + logTag + " folder: " + folder.getName(), e);
                    }
                }
            }
            hasProcessed = true;
        }

        return hasProcessed;
    }

    private boolean extractZip(File zipFile, File destDir) {
        destDir.mkdirs();
        String name = zipFile.getName().replace(".zip", "");
        File extractDir = new File(destDir, name);

        if (extractDir.exists() && extractDir.list() != null && extractDir.list().length > 0) {
            Log.i(TAG, "Already extracted, skip: " + extractDir.getAbsolutePath());
            return false;
        }

        try (java.util.zip.ZipFile zip = openZipFile(zipFile)) {
            String prefixToStrip = findCommonZipPrefix(zip);
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (prefixToStrip != null && entryName.startsWith(prefixToStrip)) {
                    entryName = entryName.substring(prefixToStrip.length());
                }
                if (entryName.isEmpty()) continue;

                File outFile = new File(extractDir, entryName);
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
        } catch (Exception e) {
            // 必须是 Exception 而不是 IOException：条目名解码失败时 ZipCoder 抛的是
            // IllegalArgumentException(unchecked)，漏掉它就等于让后台线程带异常死掉 → 进程被杀。
            Log.e(TAG, "Failed to extract zip: " + zipFile.getName(), e);
            // 解到一半的目录会让下次启动被判成"已解压完成"而跳过重试，清掉它以便重试
            deleteRecursive(extractDir);
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
            // 初回起動では、内蔵曲パック(inochi_ogg)の背景コピーが終わるのを待ってから
            // スキャンに入る。これが無いと初回だけ内蔵曲が曲リストに出てこない。
            // MainController からは SongUpdateThread（背景スレッド）経由で呼ばれる。
            awaitAssetSeed();
            instance.ensureExternalSongZip();
        }
    }

    private boolean extractSongZip(File zipFile, File destDir) {
        destDir.mkdirs();
        try (java.util.zip.ZipFile zip = openZipFile(zipFile)) {
            String prefixToStrip = findCommonZipPrefix(zip);
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
        } catch (Exception e) {
            // 同 extractZip()：条目名解码失败抛的是 unchecked 的 IllegalArgumentException，
            // 只 catch IOException 会让后台线程死掉 → 进程被杀。歌曲包同样会遇到这种 zip。
            Log.e(TAG, "Failed to extract song zip: " + zipFile.getName(), e);
            deleteRecursive(destDir);
            return false;
        }
    }

    /**
     * 打开 zip。条目名编码由 {@link ZipCharsetSupport} 逐个候选试出来 —— 不能只按设备语言
     * 猜一次：猜错时 {@code ZipCoder.toString()} 抛的是被包成 <b>unchecked
     * IllegalArgumentException</b> 的 {@code MalformedInputException}，{@code catch
     * (IOException)} 抓不到，会逃出后台线程直接杀进程。
     */
    private java.util.zip.ZipFile openZipFile(File file) throws IOException {
        return ZipCharsetSupport.open(file);
    }

    private String findCommonZipPrefix(java.util.zip.ZipFile zip) {
        Set<String> rootItems = new HashSet<>();
        java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            java.util.zip.ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (name.isEmpty()) continue;

            int slashIndex = name.indexOf('/');
            if (slashIndex < 0) {
                // File at root
                rootItems.add(name);
            } else {
                // Folder at root
                rootItems.add(name.substring(0, slashIndex + 1));
            }

            if (rootItems.size() > 1) {
                return null;
            }
        }

        if (rootItems.size() == 1) {
            String item = rootItems.iterator().next();
            if (item.endsWith("/")) {
                return item;
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
        skinDir.mkdirs();
        copyAssetFolder(getAssets(), "skin", skinDir, forceAssetsOverwrite);
    }

    /**
     * 版本更新检测：把当前 APK 的 versionCode 与 SharedPreferences 里记录的上次值比较。
     * 不同（含首次安装时无记录）则返回 true 并更新记录——调用方据此对内置皮肤
     * （assets/skin）执行覆盖拷贝。仅应在 onCreate 主线程调用一次。
     */
    private boolean detectAndRecordVersionUpdate() {
        long versionCode = 0;
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            Log.w(TAG, "Failed to read versionCode, assets overwrite check defaults to no-op: " + e.getMessage());
            return false;
        }
        SharedPreferences sp = getSharedPreferences(PREFS_ASSET_VERSION, Context.MODE_PRIVATE);
        long installed = sp.getLong(KEY_INSTALLED_VERSION_CODE, -1);
        boolean changed = installed != versionCode;
        if (changed) {
            sp.edit().putLong(KEY_INSTALLED_VERSION_CODE, versionCode).apply();
            Log.i(TAG, "Version change detected: previous=" + installed + " current=" + versionCode
                    + " -> overwrite-extracting built-in skins");
        }
        return changed;
    }

    /** 兼容旧调用：不覆盖已存在文件（补缺语义）。戻り値を使わない呼び出し元のための入口。 */
    private void copyAssetFolder(AssetManager am, String srcPath, File destDir) {
        copyAssetFolder(am, srcPath, destDir, false);
    }

    /**
     * assets のフォルダを丸ごとコピーする。
     *
     * @return 失敗したファイル数（0 = 全部成功）。ディレクトリ内の一部だけ失敗した場合も
     *         その数を返すので、呼び出し元は「完走したか」を判定できる。
     */
    private int copyAssetFolder(AssetManager am, String srcPath, File destDir, boolean overwrite) {
        int failed = 0;
        try {
            String[] assets = am.list(srcPath);
            if (assets == null || assets.length == 0) {
                return copyAssetFile(am, srcPath, new File(destDir, new File(srcPath).getName()), overwrite) ? 0 : 1;
            }
            for (String asset : assets) {
                String src = srcPath + "/" + asset;
                File destSub = new File(destDir, asset);
                boolean isDir = !(asset.contains(".") && !asset.endsWith("/"));
                if (isDir) {
                    destSub.mkdirs();
                    failed += copyAssetFolder(am, src, destSub, overwrite);
                } else if (overwrite || !destSub.exists()) {
                    if (!copyAssetFile(am, src, destSub, overwrite)) failed++;
                }
            }
        } catch (IOException e) {
            failed++;
            Log.w(TAG, "Asset copy fail: " + srcPath + " - " + e.getMessage());
        }
        return failed;
    }

    /** 兼容旧调用：不覆盖已存在文件（补缺语义）。 */
    private boolean copyAssetFile(AssetManager am, String srcPath, File destFile) {
        return copyAssetFile(am, srcPath, destFile, false);
    }

    /**
     * assets の 1 ファイルをコピーする。失敗は投げず戻り値で返す
     * （単一ファイルの失敗で残りを巻き添えにしない）。
     *
     * @return true = 成功
     */
    private boolean copyAssetFile(AssetManager am, String srcPath, File destFile, boolean overwrite) {
        // 覆盖模式下源文件缺失仍会抛 IOException，但逐文件 try-catch 保证单个失败不影响其余文件
        try (InputStream is = am.open(srcPath);
             FileOutputStream fos = new FileOutputStream(destFile)) {
            // 32KB: 内蔵曲パックの平均ファイルは約 24KB なので 1 read でほぼ収まる。
            // 8KB だと 3 回の FUSE 往復になり、1082 ファイルでは差が効く
            // （旧実装は主スレッドでこれをやっていて ANR になった）。
            byte[] buf = new byte[32768];
            int len;
            while ((len = is.read(buf)) > 0) fos.write(buf, 0, len);
            // 進捗カウンタは AssetSeeder スレッドだけが書く（他スレッドのコピーは数えない）。
            // これを awaitAssetSeed が読んで「止まっていないか」を判定する。
            if (Thread.currentThread() == assetSeedThread) assetSeedCopied++;
            return true;
        } catch (IOException e) {
            Log.w(TAG, "Asset file copy fail: " + srcPath + " - " + e.getMessage());
            // 🔴 半端なファイルを残してはいけない。copyAssetFolder は「既存ファイルはスキップ」
            // なので、壊れたファイルが残ると二度とコピーし直されない。消しておけば
            // 次回起動時に差分として拾われる。
            if (destFile.exists() && !destFile.delete()) {
                Log.w(TAG, "Failed to delete partial file: " + destFile.getAbsolutePath());
            }
            return false;
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !isTextInputActive) suppressImeForGameInput();
    }

    @Override
    protected void onResume() {
        long superStartNanos = System.nanoTime();
        super.onResume();
        long superMs = (System.nanoTime() - superStartNanos) / 1000000L;
        if (superMs > 50) {
            // super.onResume() 里 libGDX 要等 GLSurfaceView 恢复。慢的话是框架侧的等待,
            // 和 Oboe 无关 —— 分开报出来,免得和 postOboeLifecycle 的耗时混在一起看。
            Log.i(TAG, "super.onResume took " + superMs + "ms");
        }
        // libGDX 默认的 audio lifecycle listener 已被 onCreate 清空,这里手动确保 Oboe stream
        // 处于 Started 状态。丢到后台线程做 —— 主线程等 native 锁会直接卡掉几秒渲染,
        // 原因见 postOboeLifecycle 的注释。resume() 本身幂等(已 Started 时是 noop)。
        postOboeLifecycle(true);
        if (!isTextInputActive) {
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
            suppressImeForGameInput();
        }
        if (pendingInitialization && !isWaitingForPermissionResult) {
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
        // 异步执行。锁屏那一瞬间 native 侧大概率正在抢 lifecycle 锁做设备重连
        // (日志里的 "send audio device update msg"),主线程等它 = "Skipped 189 frames"。
        // isMusicPlayer 恰好就是 resume 的语义:true 保持流 running,false 停流。
        postOboeLifecycle(isMusicPlayer);

        long superStartNanos = System.nanoTime();
        super.onPause();
        long superMs = (System.nanoTime() - superStartNanos) / 1000000L;
        if (superMs > 50) {
            Log.i(TAG, "super.onPause took " + superMs + "ms");
        }
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

    /**
     * 通过反射从 core 模块 (EventFactory.openFile) 调用的入口。
     * 把 BMS 文件夹下的 README/任意文件用 ACTION_VIEW 交给系统选择器,
     * 让用户挑一个外部应用来打开 (文本查看器、Markdown 阅读器等)。
     */
    public void openFile(String path) {
        runOnUiThread(() -> openFileDirect(path));
    }

    private void openFileDirect(String path) {
        if (path == null || path.isEmpty()) return;
        java.io.File file = new java.io.File(path);
        if (!file.exists()) {
            Log.w(TAG, "openFileDirect: file does not exist: " + path);
            return;
        }
        try {
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, "com.starxh.beatoraja.fileprovider", file);
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "text/plain");
            intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open file: " + path, e);
        }
    }

    // ─── Rating WebView ───

    private static android.app.AlertDialog ratingDialog = null;
    private static double prevStarRating = 0.0;

    /** Called via reflection from core module to show the player rating WebView */
    public static void showRatingWebView(final String jsonData) {
        if (instance == null) return;
        instance.runOnUiThread(() -> {
            if (ratingDialog != null && ratingDialog.isShowing()) {
                ratingDialog.dismiss();
            }

            android.webkit.WebView webView = new android.webkit.WebView(instance);
            webView.setBackgroundColor(android.graphics.Color.parseColor("#1a1a2e"));

            android.webkit.WebSettings ws = webView.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setAllowFileAccess(true);
            ws.setCacheMode(android.webkit.WebSettings.LOAD_NO_CACHE);

            webView.addJavascriptInterface(new Object() {
                @android.webkit.JavascriptInterface
                @android.annotation.SuppressLint("unused")
                public void close() {
                    if (ratingDialog != null) {
                        instance.runOnUiThread(() -> ratingDialog.dismiss());
                    }
                }
                @android.webkit.JavascriptInterface
                @android.annotation.SuppressLint("unused")
                public double getPrevStarRating() {
                    return prevStarRating;
                }
                @android.webkit.JavascriptInterface
                @android.annotation.SuppressLint("unused")
                public void saveStarRating(double val) {
                    prevStarRating = val;
                }
                @android.webkit.JavascriptInterface
                @android.annotation.SuppressLint("unused")
                public String getSettings() {
                    return instance.getSharedPreferences(
                            "beatoraja_prefs",
                            android.content.Context.MODE_PRIVATE)
                            .getString("walkure_settings", "{}");
                }
                @android.webkit.JavascriptInterface
                @android.annotation.SuppressLint("unused")
                public void saveSettings(String json) {
                    instance.getSharedPreferences(
                            "beatoraja_prefs",
                            android.content.Context.MODE_PRIVATE).edit()
                            .putString("walkure_settings", json == null ? "{}" : json).apply();
                }
            }, "RatingBridge");

            webView.setWebViewClient(new android.webkit.WebViewClient() {
                @Override
                public void onPageFinished(android.webkit.WebView view, String url) {
                    if (android.os.Build.VERSION.SDK_INT >= 19) {
                        view.evaluateJavascript(
                            "RatingApp.showRating(" + jsonData + ")", null);
                    } else {
                        view.loadUrl(
                            "javascript:RatingApp.showRating(" + jsonData + ")");
                    }
                }
            });

            webView.loadDataWithBaseURL("https://walkure.local/", loadRatingHtml(), "text/html", "UTF-8", null);

            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(instance);
            builder.setView(webView);
            ratingDialog = builder.create();
            ratingDialog.setCancelable(true);
            ratingDialog.setCanceledOnTouchOutside(true);
            ratingDialog.getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            ratingDialog.show();
            // Truly fullscreen: fill entire screen including system bar area
            ratingDialog.getWindow().setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT);
            ratingDialog.getWindow().setFlags(
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
            ratingDialog.getWindow().getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN |
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        });
    }

    private static String loadRatingHtml() {
        try {
            java.io.InputStream is = instance.getAssets().open("walkure/index.html");
            java.util.Scanner scanner = new java.util.Scanner(is, "UTF-8").useDelimiter("\\A");
            String content = scanner.hasNext() ? scanner.next() : "";
            scanner.close();
            is.close();
            return content;
        } catch (java.io.IOException ex) {
            android.util.Log.e(TAG, "Failed to read rating/index.html from assets", ex);
            return fallbackRatingHtml();
        }
    }

    private static String fallbackRatingHtml() {
        return "<!DOCTYPE html>\n" +
"<html lang=\"en\">\n" +
"<head>\n" +
"<meta charset=\"UTF-8\">\n" +
"<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\">\n" +
"<title>Player Rating</title>\n" +
"<style>\n" +
"* { margin: 0; padding: 0; box-sizing: border-box; }\n" +
"body {\n" +
"  font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif;\n" +
"  background: #1a1a2e;\n" +
"  color: #e0e0e0;\n" +
"  padding: 16px;\n" +
"  overflow-x: hidden;\n" +
"}\n" +
".container { max-width: 600px; margin: 0 auto; }\n" +
".header { text-align: center; padding: 24px 0 16px; }\n" +
".header h1 { font-size: 20px; color: #7ec8e3; margin-bottom: 4px; }\n" +
".header .subtitle { font-size: 13px; color: #888; }\n" +
".rating-card {\n" +
"  background: linear-gradient(135deg, #16213e, #0f3460);\n" +
"  border-radius: 16px;\n" +
"  padding: 24px;\n" +
"  text-align: center;\n" +
"  margin-bottom: 16px;\n" +
"  border: 1px solid #1a4a6e;\n" +
"}\n" +
".rating-card .star { font-size: 48px; font-weight: bold; color: #f0d060; }\n" +
".rating-card .theta { font-size: 14px; color: #888; margin-top: 4px; }\n" +
".rating-card .count { font-size: 13px; color: #aaa; margin-top: 2px; }\n" +
".section-title {\n" +
"  font-size: 16px; font-weight: 600; color: #7ec8e3;\n" +
"  margin: 20px 0 10px; padding-bottom: 6px;\n" +
"  border-bottom: 1px solid #2a2a4e;\n" +
"}\n" +
".chart-entry {\n" +
"  background: #16213e; border-radius: 10px;\n" +
"  padding: 12px 14px; margin-bottom: 8px;\n" +
"  border-left: 4px solid #0f3460;\n" +
"}\n" +
".chart-entry .name { font-size: 14px; font-weight: 500; color: #e0e0e0; }\n" +
".chart-entry .meta { font-size: 12px; color: #888; margin-top: 4px; }\n" +
".chart-entry .prob { font-size: 13px; font-weight: 600; color: #4ecdc4; margin-top: 6px; }\n" +
".empty-state { text-align: center; padding: 40px 16px; color: #666; }\n" +
".empty-state .icon { font-size: 48px; margin-bottom: 12px; }\n" +
".empty-state p { font-size: 14px; }\n" +
".error-state { text-align: center; padding: 40px 16px; color: #e74c3c; }\n" +
".error-state .icon { font-size: 48px; margin-bottom: 12px; }\n" +
".close-btn {\n" +
"  display: block; width: 100%; padding: 14px; margin-top: 20px;\n" +
"  background: #0f3460; color: #7ec8e3;\n" +
"  border: 1px solid #1a4a6e; border-radius: 10px;\n" +
"  font-size: 16px; cursor: pointer; text-align: center;\n" +
"}\n" +
".loading { text-align: center; padding: 60px 16px; color: #888; }\n" +
".spinner {\n" +
"  width: 40px; height: 40px;\n" +
"  border: 4px solid #2a2a4e; border-top-color: #7ec8e3;\n" +
"  border-radius: 50%; animation: spin 0.8s linear infinite;\n" +
"  margin: 0 auto 16px;\n" +
"}\n" +
"@keyframes spin { to { transform: rotate(360deg); } }\n" +
"</style>\n" +
"</head>\n" +
"<body>\n" +
"<div id=\"app\">\n" +
"<div class=\"loading\"><div class=\"spinner\"></div><p>Loading...</p></div>\n" +
"</div>\n" +
"<script>\n" +
"var RatingApp = {\n" +
"  data: null,\n" +
"  showRating: function(dataOrStr) {\n" +
"    if (typeof dataOrStr === 'string') { try { this.data = JSON.parse(dataOrStr); } catch(e) { this.renderError(\"Invalid data\"); return; } }\n" +
"    else { this.data = dataOrStr; }\n" +
"    this.render();\n" +
"  },\n" +
"  render: function() {\n" +
"    var d = this.data;\n" +
"    if (!d) { this.renderLoading(); return; }\n" +
"    if (d.error) { this.renderError(d.error); return; }\n" +
"    var html = '<div class=\"container\">';\n" +
"    html += '<div class=\"header\"><h1>Player Rating</h1>';\n" +
"    if (d.playerName) html += '<div class=\"subtitle\">' + this.esc(d.playerName) + '</div>';\n" +
"    html += '</div>';\n" +
"    html += '<div class=\"rating-card\">';\n" +
"    html += '<div class=\"star\">\\u2605 ' + (d.playerStarRating != null ? d.playerStarRating.toFixed(2) : '?') + '</div>';\n" +
"    html += '<div class=\"theta\">\\u03b8 = ' + (d.theta != null ? d.theta.toFixed(4) : '?') + '</div>';\n" +
"    html += '<div class=\"count\">Matched charts: ' + (d.observationCount || 0) + '</div>';\n" +
"    html += '</div>';\n" +
"    if (d.recommendation && d.recommendation.length > 0) {\n" +
"      html += '<div class=\"section-title\">Recommendations (' + d.recommendation.length + ')</div>';\n" +
"      for (var i = 0; i < Math.min(d.recommendation.length, 30); i++) {\n" +
"        var rec = d.recommendation[i];\n" +
"        html += '<div class=\"chart-entry\">';\n" +
"        html += '<div class=\"name\">' + this.esc(rec.name || 'Unknown') + '</div>';\n" +
"        html += '<div class=\"meta\">' + this.esc(rec.targetClearLamp || '') + '</div>';\n" +
"        if (rec.prob != null) html += '<div class=\"prob\">' + (rec.prob * 100).toFixed(1) + '%</div>';\n" +
"        html += '</div>';\n" +
"      }\n" +
"      if (d.recommendation.length > 30) {\n" +
"        html += '<div style=\"text-align:center;color:#666;font-size:13px;padding:8px;\">... and ' + (d.recommendation.length - 30) + ' more</div>';\n" +
"      }\n" +
"    }\n" +
"    html += '<button class=\"close-btn\" onclick=\"RatingApp.close()\">Close</button></div>';\n" +
"    document.getElementById('app').innerHTML = html;\n" +
"  },\n" +
"  renderLoading: function() {\n" +
"    document.getElementById('app').innerHTML = '<div class=\"loading\"><div class=\"spinner\"></div><p>Loading...</p></div>';\n" +
"  },\n" +
"  renderError: function(msg) {\n" +
"    document.getElementById('app').innerHTML = '<div class=\"container\"><div class=\"error-state\"><div class=\"icon\">!</div><p>' + this.esc(msg) + '</p></div><button class=\"close-btn\" onclick=\"RatingApp.close()\">Close</button></div>';\n" +
"  },\n" +
"  close: function() {\n" +
"    if (window.RatingBridge) { window.RatingBridge.close(); }\n" +
"  },\n" +
"  esc: function(s) {\n" +
"    if (s == null) return '';\n" +
"    return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\"/g,'&quot;');\n" +
"  }\n" +
"};\n" +
"</script>\n" +
"</body>\n" +
"</html>";
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
        instance = null;
        super.onDestroy();
    }
}
