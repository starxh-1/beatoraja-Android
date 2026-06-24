package bms.player.beatoraja.skin;

import bms.player.beatoraja.*;
import bms.player.beatoraja.skin.json.JSONSkinLoader;
import bms.player.beatoraja.skin.lr2.LR2SkinCSVLoader;
import bms.player.beatoraja.skin.lr2.LR2SkinHeaderLoader;
import bms.player.beatoraja.skin.lua.LuaSkinLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ObjectMap;

import java.io.File;
import java.io.IOException;

/**
 * スキンローダー
 *
 * @author exch
 */
public abstract class SkinLoader {
    /**
     * スキンイメージのリソースプール
     */
    private static PixmapResourcePool resource;

    public static void initPixmapResourcePool(int gen) {
    	if(resource != null) {
    		resource.dispose();
    	}
    	resource = new PixmapResourcePool(gen);
    }

    /** 皮肤图片并行预加载线程池 */
    private static final ExecutorService IMAGE_PRELOAD_POOL = Executors.newFixedThreadPool(
        Math.max(2, Runtime.getRuntime().availableProcessors()), r -> {
        Thread t = new Thread(r, "SkinImagePreload");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY);
        return t;
    });

    /**
     * 批量并行预加载图片到 PixmapResourcePool，用于加速皮肤加载。
     * 所有路径会被提交到线程池并行解码，调用线程阻塞直到全部完成。
     */
    public static void preloadImages(Collection<String> paths) {
        if (paths == null || paths.isEmpty()) return;
        PixmapResourcePool pool = getResource();
        List<Future<?>> futures = new ArrayList<>(paths.size());
        for (String path : paths) {
            if (path == null || path.isEmpty() || pool.exists(path)) continue;
            futures.add(IMAGE_PRELOAD_POOL.submit(() -> {
                pool.get(path);
            }));
        }
        for (Future<?> f : futures) {
            try {
                f.get(30, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        }
    }

    /**
     * スキンデータを読み込む
     *
     * @param state
     * @param skinType スキンタイプ
     * @return
     */
    public static Skin load(MainState state, SkinType skinType) {
        Skin skin = load(state, skinType, state.resource.getPlayerConfig().getSkin()[skinType.getId()]);
        if(skin == null) {
            SkinConfig skinConfig = new SkinConfig();
            skinConfig.setPath(SkinConfig.Default.get(skinType).path);
            skinConfig.validate();
            skin = load(state, skinType, skinConfig);
        }
        return skin;
    }

    public static Skin load(MainState state, SkinType skinType, SkinConfig sc) {
        final PlayerResource resource = state.resource;
        try {
            String pathStr = sc.getPath().replace("\\", "/");
            if (pathStr.endsWith(".json")) {
                JSONSkinLoader sl = new JSONSkinLoader(state, resource.getConfig());
                Skin skin = sl.loadSkin(new File(sc.getPath()), skinType, sc.getProperties());
                SkinLoader.resource.disposeOld();
                return skin;
            } else if (pathStr.endsWith(".luaskin")) {
                LuaSkinLoader loader = new LuaSkinLoader(state, resource.getConfig());
                Skin skin = loader.loadSkin(new File(sc.getPath()), skinType, sc.getProperties());
                SkinLoader.resource.disposeOld();
                return skin;
            } else {
                LR2SkinHeaderLoader loader = new LR2SkinHeaderLoader(resource.getConfig());
                SkinHeader header = loader.loadSkin(new File(sc.getPath()), state, sc.getProperties());
                LR2SkinCSVLoader dloader = LR2SkinCSVLoader.getSkinLoader(skinType,  header.getResolution(), resource.getConfig());
                header.setSourceResolution(dloader.src);
                header.setDestinationResolution(dloader.dst);
                Skin skin = dloader.loadSkin(state, header, loader.getOption());
                SkinLoader.resource.disposeOld();
                return skin;
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return null;
    }

    public static PixmapResourcePool getResource() {
    	if(resource == null) {
    		initPixmapResourcePool(1);
    	}
        return resource;
    }

    public static File getPath(String imagepath, ObjectMap<String, String> filemap) {
        imagepath = imagepath.replace("\\", "/").replaceAll("/+", "/");
        // 只对非绝对路径去除前导 /，保留 Android 上的绝对路径
        if (imagepath.startsWith("/") && !imagepath.startsWith("/storage/") && !imagepath.startsWith("/Android/")) {
            imagepath = imagepath.substring(1);
        }

        try {
            String normalized = normalizePath(imagepath).replace("\\", "/");
            if (!normalized.isEmpty()) {
                imagepath = normalized;
            }
        } catch (Exception e) {}

        File imagefile = new File(imagepath);
        String currentPath = imagepath;
        for (String key : filemap.keys()) {
            // Normalize key the same way currentPath was normalized above, so that
            // getCanonicalPath() vs getAbsolutePath() differences don't break matching.
            String cmpKey = key.replace("\\", "/").replaceAll("/+", "/");
            try {
                String nk = normalizePath(cmpKey);
                if (nk != null && !nk.isEmpty()) {
                    cmpKey = nk.replace("\\", "/");
                }
            } catch (Exception ignore) {}

            boolean matched = currentPath.equalsIgnoreCase(cmpKey);
            if (!matched) {
                // Suffix match: try with both raw and normalized key
                matched = currentPath.toLowerCase().endsWith("/" + cmpKey.toLowerCase());
                if (!matched && cmpKey.startsWith("/")) {
                    // If key is absolute, try without leading / for suffix matching
                    matched = currentPath.toLowerCase().endsWith(cmpKey.toLowerCase());
                }
            }

            if (matched) {
                String value = filemap.get(key);
                if ("Random".equalsIgnoreCase(value)) {
                    break;
                }

                int lastAsterisk = currentPath.lastIndexOf('*');
                if (lastAsterisk != -1) {
                    imagefile = new File(currentPath.substring(0, lastAsterisk) + value);
                } else {
                    imagefile = new File(value);
                }
                // Cleared imagepath to prevent fallback random logic
                imagepath = "";
                break;
            }
        }

        if (imagepath.contains("*")) {
            // Determine whether this is a directory-level wildcard (e.g. "frame/SP/*/main.png")
            // or a file-level wildcard (e.g. "bomb/*.png")
            int firstStar = imagepath.indexOf('*');
            int slashAfterStar = imagepath.indexOf('/', firstStar);
            boolean isDirectoryWildcard = (slashAfterStar != -1);

            if (isDirectoryWildcard) {
                // Directory-level: "*" is a subdirectory name, e.g. "frame/SP/*/main.png"
                int baseEnd = imagepath.lastIndexOf('/', firstStar - 1);
                String basePath = (baseEnd > 0) ? imagepath.substring(0, baseEnd) : "";
                String afterWildcard = imagepath.substring(slashAfterStar); // e.g. "/main.png"

                // Extract the subdir matching extension (optional, e.g. "*|.png/default")
                String subdirExt = "";
                int pipeIdx = imagepath.indexOf('|', firstStar);
                if (pipeIdx > firstStar && pipeIdx < slashAfterStar) {
                    subdirExt = imagepath.substring(firstStar + 1, pipeIdx).toLowerCase();
                } else {
                    subdirExt = imagepath.substring(firstStar + 1, slashAfterStar).toLowerCase();
                }

                com.badlogic.gdx.files.FileHandle baseDir = resolveWildcardDir(basePath);

                if (baseDir != null && baseDir.exists() && baseDir.isDirectory()) {
                    java.util.List<com.badlogic.gdx.files.FileHandle> matchingDirs = new java.util.ArrayList<>();
                    for (com.badlogic.gdx.files.FileHandle sub : baseDir.list()) {
                        if (sub.isDirectory() && (subdirExt.isEmpty() || sub.name().toLowerCase().endsWith(subdirExt))) {
                            matchingDirs.add(sub);
                        }
                    }
                    if (!matchingDirs.isEmpty()) {
                        com.badlogic.gdx.files.FileHandle chosen = matchingDirs.get((int) (Math.random() * matchingDirs.size()));
                        String finalPath = chosen.path().replace("\\", "/") + afterWildcard;
                        com.badlogic.gdx.files.FileHandle finalFile = Gdx.files.absolute(finalPath);
                        if (finalFile.exists()) {
                            imagefile = new File(finalPath);
                        } else {
                            imagefile = new File(finalPath);
                            Gdx.app.log("SkinPath", "Dir wildcard: chosen dir but inner file missing: " + finalPath);
                        }
                    } else {
                        Gdx.app.log("SkinPath", "Dir wildcard: no matching subdirs in " + basePath + " (ext=" + subdirExt + ")");
                    }
                } else {
                    Gdx.app.log("SkinPath", "Dir wildcard: base dir not found: " + basePath);
                }
            } else {
                // File-level wildcard: e.g. "bomb/*.png"
                String ext = imagepath.substring(firstStar + 1).toLowerCase();
                if (ext.startsWith(".")) ext = ext.substring(1);
                if (imagepath.contains("|")) {
                    int pipePos = imagepath.indexOf('|', firstStar);
                    if (pipePos > firstStar) {
                        ext = imagepath.substring(firstStar + 1, pipePos).toLowerCase();
                        if (imagepath.length() > pipePos + 1) {
                            ext += imagepath.substring(pipePos + 1).toLowerCase();
                        }
                    }
                }

                String dirPath = imagepath.substring(0, imagepath.lastIndexOf('/'));
                com.badlogic.gdx.files.FileHandle dirHandle = resolveWildcardDir(dirPath);

                if (dirHandle != null && dirHandle.exists() && dirHandle.isDirectory()) {
                    Array<com.badlogic.gdx.files.FileHandle> matches = new Array<>();
                    for (com.badlogic.gdx.files.FileHandle sub : dirHandle.list()) {
                        String subName = sub.name().toLowerCase();
                        if (sub.isDirectory() || subName.endsWith(ext) || subName.endsWith("." + ext)) {
                            matches.add(sub);
                        }
                    }
                    // Prefer files over directories
                    Array<com.badlogic.gdx.files.FileHandle> files = new Array<>();
                    Array<com.badlogic.gdx.files.FileHandle> dirs = new Array<>();
                    for (com.badlogic.gdx.files.FileHandle m : matches) {
                        if (m.isDirectory()) dirs.add(m);
                        else files.add(m);
                    }
                    if (files.size > 0) {
                        imagefile = files.get((int) (Math.random() * files.size)).file();
                    } else if (dirs.size > 0) {
                        imagefile = dirs.get((int) (Math.random() * dirs.size)).file();
                    }
                }
            }
        }
        return imagefile;
    }

    /**
     * Resolve a directory path for wildcard scanning, with Android fallbacks.
     * Tries: absolute path → beatoraja.root + relative → internal (assets).
     */
    private static com.badlogic.gdx.files.FileHandle resolveWildcardDir(String dirPath) {
        boolean isAndroid = Gdx.app.getType() == com.badlogic.gdx.Application.ApplicationType.Android;
        String androidRoot = isAndroid ? System.getProperty("beatoraja.root", null) : null;

        // 1. If absolute path, try directly
        if (dirPath.startsWith("/")) {
            com.badlogic.gdx.files.FileHandle fh = Gdx.files.absolute(dirPath);
            if (fh.exists()) return fh;
        }

        // 2. On Android, try beatoraja.root + relative path
        if (androidRoot != null && !dirPath.startsWith("/")) {
            String resolved = (androidRoot + "/" + dirPath).replace("\\", "/").replaceAll("/+", "/");
            com.badlogic.gdx.files.FileHandle fh = Gdx.files.absolute(resolved);
            if (fh.exists()) return fh;
        }

        // 3. Try internal (APK assets)
        com.badlogic.gdx.files.FileHandle fhInt = Gdx.files.internal(dirPath);
        if (fhInt.exists()) return fhInt;

        // 4. Try as absolute path even without leading /
        com.badlogic.gdx.files.FileHandle fhAbs = Gdx.files.absolute(dirPath);
        if (fhAbs.exists()) return fhAbs;

        // 5. If the path looks like an absolute path missing its leading / (common on Android),
        //    try prepending / to make it absolute
        if (!dirPath.startsWith("/")
                && (dirPath.startsWith("storage/") || dirPath.startsWith("sdcard/")
                    || dirPath.startsWith("Android/data") || dirPath.startsWith("data/data"))) {
            com.badlogic.gdx.files.FileHandle fhSlash = Gdx.files.absolute("/" + dirPath);
            if (fhSlash.exists()) return fhSlash;
        }

        return Gdx.files.absolute(dirPath);
    }

    public static Texture getTexture(String path, boolean usecim) {
        return getTexture(path, usecim, false);
    }

    public static Texture getTexture(String path, boolean usecim, boolean useMipMaps) {
    	final PixmapResourcePool resource = SkinLoader.getResource();
        if(resource.exists(path)) {
            Pixmap pixmap = resource.get(path);
            return pixmap != null ? new Texture(pixmap, useMipMaps) : null;
        }
        long modifiedtime = 0;
        try {
            if (com.badlogic.gdx.Gdx.app.getType() != com.badlogic.gdx.Application.ApplicationType.Android) {
                modifiedtime = new File(path).lastModified() / 1000;
            }
        } catch (Exception e) {
        }
        try {
            String cim = path.substring(0, path.lastIndexOf('.')) + "__" + modifiedtime + ".cim";
            if(resource.exists(cim)) {
                Pixmap pixmap = resource.get(cim);
                return pixmap != null ? new Texture(pixmap, useMipMaps) : null;
            }

            if (new File(cim).exists()) {
                Pixmap pixmap = resource.get(cim);
                return pixmap != null ? new Texture(pixmap, useMipMaps) : null;
            } else if(usecim){
                Pixmap pixmap = resource.get(path);

                File parentDir = new File(path).getParentFile();
                if (parentDir != null && parentDir.isDirectory()) {
                    // 在 Android 上，避免全量 listFiles，它非常慢。
                    // 老旧 CIM 文件的清理在 Android 上通常不是必要的。
                    if (com.badlogic.gdx.Gdx.app.getType() != com.badlogic.gdx.Application.ApplicationType.Android) {
                        File[] files = parentDir.listFiles();
                        if (files != null) {
                            String prefix = path.substring(0, path.lastIndexOf('.')) + "__";
                            for (File f : files) {
                                String fname = f.getName();
                                if (fname.startsWith(prefix) && fname.endsWith(".cim")) {
                                    f.delete();
                                    break;
                                }
                            }
                        }
                    }
                }
                if (pixmap != null) {
                    PixmapIO.writeCIM(Gdx.files.local(cim), pixmap);
                    Texture tex = new Texture(pixmap, useMipMaps);
                    return tex;
                } else {
                    return null;
                }
            } else {
                Pixmap pixmap = resource.get(path);
                return pixmap != null ? new Texture(pixmap, useMipMaps) : null;
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return null;
    }

    public static String normalizePath(String path) {
        if (path == null || path.isEmpty()) return path;
        // Strip leading ./ (Windows-style current-directory prefix) so it doesn't
        // cause the path to be treated as relative when it's actually absolute
        String cleanPath = path.replace("\\", "/");
        while (cleanPath.startsWith("./")) {
            cleanPath = cleanPath.substring(2);
        }
        boolean isAbsolute = cleanPath.startsWith("/");
        File f = new File(cleanPath);
        // 优先用 getCanonicalPath 解析 .. 和符号链接，失败时降级到手动处理
        String abs;
        try {
            abs = f.getCanonicalPath();
        } catch (Exception e) {
            abs = f.getAbsolutePath();
        }
        // 简单的 ".." 处理（作为 fallback 保护）
        String[] parts = abs.replace("\\", "/").split("/");
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (String p : parts) {
            if (p.equals("..") && result.size() > 0 && !result.get(result.size()-1).equals("..")) {
                result.remove(result.size() - 1);
            } else if (!p.isEmpty()) {
                result.add(p);
            }
        }
        StringBuilder sb = new StringBuilder();
        if (isAbsolute) {
            sb.append("/");
        }
        for (int i = 0; i < result.size(); i++) {
            if (i > 0 || isAbsolute) sb.append("/");
            sb.append(result.get(i));
        }
        return sb.toString();
    }
}
