package bms.player.beatoraja;

import java.io.File;
import java.util.logging.Logger;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;

/**
 * Pixmapリソースプール
 *
 * @author exch
 */
public class PixmapResourcePool extends ResourcePool<String, Pixmap> {

	public PixmapResourcePool() {
		super(1);
	}

	public PixmapResourcePool(int maxgen) {
		super(maxgen);
	}

	@Override
	protected Pixmap load(String path) {
		final Pixmap pixmap = loadPicture(path);
		return pixmap != null ? convert(pixmap) : null;
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

	/**
	 * 指定のパスで表現されるファイルを読み込む
	 * @param path イメージファイルのパス
	 * @return イメージ。読めなかった場合またはpathがファイルでない場合はnullを返す
	 */
	public static Pixmap loadPicture(String path) {
		if (path == null) return null;
		// 统一路径分隔符，防止 Android 无法识别
		path = path.replace("\\", "/");
		Pixmap tex = null;
		boolean exists = false;
		String actualPath = path;
		if (com.badlogic.gdx.Gdx.app.getType() == com.badlogic.gdx.Application.ApplicationType.Android) {
			exists = com.badlogic.gdx.Gdx.files.internal(path).exists() || com.badlogic.gdx.Gdx.files.absolute(path).exists();

			// Android 大小写敏感问题修复：如果文件找不到，尝试忽略大小写在目录中搜索
			if (!exists) {
				java.io.File file = new java.io.File(path);
				java.io.File parentDir = file.getParentFile();
				if (parentDir != null && parentDir.exists()) {
					String fileName = file.getName();
					java.io.File[] files = parentDir.listFiles();
					if (files != null) {
						for (java.io.File candidate : files) {
							if (candidate.getName().equalsIgnoreCase(fileName)) {
								actualPath = candidate.getAbsolutePath().replace("\\", "/");
								// On Android, absolute paths from external storage should use Gdx.files.absolute(), not internal()
								if (actualPath.startsWith("/") && !actualPath.startsWith("/data/") && !actualPath.startsWith("/app/")) {
									exists = com.badlogic.gdx.Gdx.files.absolute(actualPath).exists();
								} else {
									exists = com.badlogic.gdx.Gdx.files.internal(actualPath).exists() || com.badlogic.gdx.Gdx.files.absolute(actualPath).exists();
								}
								if (exists) {
									break;
								}
							}
						}
					}
				}
			}
		} else {
			exists = new File(path).isFile();
		}

		if(!exists) {
			java.util.logging.Logger.getGlobal().warning("Image file not found: " + path);
			return tex;
		}

		try {
			if(actualPath.endsWith(".cim")) {
				tex = PixmapIO.readCIM(com.badlogic.gdx.Gdx.files.internal(actualPath));
			} else {
				// Try internal first (for assets), but if actualPath is absolute on Android, use absolute
				if (com.badlogic.gdx.Gdx.app.getType() == com.badlogic.gdx.Application.ApplicationType.Android
						&& actualPath.startsWith("/")) {
					tex = new Pixmap(com.badlogic.gdx.Gdx.files.absolute(actualPath));
				} else {
					tex = new Pixmap(com.badlogic.gdx.Gdx.files.internal(actualPath));
				}
			}
		} catch (Throwable e) {
			if (com.badlogic.gdx.Gdx.app.getType() == com.badlogic.gdx.Application.ApplicationType.Android) {
				// Android 上如果 internal 失败，尝试 absolute
				try {
					tex = new Pixmap(com.badlogic.gdx.Gdx.files.absolute(actualPath));
				} catch (Exception e2) {
					Logger.getGlobal().warning("BGAファイル読み込み失敗。" + e2.getMessage() + " path: " + actualPath);
				}
			} else {
				Logger.getGlobal().warning("BGAファイル読み込み失敗。" + e.getMessage() + " path: " + actualPath);
			}
		}
		if (tex == null && com.badlogic.gdx.Gdx.app.getType() != com.badlogic.gdx.Application.ApplicationType.Android) {
			Logger.getGlobal().warning("BGAファイル読み込み再試行:" + path);
			try {
				// Android 兼容性处理: 使用反射调用桌面端特有的 ImageIO 和 BufferedImage
				java.io.File f = new java.io.File(path);
				Class<?> imageIOClass = Class.forName("javax.imageio.ImageIO");
				java.lang.reflect.Method readMethod = imageIOClass.getMethod("read", java.io.File.class);
				Object bi = readMethod.invoke(null, f);

				if (bi != null) {
					Class<?> bufferedImageClass = Class.forName("java.awt.image.BufferedImage");
					int width = (int) bufferedImageClass.getMethod("getWidth").invoke(bi);
					int height = (int) bufferedImageClass.getMethod("getHeight").invoke(bi);
					java.lang.reflect.Method getRGBMethod = bufferedImageClass.getMethod("getRGB", int.class, int.class);

					tex = new Pixmap(width, height, Pixmap.Format.RGBA8888);
					for(int x = 0; x < width; x++) {
						for(int y = 0; y < height; y++) {
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
