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

import bms.player.beatoraja.Config;
import bms.player.beatoraja.PlayerConfig;
import bms.player.beatoraja.BMSPlayerMode;

public class AndroidLauncher extends AndroidApplication {
    private static final String TAG = "AndroidLauncher";
    private static AndroidLauncher instance;
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
                    // 使用更加频繁且自然的微小滑动，避开屏幕边缘（防止触发系统通知栏/导航栏引起闪烁和分辨率变动）
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

        // 捕获 Android 系统的返回键，交由 LibGDX 的 InputProcessor 处理
        // 必须在 initialize() 之后调用，此时 Gdx.input 才被初始化
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
                    } else if (destFile.exists() && !destFile.isDirectory()) {
                        // 遗留 bug：之前被 renameTo 成了文件而不是目录，删掉它重新处理
                        Log.w(TAG, "Found legacy file instead of directory, deleting: " + destFile.getAbsolutePath());
                        destFile.delete();
                    }

                    if (!destFile.exists() || !destFile.isDirectory()) {
                        // 先尝试直接移动到临时 zip 路径
                        File destParent = destFile.getParentFile();
                        if (destParent != null && !destParent.exists()) destParent.mkdirs();
                        File tempZip = new File(internalSkinDir, skinName + ".zip");
                        if (zip.renameTo(tempZip)) {
                            Log.i(TAG, "Moved skin zip to: " + tempZip.getAbsolutePath());
                            // 然后解压
                            if (extractSkinZip(tempZip, internalSkinDir)) {
                                tempZip.delete();
                                Log.i(TAG, "Deleted skin zip after extract: " + zip.getName());
                            }
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
            // Find and strip common top-level prefix to avoid double-nesting
            String prefixToStrip = findCommonZipPrefix(zip, skinName);
            if (prefixToStrip != null) {
                Log.i(TAG, "Stripping top-level prefix: '" + prefixToStrip + "'");
            }

            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                String entryName = entry.getName();
                // Strip common prefix if found
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
            Log.i(TAG, "Skin extracted successfully: " + skinExtractDir.getName());
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to extract skin zip: " + zipFile.getName(), e);
            return false;
        }
    }

    /**
     * 检测并解压 songs 目录下的 zip 文件到 songs 子目录
     */
    private void ensureExternalSongZip() {
        File songsDir = new File(getDownloadPath(), BEATORAJA_BASE + "/" + SONGS_FOLDER);
        if (!songsDir.exists() || !songsDir.isDirectory()) return;

        File[] zipFiles = songsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".zip"));
        if (zipFiles == null || zipFiles.length == 0) return;

        Log.i(TAG, "Found " + zipFiles.length + " song zip(s) in: " + songsDir.getAbsolutePath());
        for (File zip : zipFiles) {
            String songName = zip.getName().replace(".zip", "");
            File extractDir = new File(songsDir, songName);

            // 只检查目标目录是否存在且有内容，如果已有内容则跳过解压（不删除 zip）
            if (extractDir.exists() && extractDir.isDirectory() && extractDir.list() != null && extractDir.list().length > 0) {
                Log.i(TAG, "Song already extracted, skip: " + extractDir.getAbsolutePath());
                // 不再删除 zip，保留以防用户需要重新安装
                continue;
            }

            Log.i(TAG, "Extracting song zip: " + zip.getName() + " -> " + extractDir.getAbsolutePath());
            if (extractSongZip(zip, extractDir)) {
                // 解压成功后删除 zip
                zip.delete();
                Log.i(TAG, "Deleted song zip after extract: " + zip.getName());
            }
        }
    }

    /**
     * 公开方法，供外部调用检测并解压 songs 目录下的 zip 文件
     * 可被 core 模块通过反射调用
     */
    public static void checkAndExtractSongZips() {
        if (instance != null) {
            instance.ensureExternalSongZip();
        }
    }

    /**
     * 解压 song zip 到目标目录（通常是 songs/songname/）
     * @return true if extracted successfully
     */
    private boolean extractSongZip(File zipFile, File destDir) {
        destDir.mkdirs();
        String zipBaseName = zipFile.getName().replace(".zip", "");
        Log.i(TAG, "Extracting song: " + zipFile.getName() + " -> " + destDir.getAbsolutePath());
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(zipFile)) {
            // Find and strip common top-level prefix to avoid double-nesting
            String prefixToStrip = findCommonZipPrefix(zip, zipBaseName);
            if (prefixToStrip != null) {
                Log.i(TAG, "Stripping top-level prefix: '" + prefixToStrip + "'");
            }

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
            Log.i(TAG, "Song extracted successfully: " + destDir.getName());
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to extract song zip: " + zipFile.getName(), e);
            return false;
        }
    }

    /**
     * Find the common top-level folder prefix among all zip entries.
     * If entries look like "ModernChic/default/..." and no other root, returns "ModernChic/".
     * Returns null if no common prefix found.
     */
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

        // Find most common top-level folder
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
        // If most common folder appears multiple times and matches zip base name, use it
        if (mostCommon != null && maxCount > 1) {
            String candidate = mostCommon.endsWith("/") ? mostCommon.substring(0, mostCommon.length() - 1) : mostCommon;
            if (candidate.equalsIgnoreCase(zipBaseName)) {
                return mostCommon;
            }
        }
        return null;
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
        if (pendingInitialization) {
            if (checkAndRequestStoragePermissions()) {
                // 权限已授予，重新初始化
                pendingInitialization = false;
                isWaitingForPermissionResult = false;
                recreate();
            }
            // 如果权限未授予，checkAndRequestStoragePermissions() 会启动 intent
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
            // RECORD_AUDIO is NOT requested here for Android 11+ to avoid unnecessary dialogs
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // ONLY READ/WRITE storage are mandatory. RECORD_AUDIO is removed from this list.
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Requesting mandatory storage permissions");
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
                return false;
            }
            // App will proceed without even checking RECORD_AUDIO
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
            // 用户从 MANAGE_APP_ALL_FILES_ACCESS_PERMISSION 设置页面返回
            // 直接调用 recreate 让 onCreate 重新检查权限状态
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
                    Log.i(TAG, "Supported mode: " + m.getPhysicalWidth() + "x" + m.getPhysicalHeight() + " @ " + m.getRefreshRate() + "Hz");
                    if (m.getRefreshRate() > highestRR) { highestRR = m.getRefreshRate(); bestMode = m; }
                }
                if (bestMode != null) {
                    WindowManager.LayoutParams params = window.getAttributes();
                    params.preferredDisplayModeId = bestMode.getModeId();
                    params.preferredRefreshRate = bestMode.getRefreshRate();
                    window.setAttributes(params);
                    Log.i(TAG, "High refresh rate requested: " + bestMode.getRefreshRate() + "Hz (modeId=" + bestMode.getModeId() + ")");
                }
                // 请求设备的最高刷新率，压制 dynamicfps 的限制 (Android 11+)
                // 通过 SurfaceControl.setFrameRate 反射调用告知系统使用最高刷新率
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && highestRR > 0) {
                    try {
                        java.lang.reflect.Method getSurfaceControl = android.view.Window.class.getMethod("getSurfaceControl");
                        Object surfaceControl = getSurfaceControl.invoke(window);
                        if (surfaceControl != null) {
                            java.lang.reflect.Method setFrameRate = surfaceControl.getClass().getMethod("setFrameRate", float.class, int.class);
                            setFrameRate.invoke(surfaceControl, highestRR, 0 /* FRAME_RATE_COMPATIBILITY_DEFAULT */);
                            Log.i(TAG, "SurfaceControl.setFrameRate(" + highestRR + ") called");
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "SurfaceControl.setFrameRate failed: " + t.getMessage());
                    }
                }
            }
        } catch (Throwable t) { Log.e(TAG, "setupHighRefreshRate fail", t); }
    }

    public void setAndroidBackPressedFlag() {
        try {
            // 在 Android 13+ 的 OnBackInvokedCallback 中，手动触发返回键事件
            // 让它流向 KeyBoardInputProcesseor.keyDown 进行重映射
            BeatorajaGame game = (BeatorajaGame) Gdx.app.getApplicationListener();
            game.getMainController().getInputProcessor().getKeyBoardInputProcesseor().simulateKeyPress(Keys.ESCAPE);
        } catch (Exception ignored) {}
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        lastUserTouchTime = SystemClock.uptimeMillis();
        // 移除旧的 onKeyDown 拦截逻辑，改用 LibGDX 的 setCatchKey(Keys.BACK) 机制
        // 这样可以确保 keyDown 事件能正常流向 InputProcessor
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

    private boolean isExitDialogShowing = false;

    /**
     * Check if the exit dialog is currently visible.
     */
    public boolean isExitDialogShowing() {
        return isExitDialogShowing;
    }

    /**
     * Show a native Android confirmation dialog before exiting the game.
     * Called via reflection from MusicSelectInputProcessor.
     */
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
