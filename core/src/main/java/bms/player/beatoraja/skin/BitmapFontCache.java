package bms.player.beatoraja.skin;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.utils.Array;

public class BitmapFontCache {
    static private final Map<File, CacheableBitmapFont> _cacheStore = new HashMap<>();
    static private int generation = 0;

    static public class CacheableBitmapFont {
        public BitmapFont.BitmapFontData fontData;
        public Array<TextureRegion> regions;
        public BitmapFont font;
        public float originalSize;
        public int type;
        public float pageWidth;
        public float pageHeight;
        public int generation;
    }

    static public boolean Has(File path) {
        if (path == null)
            return false;

        CacheableBitmapFont c = _cacheStore.get(path);
        return c != null && c.generation == generation;
    }

    static public void Set(File path, CacheableBitmapFont font) {
        font.generation = generation;
        _cacheStore.put(path, font);
    }

    static public CacheableBitmapFont Get(File path) {
        CacheableBitmapFont c = _cacheStore.get(path);
        return (c != null && c.generation == generation) ? c : null;
    }

    /**
     * 递增 generation 计数器，使所有缓存条目失效。
     * Android 上进程复用可能导致 static 缓存保留已销毁的 GPU 纹理，
     * invalidate() 确保下次 Has()/Get() 返回 false/null，触发重建。
     */
    static public void invalidate() {
        generation++;
        for (CacheableBitmapFont c : _cacheStore.values()) {
            if (c != null && c.font != null) {
                try { c.font.dispose(); } catch (Throwable ignore) {}
            }
        }
        _cacheStore.clear();
    }
}
