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

        // 首次启动时创建必要的目录
        createDefaultDirectories();

        // 检测并解压 skin zip 到外部存储
        ensureExternalSkinZip(filesDir);

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

        initialize(new BeatorajaGame(null, null, null, BMSPlayerMode.AUTOPLAY, true), config);
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
        if (!skinsDir.exists()) {
            skinsDir.mkdirs();
            Log.i(TAG, "Created skins directory: " + skinsDir.getAbsolutePath());
        }
    }

    /**
     * 检测外部 skin 并导入到 skin 目录
     * 检测 Download/beatoraja/skins/ 下的 zip 和文件夹
     * zip 解压后删除，文件夹移动后删除
     * 如果没有外部 skin，则复制内置 assets/skin/
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

                    // 如果已存在同名文件夹，跳过
                    if (destFile.exists() && destFile.list() != null && destFile.list().length > 0) {
                        Log.i(TAG, "Skin already exists, skip: " + destFile.getAbsolutePath());
                    } else {
                        // 先尝试直接移动
                        File destParent = destFile.getParentFile();
                        if (destParent != null && !destParent.exists()) destParent.mkdirs();
                        if (zip.renameTo(destFile)) {
                            Log.i(TAG, "Moved skin zip to: " + destFile.getAbsolutePath());
                        } else {
                            // 移动失败，解压后删除 zip
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

                    // 如果目标已存在同名文件夹，跳过
                    if (destSkinDir.exists()) {
                        Log.i(TAG, "Skin folder already exists, skip: " + destSkinDir.getAbsolutePath());
                    } else {
                        // 先尝试直接移动
                        if (skinFolder.renameTo(destSkinDir)) {
                            Log.i(TAG, "Moved skin folder to: " + destSkinDir.getAbsolutePath());
                        } else {
                            // 移动失败，复制后删除原文件夹
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

        // 没有外部 skin，走老路：复制内置 assets/skin/
        ensureSkinAssets(filesDir);
    }

    /**
     * 解压 skin zip 到目标目录
     * @return true if extracted successfully
     */
    private boolean extractSkinZip(File zipFile, File destDir) {
        destDir.mkdirs();
        String skinName = zipFile.getName().replace(".zip", "");
        File skinExtractDir = new File(destDir, skinName);

        // 如果已存在同名文件夹，跳过
        if (skinExtractDir.exists() && skinExtractDir.list() != null && skinExtractDir.list().length > 0) {
            Log.i(TAG, "Skin already extracted, skip: " + skinExtractDir.getAbsolutePath());
            return false;
        }

        Log.i(TAG, "Extracting skin: " + zipFile.getName() + " -> " + skinExtractDir.getAbsolutePath());
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(zipFile)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                File outFile = new File(skinExtractDir, entry.getName());
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
            Log.i(TAG, "Skin extracted successfully: " + skinExtractDir.getName());
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to extract skin zip: " + zipFile.getName(), e);
            return false;
        }
    }

    /**
     * 将外部 skin 文件夹移动到目标目录
     * @return true if moved successfully
     */
    private boolean moveSkinFolder(File skinFolder, File destDir) {
        destDir.mkdirs();
        File destSkinDir = new File(destDir, skinFolder.getName());

        // 如果目标已存在同名文件夹，跳过
        if (destSkinDir.exists()) {
            Log.i(TAG, "Skin folder already exists, skip: " + destSkinDir.getAbsolutePath());
            return false;
        }

        Log.i(TAG, "Moving skin folder: " + skinFolder.getAbsolutePath() + " -> " + destSkinDir.getAbsolutePath());
        try {
            copyDirectory(skinFolder, destSkinDir);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to move skin folder: " + skinFolder.getName(), e);
            return false;
        }
    }

    /**
     * 递归复制目录
     */
    private void copyDirectory(File src, File dest) throws IOException {
        if (!src.exists()) return;
        if (!src.isDirectory()) return;

        dest.mkdirs();
        File[] children = src.listFiles();
        if (children == null) return;

        for (File child : children) {
            File destChild = new File(dest, child.getName());
            if (child.isDirectory()) {
                copyDirectory(child, destChild);
            } else {
                try (InputStream is = new FileInputStream(child);
                     OutputStream os = new FileOutputStream(destChild)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = is.read(buf)) > 0) os.write(buf, 0, len);
                }
            }
        }
    }

    /**
     * 递归删除目录
     */
    private boolean deleteRecursive(File path) {
        if (path == null || !path.exists()) return false;
        if (path.isDirectory()) {
            File[] children = path.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        return path.delete();
    }

    /**
     * 将 assets/skin/ 目录复制到外部存储（如果目标不存在）
     */
    private void ensureSkinAssets(File filesDir) {
        File skinDir = new File(filesDir, "skin");
        if (skinDir.exists()) return;

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

    /**
     * Open a URL in external browser (called from skin events)
     * If message is provided, show a confirmation dialog first
     */
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

    private void openUrlDirect(String url) {
        try {
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open URL: " + url, e);
        }
    }

    @Override
    protected void onDestroy() {
        stopKeepAlive();
        super.onDestroy();
    }
}
