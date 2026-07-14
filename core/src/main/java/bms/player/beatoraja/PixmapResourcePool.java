package bms.player.beatoraja;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import com.badlogic.gdx.Application.ApplicationType;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;

/**
 * Pixmapリソースプール
 *
 * @author exch
 */
public class PixmapResourcePool extends ResourcePool<String, Pixmap> {

	/**
	 * 大小写不敏感文件名缓存。key=父目录绝对路径, value=(小写文件名→原始文件名)。
	 * 避免每次文件找不到时 O(n) 遍历整个目录做 equalsIgnoreCase。
	 */
	private static final ConcurrentHashMap<String, Map<String, String>> DIR_FILE_CACHE = new ConcurrentHashMap<>();

	private static final String[] IMAGE_EXTENSIONS = { ".png", ".jpg", ".jpeg", ".bmp", ".gif", ".tga" };

	/**
	 * 在指定目录下按大小写不敏感方式查找文件。
	 * 首次查找该目录时列出所有文件建立缓存映射，后续查找为 O(1)。
	 * @param parentPath 父目录绝对路径
	 * @param targetName 目标文件名（任意大小写）
	 * @return 匹配的实际文件名，未找到返回 null
	 */
	public static String findFileIgnoreCase(String parentPath, String targetName) {
		Map<String, String> fileMap = DIR_FILE_CACHE.computeIfAbsent(parentPath, k -> {
			Map<String, String> map = new HashMap<>();
			File dir = new File(k);
			File[] files = dir.listFiles();
			if (files != null) {
				for (File f : files) {
					map.put(f.getName().toLowerCase(), f.getName());
				}
			}
			return map;
		});
		return fileMap.get(targetName.toLowerCase());
	}

	/**
	 * 判断文件是否存在（兼容 Android internal/absolute 和桌面端）。
	 */
	private static boolean fileExists(String path) {
		if (path == null) return false;
		if (Gdx.app.getType() == ApplicationType.Android) {
			return Gdx.files.internal(path).exists() || Gdx.files.absolute(path).exists();
		} else {
			return new File(path).isFile();
		}
	}

	/**
	 * 按大小写不敏感方式查找文件的完整实际路径（Android 专用）。
	 * @return 实际路径，未找到返回 null
	 */
	private static String resolveCaseInsensitive(String path) {
		if (path == null) return null;
		File file = new File(path);
		File parentDir = file.getParentFile();
		if (parentDir == null || !parentDir.exists()) return null;
		String actualName = findFileIgnoreCase(parentDir.getAbsolutePath(), file.getName());
		if (actualName == null) return null;
		return new File(parentDir, actualName).getAbsolutePath().replace("\\", "/");
	}

	/**
	 * 尝试各种扩展名找到实际存在的图片文件。
	 * 优先级：原路径 → 大小写不敏感(Android) → 替换扩展名 → 替换扩展名+大小写不敏感(Android)
	 * @param path 带扩展名的原始路径
	 * @return 第一个存在的文件路径，都找不到返回 null
	 */
	public static String findImagePath(String path) {
		if (path == null) return null;
		path = path.replace("\\", "/");

		// 1. 检查原路径
		if (fileExists(path)) return path;

		// 2. 大小写不敏感（Android）
		if (Gdx.app.getType() == ApplicationType.Android) {
			String ci = resolveCaseInsensitive(path);
			if (ci != null && fileExists(ci)) return ci;
		}

		// 3. 去掉扩展名，逐个尝试支持的图片扩展名
		int dot = path.lastIndexOf('.');
		String base = (dot >= 0) ? path.substring(0, dot) : path;
		String origExt = (dot >= 0) ? path.substring(dot).toLowerCase() : "";

		for (String ext : IMAGE_EXTENSIONS) {
			if (ext.equals(origExt)) continue;
			String candidate = base + ext;
			if (fileExists(candidate)) return candidate;
			// 大小写不敏感（Android）
			if (Gdx.app.getType() == ApplicationType.Android) {
				String ci = resolveCaseInsensitive(candidate);
				if (ci != null && fileExists(ci)) return ci;
			}
		}
		// 最后试 .cim
		if (!".cim".equals(origExt)) {
			String candidate = base + ".cim";
			if (fileExists(candidate)) return candidate;
			if (Gdx.app.getType() == ApplicationType.Android) {
				String ci = resolveCaseInsensitive(candidate);
				if (ci != null && fileExists(ci)) return ci;
			}
		}

		return null;
	}

	public PixmapResourcePool() {
		super(1);
	}

	public PixmapResourcePool(int maxgen) {
		super(maxgen);
	}

	@Override
	protected Pixmap load(String path) {
		final Pixmap pixmap = loadPicture(path);
		if (pixmap != null) {
			Pixmap converted = convert(pixmap);
			if (converted != pixmap) {
				pixmap.dispose();
			}
			return scaleDown(converted);
		}
		return null;
	}

	/**
	 * Pixmapをload時に変換する。
	 *
	 * @param pixmap
	 * @return
	 */
	protected Pixmap convert(Pixmap pixmap) {
		return pixmap;
	}

	@Override
	protected void dispose(Pixmap resource) {
		resource.dispose();
	}

	private static float getDownscaleFactor() {
		String prop = System.getProperty("beatoraja.downscale");
		if (prop == null || prop.isEmpty()) return 1.0f;
		try {
			return Float.parseFloat(prop);
		} catch (NumberFormatException e) {
			return 1.0f;
		}
	}

	private static Pixmap scaleDown(Pixmap original) {
		float scale = getDownscaleFactor();
		if (scale >= 1.0f || original == null) return original;
		int newW = Math.max(1, Math.round(original.getWidth() * scale));
		int newH = Math.max(1, Math.round(original.getHeight() * scale));
		if (newW == original.getWidth() && newH == original.getHeight()) return original;
		Pixmap scaled = new Pixmap(newW, newH, original.getFormat());
		scaled.drawPixmap(original, 0, 0, original.getWidth(), original.getHeight(), 0, 0, newW, newH);
		original.dispose();
		return scaled;
	}

	/**
	 * 指定のパスで表現されるファイルを読み込む
	 * @param path イメージファイルのパス
	 * @return イメージ。読めなかった場合またはpathがファイルでない場合はnullを返す
	 */
	public static Pixmap loadPicture(String path) {
		if (path == null) return null;
		path = path.replace("\\", "/");

		String actualPath = findImagePath(path);
		if (actualPath == null) {
			Logger.getGlobal().warning("Image file not found: " + path);
			return null;
		}

		Pixmap tex = null;
		try {
			if (actualPath.endsWith(".cim")) {
				tex = PixmapIO.readCIM(Gdx.files.internal(actualPath));
			} else {
				if (Gdx.app.getType() == ApplicationType.Android && actualPath.startsWith("/")) {
					tex = new Pixmap(Gdx.files.absolute(actualPath));
				} else {
					tex = new Pixmap(Gdx.files.internal(actualPath));
				}
			}
		} catch (Throwable e) {
			if (Gdx.app.getType() == ApplicationType.Android) {
				try {
					tex = new Pixmap(Gdx.files.absolute(actualPath));
				} catch (Exception e2) {
					Logger.getGlobal().warning("BGAファイル読み込み失敗。" + e2.getMessage() + " path: " + actualPath);
				}
			} else {
				Logger.getGlobal().warning("BGAファイル読み込み失敗。" + e.getMessage() + " path: " + actualPath);
			}
		}
		if (tex == null && Gdx.app.getType() != ApplicationType.Android) {
			Logger.getGlobal().warning("BGAファイル読み込み再試行:" + path);
			try {
				java.io.File f = new java.io.File(actualPath);
				Class<?> imageIOClass = Class.forName("javax.imageio.ImageIO");
				java.lang.reflect.Method readMethod = imageIOClass.getMethod("read", java.io.File.class);
				Object bi = readMethod.invoke(null, f);

				if (bi != null) {
					Class<?> bufferedImageClass = Class.forName("java.awt.image.BufferedImage");
					int width = (int) bufferedImageClass.getMethod("getWidth").invoke(bi);
					int height = (int) bufferedImageClass.getMethod("getHeight").invoke(bi);
					java.lang.reflect.Method getRGBMethod = bufferedImageClass.getMethod("getRGB", int.class, int.class);

					tex = new Pixmap(width, height, Pixmap.Format.RGBA8888);
					for (int x = 0; x < width; x++) {
						for (int y = 0; y < height; y++) {
							int rgb = (int) getRGBMethod.invoke(bi, x, y);
							tex.drawPixel(x, y, (rgb << 8 | 0x000000ff));
						}
					}
				}
			} catch (Throwable e) {
				Logger.getGlobal().warning("BGAファイル読み込み失敗。" + e.getMessage());
			}
		}

		return tex;
	}
}
