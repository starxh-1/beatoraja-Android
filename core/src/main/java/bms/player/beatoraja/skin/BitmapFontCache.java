package bms.player.beatoraja.skin;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.utils.Array;

public class BitmapFontCache {
    static private final Map<File, CacheableBitmapFont> _cacheStore = new HashMap<>();

    static public class CacheableBitmapFont {
        public BitmapFont.BitmapFontData fontData;
        public Array<TextureRegion> regions;
        public BitmapFont font;
        public float originalSize;
        public int type;
        public float pageWidth;
        public float pageHeight;
    }

    static public boolean Has(File path) {
        if (path == null)
            return false;

        return _cacheStore.containsKey(path);
    }

    static public void Set(File path, CacheableBitmapFont font) {
        _cacheStore.put(path, font);
    }

    static public CacheableBitmapFont Get(File path) {
        return _cacheStore.get(path);
    }

    /**
     * 清空所有缓存并 dispose 缓存中的 BitmapFont。
     * 必须在 Android OpenGL 上下文重建（resume）时调用，
     * 否则缓存中的 TextureRegion 仍引用已销毁的 GPU 纹理，导致 .fnt 渲染失败。
     */
    static public void invalidate() {
        for (CacheableBitmapFont c : _cacheStore.values()) {
            if (c != null && c.font != null) {
                try { c.font.dispose(); } catch (Throwable ignore) {}
            }
        }
        _cacheStore.clear();
    }
}
