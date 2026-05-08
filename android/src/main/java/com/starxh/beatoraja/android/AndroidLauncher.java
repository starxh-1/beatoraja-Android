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
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.badlogic.gdx.backends.android.AndroidAudio;
import com.badlogic.gdx.Input.Keys;
import com.starxh.beatoraja.BeatorajaGame;
import barsoosayque.libgdxoboe.OboeAudio;

import java.io.*;

import bms.player.beatoraja.Config;
import bms.player.beatoraja.PlayerConfig;
import bms.player.beatoraja.BMSPlayerMode;

public class AndroidLauncher extends AndroidApplication {
    private static final String TAG = "AndroidLauncher";
    private InputMethodManager inputMethodManager;
    private volatile boolean isTextInputActive = false;
    private OboeAudio oboeAudio;

    @Override
    public AndroidAudio createAudio(Context context, AndroidApplicationConfiguration config) {
        try {
            oboeAudio = new OboeAudio(context.getAssets());
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

    private Object backInvokedCallback;
    private Handler keepAliveHandler;
    private volatile long lastUserTouchTime = 0;
    private volatile boolean isUserTouching = false;
    private boolean isSimulatingTouch = false;

    private final Runnable keepAliveRunnable = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            if (isTextInputActive || isUserTouching || (now - lastUserTouchTime < 2000)) {
                if (keepAliveHandler != null) keepAliveHandler.postDelayed(this, 1000);
                return;
            }
            try {
                Window w = getWindow();
                if (w != null && w.getDecorView() != null) {
                    isSimulatingTouch = true;
                    MotionEvent scrollEvent = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 0, 0, 0);
                    scrollEvent.setSource(android.view.InputDevice.SOURCE_MOUSE);
                    w.getDecorView().dispatchGenericMotionEvent(scrollEvent);
                    scrollEvent.recycle();
                    isSimulatingTouch = false;
                }
            } catch (Throwable t) {
                isSimulatingTouch = false;
            }
            if (keepAliveHandler != null) keepAliveHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
            return;
        }

        AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        config.useImmersiveMode = true;
        config.useWakelock = true;

        File filesDir = getExternalFilesDir(null);
        String root = filesDir.getAbsolutePath();
        System.setProperty("beatoraja.root", root);

        // 复制 skin 资源到外部存储（如果不存在）
        ensureSkinAssets(filesDir);

        Config.updateConfigPath();
        PlayerConfig.updateConfigPath();

        String playerName = "player1";
        String[] bmsrootFromConfig = new String[0];
        try {
            File configFile = new File(filesDir, "config_sys.json");
            if (configFile.exists()) {
                String content = new String(java.nio.file.Files.readAllBytes(configFile.toPath()), "UTF-8");
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

        String defaultBmsRoot = getDownloadPath() + "/oraja_bms";
        String[] allBmsRoots = (bmsrootFromConfig.length > 0) ? bmsrootFromConfig : new String[]{defaultBmsRoot};

        try {
            String defaultDbPath = filesDir.getAbsolutePath() + "/songdata.db";
            bms.player.beatoraja.song.AndroidSQLiteSongDatabaseAccessor androidAccessor =
                    new bms.player.beatoraja.song.AndroidSQLiteSongDatabaseAccessor(this, defaultDbPath, allBmsRoots);
            bms.player.beatoraja.MainLoader.setSongDatabaseAccessor(androidAccessor);
        } catch (Throwable t) { Log.e(TAG, "DB init fail", t); return; }

        setupHighRefreshRate();
        inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);

        initialize(new BeatorajaGame(null, null, null, BMSPlayerMode.AUTOPLAY, true), config);
        setupSustainedPerformance();
    }

    private boolean pendingInitialization = false;

    /**
     * 将 assets/skin/ 目录复制到外部存储（如果目标不存在）
     */
    private void ensureSkinAssets(File filesDir) {
        File skinDir = new File(filesDir, "skin");
        if (skinDir.exists()) return; // 已存在，无需复制

        Log.i(TAG, "Copying skin assets to: " + skinDir.getAbsolutePath());
        skinDir.mkdirs();

        AssetManager am = getAssets();
        copyAssetFolder(am, "skin", skinDir);
    }

    /**
     * 递归复制 assets 目录到目标文件夹
     */
    private void copyAssetFolder(AssetManager am, String srcPath, File destDir) {
        try {
            String[] assets = am.list(srcPath);
            if (assets == null || assets.length == 0) {
                // 可能是文件而非目录，创建文件
                File destFile = new File(destDir, new java.io.File(srcPath).getName());
                copyAssetFile(am, srcPath, destFile);
                return;
            }
            for (String asset : assets) {
                String src = srcPath + "/" + asset;
                File destSub = new File(destDir, asset);
                if (!destSub.exists()) {
                    if (asset.contains(".") && !asset.endsWith("/")) {
                        // 文件
                        copyAssetFile(am, src, destSub);
                    } else {
                        // 目录
                        destSub.mkdirs();
                        copyAssetFolder(am, src, destSub);
                    }
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to copy asset folder: " + srcPath + " - " + e.getMessage());
        }
    }

    /**
     * 复制单个 asset 文件
     */
    private void copyAssetFile(AssetManager am, String srcPath, File destFile) {
        try (java.io.InputStream is = am.open(srcPath);
             java.io.FileOutputStream fos = new java.io.FileOutputStream(destFile)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) {
                fos.write(buf, 0, len);
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to copy asset file: " + srcPath + " - " + e.getMessage());
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && inputMethodManager != null && !isTextInputActive) {
            inputMethodManager.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!isTextInputActive) {
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
            if (inputMethodManager != null) inputMethodManager.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);
        }
        if (pendingInitialization && checkAndRequestStoragePermissions()) {
            pendingInitialization = false;
            recreate();
        } else {
            setupSustainedPerformance();
            setupHighRefreshRate();
        }
    }

    private void setupSustainedPerformance() {
        try {
            Window window = getWindow();
            if (window != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) window.setSustainedPerformanceMode(false);
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
                startActivityForResult(intent, 100);
                return false;
            }
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.RECORD_AUDIO}, 1);
                return false;
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 2);
                return false;
            }
        }
        return true;
    }

    @Override
    protected void onPause() {
        stopKeepAlive();
        super.onPause();
    }

    private String getDownloadPath() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();
        }
        return "/storage/emulated/0/Download";
    }

    private void setupHighRefreshRate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Window window = getWindow();
                android.view.Display display = window.getWindowManager().getDefaultDisplay();
                android.view.Display.Mode[] modes = display.getSupportedModes();
                android.view.Display.Mode bestMode = null;
                float highestRR = 0;
                for (android.view.Display.Mode m : modes) {
                    if (m.getRefreshRate() > highestRR) { highestRR = m.getRefreshRate(); bestMode = m; }
                }
                if (bestMode != null) {
                    WindowManager.LayoutParams params = window.getAttributes();
                    params.preferredDisplayModeId = bestMode.getModeId();
                    window.setAttributes(params);
                }
            }
        } catch (Throwable t) {}
    }

    public void setAndroidBackPressedFlag() {
        try {
            BeatorajaGame game = (BeatorajaGame) Gdx.app.getApplicationListener();
            game.getMainController().getInputProcessor().getKeyBoardInputProcesseor().simulateKeyPress(Keys.ESCAPE);
        } catch (Exception e) {}
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        lastUserTouchTime = SystemClock.uptimeMillis();
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            setAndroidBackPressedFlag();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (isSimulatingTouch) return true;
        lastUserTouchTime = SystemClock.uptimeMillis();
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) isUserTouching = true;
        else if (ev.getActionMasked() == MotionEvent.ACTION_UP) isUserTouching = false;
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (isSimulatingTouch) return true;
        lastUserTouchTime = SystemClock.uptimeMillis();
        return super.dispatchGenericMotionEvent(ev);
    }

    public void setTextInputActive(boolean active) {
        isTextInputActive = active;
        if (active) {
            stopKeepAlive();
            runOnUiThread(() -> getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING));
        } else {
            startKeepAlive();
            if (inputMethodManager != null) inputMethodManager.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);
            runOnUiThread(() -> getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING));
        }
    }

    @Override
    protected void onDestroy() {
        stopKeepAlive();
        super.onDestroy();
    }
}
