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

    /** 直近に「装配を許可した」フレーム ID（{@link #allowBuildThisFrame()} 用）。 */
    static private long lastBuildFrame = -1L;

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

    /**
     * このフレームで GL スレッドが .fnt フォントを 1 つ「装配」（Texture 生成 +
     * 字形への region 結び付け）してよいか。
     *
     * <p>🔴 装配は必ずフレーム単位で間引く。1 つの .fnt を装配するには
     * 全字形（実測 17114 個）を走査してページ Texture を丸ごとアップロードするので、
     * 1 回で数百 ms かかる。同じフレーム内に十数個まとめて装配すると GL スレッドの
     * 単一フレームが 5 秒を超える —— そして主スレッドは
     * {@code SurfaceView.surfaceChanged()} の中で「GL スレッドが resize を処理し終える」
     * のを同期待ちしているので、そのまま
     * {@code Input dispatching timed out}（ANR）になる。
     * （2026-10-12 LEGION Y700 で実際に ANR。スタックは
     * {@code SkinTextBitmapSource} の .fnt 読込中だった）
     *
     * <p>フレーム ID を使うので、描画ループが 1 フレームに何度呼んでも
     * 許可は 1 回だけ。GL コンテキストが無い（単体テスト・極初期）ときは
     * 間引かずに許可する。
     */
    static public boolean allowBuildThisFrame() {
        try {
            long frame = com.badlogic.gdx.Gdx.graphics.getFrameId();
            if (frame != lastBuildFrame) {
                lastBuildFrame = frame;
                return true;
            }
            return false;
        } catch (Throwable ignore) {
            return true;
        }
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
            if (c == null)
                continue;
            // 🔴 font.dispose() だけでは足りない。BitmapFont(data, regions, ...) で作った Font は
            // ページ Texture を所有しない（ownsTexture == false）ので、誰も解放しないまま
            // 参照が消える。.fnt 1 個につきページ 1 枚＝数 MB、皮膚は十数個の .fnt を参照し、
            // しかも resume のたびに invalidate() が走るので、放置すると GL メモリが単調に増える。
            // SkinLoader.getTexture() は毎回 new Texture して呼び出し元へ所有権を渡すため、
            // ここで解放して問題ない（Texture.dispose() は glHandle を 0 にするので多重呼び出しも安全）。
            if (c.font != null) {
                try { c.font.dispose(); } catch (Throwable ignore) {}
            }
            if (c.regions != null) {
                for (int i = 0; i < c.regions.size; i++) {
                    TextureRegion r = c.regions.get(i);
                    if (r != null && r.getTexture() != null) {
                        try { r.getTexture().dispose(); } catch (Throwable ignore) {}
                    }
                }
            }
        }
        _cacheStore.clear();
    }
}
