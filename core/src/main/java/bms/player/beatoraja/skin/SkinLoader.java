package bms.player.beatoraja.skin;

import bms.player.beatoraja.*;
import bms.player.beatoraja.skin.json.JSONSkinLoader;
import bms.player.beatoraja.skin.lr2.LR2SkinCSVLoader;
import bms.player.beatoraja.skin.lr2.LR2SkinHeaderLoader;
import bms.player.beatoraja.skin.lua.LuaSkinLoader;
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
            if (currentPath.equalsIgnoreCase(key) || currentPath.toLowerCase().endsWith("/" + key.toLowerCase())) {
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
            String ext = imagepath.substring(imagepath.lastIndexOf("*") + 1).toLowerCase();
            if(imagepath.contains("|")) {
                if(imagepath.length() > imagepath.lastIndexOf('|') + 1) {
                    ext = imagepath.substring(imagepath.lastIndexOf("*") + 1, imagepath.indexOf('|')) + imagepath.substring(imagepath.lastIndexOf('|') + 1);
                } else {
                    ext = imagepath.substring(imagepath.lastIndexOf("*") + 1, imagepath.indexOf('|'));
                }
            }
            ext = ext.toLowerCase();

            String dirPath = imagepath.substring(0, imagepath.lastIndexOf('/'));
            com.badlogic.gdx.files.FileHandle dirHandle;

            if (com.badlogic.gdx.Gdx.app.getType() == com.badlogic.gdx.Application.ApplicationType.Android && !dirPath.startsWith("/")) {
                String root = System.getProperty("beatoraja.root", ".");
                dirHandle = Gdx.files.absolute(new File(root, dirPath).getAbsolutePath());
            } else {
                dirHandle = Gdx.files.internal(dirPath);
                if (!dirHandle.exists()) dirHandle = Gdx.files.absolute(dirPath);
            }

            if (dirHandle.exists() && dirHandle.isDirectory()) {
                Array<com.badlogic.gdx.files.FileHandle> l = new Array<>();
                for (com.badlogic.gdx.files.FileHandle subfile : dirHandle.list()) {
                    if (subfile.path().toLowerCase().endsWith(ext)) {
                        l.add(subfile);
                    }
                }
                if (l.size > 0) {
                    imagefile = l.get((int) (Math.random() * l.size)).file();
                }
            }
        }
        return imagefile;
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
        boolean isAbsolute = path.startsWith("/");
        File f = new File(path);
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
