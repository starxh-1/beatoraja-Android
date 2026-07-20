package bms.player.beatoraja.skin;

import bms.player.beatoraja.skin.property.StringProperty;
import bms.player.beatoraja.skin.property.StringPropertyFactory;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.math.Rectangle;

import bms.player.beatoraja.skin.Skin.SkinObjectRenderer;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.GdxRuntimeException;

/**
 * フォントデータをソースとして持つスキン用テキスト
 *
 * @author exch
 */
public final class SkinTextFont extends SkinText {

    /**
     * ビットマップフォント
     */
    private BitmapFont font;

    private GlyphLayout layout;

    private FreeTypeFontGenerator generator;
    private FreeTypeFontGenerator.FreeTypeFontParameter parameter;
    private String preparedFonts;

    /**
     * 记录此实例使用的字体路径，用于 dispose() 时从缓存中精确移除，
     * 以及在 resume() 后重新加载时使用。
     */
    private String cachedFontPath;

    /**
     * Static cache for FreeTypeFontGenerator - cache by (fontpath) to avoid repeated I/O opening the font file
     * Key: fontpath
     * Value: opened FreeTypeFontGenerator (already read from disk/assets)
     *
     * 注意：FreeTypeFontGenerator 本身不依赖 OpenGL 上下文，但由它生成的 BitmapFont
     * 的底层 Texture 在 OpenGL 上下文丢失后会失效。每次 resume() 时需通过
     * invalidateGeneratorCache() 清空缓存，让所有 SkinTextFont 在下次渲染时重新
     * 调用 generateFont() 生成新的 Texture，确保字体纹理与当前 GL 上下文绑定。
     */
    private static final ConcurrentHashMap<String, FreeTypeFontGenerator> generatorCache =
        new ConcurrentHashMap<>();

    /**
     * 全局缓存代数计数器。每次 invalidateGeneratorCache() 时递增。
     * 各实例通过比较 instanceGeneration 与此值，判断自身 generator 是否已失效。
     */
    private static volatile int cacheGeneration = 0;

    /**
     * 此实例获取 generator 时的缓存代数。若与 cacheGeneration 不一致，
     * 说明 generator 已被 invalidateGeneratorCache() dispose，需要重建。
     */
    private int instanceGeneration = 0;

    private final Color shadowcolor = new Color();

    public SkinTextFont(String fontpath, int cycle, int size, int shadow) {
        this(fontpath, cycle, size, shadow, StringPropertyFactory.getStringProperty(-1));
    }

    public SkinTextFont(String fontpath, int cycle, int size, int shadow, StringProperty property) {
    	super(property);
    	try {
            // 规范化路径，处理 .. 和 . 等相对路径符号，确保 Android AssetManager 能解析
            String normalizedPath = fontpath;
            try {
                String tmp = normalizePath(fontpath);
                normalizedPath = tmp;
            } catch (Exception e) {
                Gdx.app.error("FontDebug", "Path normalize failed for: " + fontpath + ", keeping original", e);
            }
            // [DEBUG] 暴露 FileHandle 路径和 exists 状态
            // 注意：Gdx.files.internal() 只适用于 APK 内置 assets，对外部存储的绝对路径始终返回 exists:false
            // 因此对于绝对路径（以 / 开头），直接使用 absolute 路径加载以避免误导性日志
            com.badlogic.gdx.files.FileHandle fontFile;
            if (normalizedPath.startsWith("/") || fontpath.startsWith("/")) {
                // 绝对路径：直接使用 absolute() 加载
                fontFile = com.badlogic.gdx.Gdx.files.absolute(normalizedPath);
                Gdx.app.log("FontDebug", "Absolute path font: " + fontFile.path() + ", exists: " + fontFile.exists() + ", size=" + size);
            } else {
                // 相对路径：先尝试 internal（用于 assets 内路径），失败则 fallback 到 absolute，
                // 最后 fallback 到 beatoraja.root 相对路径（Android 外部存储上的皮肤目录）
                fontFile = com.badlogic.gdx.Gdx.files.internal(normalizedPath);
                Gdx.app.log("FontDebug", "Trying to load font from: " + fontFile.path() + ", exists: " + fontFile.exists() + ", size=" + size);
                if (!fontFile.exists()) {
                    // 尝试 absolute 路径作为备选
                    com.badlogic.gdx.files.FileHandle fontFileAbs = com.badlogic.gdx.Gdx.files.absolute(normalizedPath);
                    Gdx.app.log("FontDebug", "Internal not found, trying absolute: " + fontFileAbs.path() + ", exists: " + fontFileAbs.exists());
                    if (fontFileAbs.exists()) {
                        fontFile = fontFileAbs;
                    } else {
                        // Android 上皮肤文件在外部存储，相对路径需要相对于 beatoraja.root 解析
                        String root = System.getProperty("beatoraja.root", null);
                        if (root != null) {
                            String rootResolved = root + "/" + normalizedPath;
                            // 规范化路径（去除 ./ 和重复斜杠）
                            rootResolved = rootResolved.replace("\\", "/").replaceAll("/+", "/");
                            com.badlogic.gdx.files.FileHandle fontFileRoot = com.badlogic.gdx.Gdx.files.absolute(rootResolved);
                            Gdx.app.log("FontDebug", "Absolute not found, trying beatoraja.root: " + fontFileRoot.path() + ", exists: " + fontFileRoot.exists());
                            if (fontFileRoot.exists()) {
                                fontFile = fontFileRoot;
                                // 更新 normalizedPath 以便后续缓存使用正确的路径
                                normalizedPath = rootResolved;
                            }
                        }
                    }
                }
            }
            // Check cache first - avoid repeated I/O opening the same font file
            generator = generatorCache.get(normalizedPath);
            if(generator == null) {
                generator = new FreeTypeFontGenerator(fontFile);
                generatorCache.put(normalizedPath, generator);
            }
            cachedFontPath = normalizedPath;
            instanceGeneration = cacheGeneration;
            parameter = new FreeTypeFontGenerator.FreeTypeFontParameter();
            parameter.characters = "";
//            this.setCycle(cycle);
            // 防止 size 为 0 或负数导致 FreeType 生成异常
            parameter.size = (size > 0 ? size : 16);
            if (size <= 0) {
                Gdx.app.error("FontDebug", "Invalid font size: " + size + " for path: " + fontpath + ", using 16");
            }
            setShadowOffset(new Vector2(shadow, shadow));
    	} catch (Throwable e) {
    		Gdx.app.error("FontError", "Failed to load font: " + fontpath + " size=" + size, e);
    		Logger.getGlobal().warning("Skin Font読み込み失敗: " + fontpath + " - " + e.getMessage());
    		e.printStackTrace();
    		// Try fallback font
    		try {
    			String fallbackPath = "font/VL-Gothic-Regular.ttf";
    			com.badlogic.gdx.files.FileHandle fallbackFile = bms.player.beatoraja.MainController.resolveFontFileHandle(fallbackPath);
    			if (fallbackFile == null) {
    				fallbackFile = Gdx.files.internal(fallbackPath);
    			}
    			Gdx.app.log("FontDebug", "Fallback font: " + fallbackFile.path() + ", exists: " + fallbackFile.exists());
    			generator = generatorCache.get(fallbackPath);
    			if(generator == null) {
    				generator = new FreeTypeFontGenerator(fallbackFile);
    				generatorCache.put(fallbackPath, generator);
    			}
                cachedFontPath = fallbackPath;
                instanceGeneration = cacheGeneration;
                parameter = new FreeTypeFontGenerator.FreeTypeFontParameter();
                parameter.characters = "";
                parameter.size = (size > 0 ? size : 16);
                setShadowOffset(new Vector2(shadow, shadow));
                Gdx.app.log("FontDebug", "Using fallback font: font/VL-Gothic-Regular.ttf");
                Logger.getGlobal().info("フォールバックフォントを使用: font/VL-Gothic-Regular.ttf");
    		} catch (Throwable e2) {
    			Gdx.app.error("FontError", "Fallback font also failed: font/VL-Gothic-Regular.ttf", e2);
    			Logger.getGlobal().warning("フォールバックフォントも読み込み失敗: font/VL-Gothic-Regular.ttf - " + e2.getMessage());
    			e2.printStackTrace();
    		}
    	}
    }

    /**
     * 清空全部 generator 缓存，并 dispose 所有已缓存的 FreeTypeFontGenerator。
     * 应在 Android OpenGL 上下文重建后（即 resume 阶段）调用，
     * 确保下次 prepareFont/prepareText 时重新创建与新 GL 上下文绑定的字体纹理。
     *
     * 注意：此方法只释放 generator 本身（CPU 侧 FreeType face），
     * 不触碰各 SkinTextFont 实例的 font 字段（BitmapFont），
     * 那部分由各实例的 prepareFont/prepareText 在下次调用时自行重建。
     */
    public static void invalidateGeneratorCache() {
        Gdx.app.log("FontDebug", "Invalidating FreeTypeFontGenerator cache, size=" + generatorCache.size());
        for (FreeTypeFontGenerator gen : generatorCache.values()) {
            try {
                gen.dispose();
            } catch (Throwable ignore) {}
        }
        generatorCache.clear();
        cacheGeneration++;
    }

    public boolean validate() {
    	if(generator == null || instanceGeneration != cacheGeneration) {
    		return false;
    	}
    	return super.validate();
    }

    /**
     * 若 generator 已被缓存清除（resume 后），则重新从缓存/文件加载。
     * 调用 prepareFont/prepareText 之前必须确保 generator 不为 null。
     */
    private void ensureGenerator() {
        // 检查代数一致性：若 cacheGeneration 已递增，说明旧 generator 已被 dispose
        if (generator != null && instanceGeneration != cacheGeneration) {
            generator = null;
            preparedFonts = null;
            if (font != null) {
                font.dispose();
                font = null;
            }
        }
        if (generator != null) return;
        if (cachedFontPath == null) return;
        try {
            generator = generatorCache.get(cachedFontPath);
            if (generator == null) {
                com.badlogic.gdx.files.FileHandle fontFile = Gdx.files.internal(cachedFontPath);
                if (!fontFile.exists()) {
                    fontFile = Gdx.files.absolute(cachedFontPath);
                }
                // Android 上皮肤字体可能在外部存储，需相对于 beatoraja.root 解析
                if (!fontFile.exists()) {
                    String root = System.getProperty("beatoraja.root", null);
                    if (root != null) {
                        String rootResolved = (root + "/" + cachedFontPath).replace("\\", "/").replaceAll("/+", "/");
                        fontFile = Gdx.files.absolute(rootResolved);
                    }
                }
                if (fontFile.exists()) {
                    generator = new FreeTypeFontGenerator(fontFile);
                    generatorCache.put(cachedFontPath, generator);
                    Gdx.app.log("FontDebug", "Re-created generator after resume: " + cachedFontPath + " -> " + fontFile.path());
                } else {
                    Gdx.app.error("FontDebug", "Cannot re-create generator, file not found: " + cachedFontPath);
                }
            } else {
                Gdx.app.log("FontDebug", "Re-linked generator from cache after resume: " + cachedFontPath);
            }
            instanceGeneration = cacheGeneration;
        } catch (Throwable e) {
            Gdx.app.error("FontError", "ensureGenerator failed for: " + cachedFontPath, e);
        }
    }

    public void prepareFont(String text) {
        // 先确保 font 被清掉，准备重新生成
        if(font != null) {
            font.dispose();
            font = null;
        }
        // resume 后 generator 可能为 null，尝试重建
        ensureGenerator();
        if (generator == null) {
            Gdx.app.error("FontError", "prepareFont called but generator is null, text=" + text);
            return;
        }
        if (parameter == null) {
            Gdx.app.error("FontError", "prepareFont called but parameter is null, text=" + text);
            return;
        }
        try {
            parameter.characters = text;
            font = generator.generateFont(parameter);
            layout = new GlyphLayout(font, "");
            preparedFonts = text;
        } catch (Throwable e) {
    		Gdx.app.error("FontError", "Failed to generate font for text: " + text
    				+ ", size=" + parameter.size, e);
    		Logger.getGlobal().warning("Font準備失敗 : " + text + " - " + e.getMessage());
    		e.printStackTrace();
    	}
    }

	@Override
	protected void prepareText(String text) {
        // 若 generator 为 null（resume 后缓存被清空），强制重建。
        boolean needRebuild = (generator == null);

        // 若 text 包含 preparedFonts 中没有的新字符，则需要扩充字符集重新生成 font。
        // 这确保当 StringProperty 返回的文本内容变化时（如 skin select custom options 切换），
        // 新字符都能被正确渲染。
        if (!needRebuild && preparedFonts != null && text != null) {
            boolean hasNewChar = false;
            for (int i = 0; i < text.length(); i++) {
                if (preparedFonts.indexOf(text.charAt(i)) < 0) {
                    hasNewChar = true;
                    break;
                }
            }
            if (!hasNewChar) {
                // 所有字符都已包含在已生成的 font 中，无需重建
                return;
            }
            // 合并新旧字符集，避免旧字符丢失
            StringBuilder merged = new StringBuilder(preparedFonts);
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (preparedFonts.indexOf(c) < 0) {
                    merged.append(c);
                }
            }
            text = merged.toString();
        }

        if(font != null) {
            font.dispose();
            font = null;
        }
        preparedFonts = null;
        // resume 后 generator 可能为 null，尝试重建
        ensureGenerator();
        if (generator == null) {
            Gdx.app.error("FontError", "prepareText called but generator is null, text=" + text);
            return;
        }
        if (parameter == null) {
            Gdx.app.error("FontError", "prepareText called but parameter is null, text=" + text);
            return;
        }
        try {
            parameter.characters = text;
            font = generator.generateFont(parameter);
            layout = new GlyphLayout(font, "");
            preparedFonts = text;
    	} catch (Throwable e) {
    		Gdx.app.error("FontError", "Failed to generate font for text: " + text
    				+ ", size=" + parameter.size, e);
    		Logger.getGlobal().warning("Font準備失敗 : " + text + " - " + e.getMessage());
    		e.printStackTrace();
    	}
	}

	@Override
    public void draw(SkinObjectRenderer sprite, float offsetX, float offsetY) {
        if(font != null) {
            if (color.a == 0f) {
                return;
            }
            if (parameter.size <= 0) {
                Gdx.app.error("FontDebug", "Skipping draw: parameter.size=" + parameter.size + " for text: " + getText());
                return;
            }
            font.getData().setScale(region.height / parameter.size);

            sprite.setType(getFilter() != 0 ? SkinObjectRenderer.TYPE_LINEAR : SkinObjectRenderer.TYPE_NORMAL);

            final float x = (getAlign() == 2 ? region.x - region.width : (getAlign() == 1 ? region.x - region.width / 2 : region.x));
            if (angle != 0) {
                final float pivotX = region.x + centerx * region.width + offsetX;
                final float pivotY = region.y + centery * region.height + offsetY;
                if(!getShadowOffset().isZero()) {
                    shadowcolor.set(color.r / 2, color.g / 2, color.b / 2, color.a);
                    setLayout(shadowcolor, region);
                    sprite.draw(font, layout, x + getShadowOffset().x + offsetX,
                        region.y - getShadowOffset().y + offsetY + region.getHeight(),
                        pivotX, pivotY, angle);
                }
                setLayout(color, region);
                sprite.draw(font, layout, x + offsetX, region.y + offsetY + region.getHeight(),
                    pivotX, pivotY, angle);
            } else {
                if(!getShadowOffset().isZero()) {
                    shadowcolor.set(color.r / 2, color.g / 2, color.b / 2, color.a);
                    setLayout(shadowcolor, region);
                    sprite.draw(font, layout, x + getShadowOffset().x + offsetX, region.y - getShadowOffset().y + offsetY + region.getHeight());
                }
                setLayout(color, region);
                sprite.draw(font, layout, x + offsetX, region.y + offsetY + region.getHeight());
            }
        }
    }

    private void setLayout(Color c, Rectangle r) {
        if (isWrapping()) {
            layout.setText(font, getText(), c, r.getWidth(), ALIGN[getAlign()], true);
        } else {
            switch (getOverflow()) {
            	case OVERFLOW_OVERFLOW -> layout.setText(font, getText(), c, r.getWidth(), ALIGN[getAlign()], false);
            	case OVERFLOW_SHRINK -> {
            		layout.setText(font, getText(), c, r.getWidth(), ALIGN[getAlign()], false);
            		float actualWidth = layout.width;
            		if (actualWidth > r.getWidth()) {
            			font.getData().setScale(font.getData().scaleX * r.getWidth() / actualWidth, font.getData().scaleY);
            			layout.setText(font, getText(), c, r.getWidth(), ALIGN[getAlign()], false);
            		}
            	}
            	case OVERFLOW_TRUNCATE -> layout.setText(font, getText(), 0, getText().length(), c, r.getWidth(), ALIGN[getAlign()], false, "");
            }
        }
    }

    public void dispose() {
    	// Don't dispose the generator - it's cached globally and reused.
    	// Only dispose the generated bitmap font.
    	// Nullify the local reference to allow GC to reclaim if cache was cleared.
    	Optional.ofNullable(font).ifPresent(BitmapFont::dispose);
    	font = null;
    	generator = null;
    	setDisposed();
    }

    private static String normalizePath(String path) {
        if (path == null) return null;
        String p = path.replace("\\", "/");
        boolean isAbsolute = p.startsWith("/");
        String[] parts = p.split("/");
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (String part : parts) {
            if (part.equals("..") && !result.isEmpty() && !result.get(result.size()-1).equals("..")) {
                result.remove(result.size() - 1);
            } else if (!part.isEmpty() && !part.equals(".")) {
                result.add(part);
            }
        }
        StringBuilder sb = new StringBuilder();
        if (isAbsolute) sb.append("/");
        for (int i = 0; i < result.size(); i++) {
            if (i > 0) sb.append("/");
            sb.append(result.get(i));
        }
        return sb.toString();
    }
}
