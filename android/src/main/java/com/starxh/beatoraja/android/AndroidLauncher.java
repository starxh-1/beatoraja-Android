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
import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.badlogic.gdx.backends.android.AndroidAudio;
import com.badlogic.gdx.Input.Keys;
import com.starxh.beatoraja.BeatorajaGame;
import barsoosayque.libgdxoboe.OboeAudio;

import java.io.*;
import java.util.Locale;
import android.content.res.Configuration;
import android.content.res.Resources;
import org.json.JSONObject;

import bms.player.beatoraja.Config;
import bms.player.beatoraja.PlayerConfig;
import bms.player.beatoraja.BMSPlayerMode;

public class AndroidLauncher extends AndroidApplication {
    private static final String TAG = "AndroidLauncher";
    private static AndroidLauncher instance;
    private InputMethodManager inputMethodManager;
    private volatile boolean isTextInputActive = false;
    private OboeAudio oboeAudio;
    private int mSampleRate = 48000;
    private String mLanguage = "en";

    @Override
    public AndroidAudio createAudio(Context context, AndroidApplicationConfiguration config) {
        try {
            oboeAudio = new OboeAudio(context.getAssets(), mSampleRate);
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
    private boolean isSimulatingTouch = false;
    private boolean isWaitingForPermissionResult = false;

    private final Runnable keepAliveRunnable = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            if (isTextInputActive || isUserTouching || (now - lastUserTouchTime < 500)) {
                if (keepAliveHandler != null) keepAliveHandler.postDelayed(this, 500);
                return;
            }
            try {
                Window w = getWindow();
                if (w != null && w.getDecorView() != null) {
                    isSimulatingTouch = true;
                    float offsetX = 100f + (float) (Math.random() * 2.0);
                    float offsetY = 100f + (float) (Math.random() * 2.0);
                    MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, offsetX, offsetY, 0);
                    MotionEvent move = MotionEvent.obtain(now, now + 5, MotionEvent.ACTION_MOVE, offsetX + 1f, offsetY + 1f, 0);
                    MotionEvent up = MotionEvent.obtain(now, now + 10, MotionEvent.ACTION_UP, offsetX + 1f, offsetY + 1f, 0);
                    w.getDecorView().dispatchTouchEvent(down);
                    w.getDecorView().dispatchTouchEvent(move);
                    w.getDecorView().dispatchTouchEvent(up);
                    down.recycle();
                    move.recycle();
                    up.recycle();
                    isSimulatingTouch = false;
                }
            } catch (Throwable t) {
                isSimulatingTouch = false;
            }
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
            Log.i(TAG, "Detected 32-bit device, using 30FPS limit for MusicSelect and low-precision timer");
        } else {
            Log.i(TAG, "Detected 64-bit device, unlimited FPS and high-precision timer enabled");
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

                // Parse audio.sampleRate
                int audioIndex = content.indexOf("\"audio\"");
                if (audioIndex >= 0) {
                    int sampleRateIndex = content.indexOf("\"sampleRate\"", audioIndex);
                    if (sampleRateIndex >= 0) {
                        int colonIndex = content.indexOf(":", sampleRateIndex);
                        if (colonIndex >= 0) {
                            int start = colonIndex + 1;
                            while (start < content.length() && (content.charAt(start) == ' ' || start < content.length() && content.charAt(start) == '"')) start++;
                            int end = start;
                            while (end < content.length() && content.charAt(end) >= '0' && content.charAt(end) <= '9') end++;
                            if (end > start) mSampleRate = Integer.parseInt(content.substring(start, end));
                        }
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

        Gdx.input.setCatchKey(Keys.BACK, true);

        // initialize 之后立即设置高帧率（此时 Surface 已准备好）
        setupHighRefreshRate();
        // 重复调用几次，压制 dynamicfps 的限制
        for (int i = 0; i < 3; i++) {
            new Handler(Looper.getMainLooper()).postDelayed(this::setupHighRefreshRate, 500 * (i + 1));
        }

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
            String[] internalDirs = {"table", "songinfo", "player", "irconfig", "sound", "bgm"};
            for (String dir : internalDirs) {
                File d = new File(filesDir, dir);
                if (!d.exists()) {
                    d.mkdirs();
                    Log.i(TAG, "Created internal directory: " + d.getAbsolutePath());
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
            if (extractDir.exists() && extractDir.isDirectory() && extractDir.list() != null && extractDir.list().length > 0) {
                continue;
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
                        while ((len = is.read(buf)) > 0) os.write(buf, 0, len);
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
        if (mostCommon != null && maxCount > 1) {
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
        if (pendingInitialization) {
            if (checkAndRequestStoragePermissions()) {
                pendingInitialization = false;
                isWaitingForPermissionResult = false;
                recreate();
            }
        } else {
            setupSustainedPerformance();
            setupHighRefreshRate();
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
                    params.preferredRefreshRate = bestMode.getRefreshRate();
                    window.setAttributes(params);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && highestRR > 0) {
                    try {
                        java.lang.reflect.Method getSurfaceControl = android.view.Window.class.getMethod("getSurfaceControl");
                        Object surfaceControl = getSurfaceControl.invoke(window);
                        if (surfaceControl != null) {
                            java.lang.reflect.Method setFrameRate = surfaceControl.getClass().getMethod("setFrameRate", float.class, int.class);
                            setFrameRate.invoke(surfaceControl, highestRR, 0);
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) { Log.e(TAG, "setupHighRefreshRate fail", t); }
    }

    public void setAndroidBackPressedFlag() {
        try {
            BeatorajaGame game = (BeatorajaGame) Gdx.app.getApplicationListener();
            game.getMainController().getInputProcessor().getKeyBoardInputProcesseor().simulateKeyPress(Keys.ESCAPE);
        } catch (Exception ignored) {}
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        lastUserTouchTime = SystemClock.uptimeMillis();
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // Android 5.1 及以下版本增强拦截
            if (instance != null) {
                setAndroidBackPressedFlag();
                return true; // 强制消费掉，不给系统处理
            }
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
                    Gdx.app.exit();
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
        stopKeepAlive();
        super.onDestroy();
    }
}
