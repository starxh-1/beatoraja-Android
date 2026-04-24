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
import java.nio.file.*;

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
                Skin skin = sl.loadSkin(Paths.get(sc.getPath()), skinType, sc.getProperties());
                SkinLoader.resource.disposeOld();
                return skin;
            } else if (pathStr.endsWith(".luaskin")) {
                LuaSkinLoader loader = new LuaSkinLoader(state, resource.getConfig());
                Skin skin = loader.loadSkin(Paths.get(sc.getPath()), skinType, sc.getProperties());
                SkinLoader.resource.disposeOld();
                return skin;
            } else {
                LR2SkinHeaderLoader loader = new LR2SkinHeaderLoader(resource.getConfig());
                SkinHeader header = loader.loadSkin(Paths.get(sc.getPath()), state, sc.getProperties());
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
        imagepath = imagepath.replace("\\", "/");
        // Normalize ".." parent references for Android AssetManager compatibility
        // (Android assets do not resolve ".." in paths)
        try {
            String normalized = Paths.get(imagepath).normalize().toString().replace("\\", "/");
            if (!normalized.isEmpty()) {
                imagepath = normalized;
            }
        } catch (Exception e) {
            // fallback: keep original path
        }
        File imagefile = new File(imagepath);
        for (String key : filemap.keys()) {
            if (imagepath.startsWith(key)) {
                String foot = imagepath.substring(key.length());
                int lastAsterisk = imagepath.lastIndexOf('*');
                if (lastAsterisk != -1) {
                    imagefile = new File(
                            imagepath.substring(0, lastAsterisk) + filemap.get(key) + foot);
                } else {
                    imagefile = new File(filemap.get(key) + foot);
                }
                // System.out.println(imagefile.getPath());
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
            com.badlogic.gdx.files.FileHandle dirHandle = Gdx.files.internal(dirPath);
            if (!dirHandle.exists()) dirHandle = Gdx.files.absolute(dirPath);

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
                modifiedtime = java.nio.file.Files.getLastModifiedTime(java.nio.file.Paths.get(path)).toMillis() / 1000;
            }
        } catch (Exception e) {
        }
        try {
            String cim = path.substring(0, path.lastIndexOf('.')) + "__" + modifiedtime + ".cim";
            if(resource.exists(cim)) {
                Pixmap pixmap = resource.get(cim);
                return pixmap != null ? new Texture(pixmap, useMipMaps) : null;
            }

            if (Files.exists(Paths.get(cim))) {
                Pixmap pixmap = resource.get(cim);
                return pixmap != null ? new Texture(pixmap, useMipMaps) : null;
            } else if(usecim){
                Pixmap pixmap = resource.get(path);

                try (DirectoryStream<Path> paths = Files.newDirectoryStream(Paths.get(path).getParent())) {
                    for (Path p : paths) {
                        final String filename = p.toString();
                        if(filename.startsWith(path.substring(0, path.lastIndexOf('.')) + "__") && filename.endsWith(".cim")) {
                            Files.deleteIfExists(p);
                            break;
                        }
                    }
                } catch(Throwable e) {
                    e.printStackTrace();
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
}
