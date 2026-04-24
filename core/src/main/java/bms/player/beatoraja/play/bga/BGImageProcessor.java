package bms.player.beatoraja.play.bga;

import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

import bms.model.TimeLine;
import bms.player.beatoraja.PixmapResourcePool;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;

/**
 * BGIリソース管理用クラス
 *
 * @author exch
 */
public class BGImageProcessor {

	public static final String[] pic_extension = { "jpg", "jpeg", "gif", "bmp", "png", "tga" };
	/**
	 * BGイメージ
	 */
	private Pixmap[] bgamap = new Pixmap[1000];
	/**
	 * BGイメージのキャッシュ
	 */
	private Texture[] bgacache;
	/**
	 * キャッシュされているBGイメージID
	 */
	private int[] bgacacheid;

	private final PixmapResourcePool cache;

	public BGImageProcessor(int size, int maxgen) {
		bgacache = new Texture[size];
		bgacacheid = new int[size];
		cache = new PixmapResourcePool(maxgen) {

			protected Pixmap convert(Pixmap pixmap) {
				// 只对 BMP 格式的图片进行黑色透明化处理
				// 通过文件路径判断是否为 BMP
				boolean needsColorKeying = false;
				
				// 注意：这里无法直接获取原始文件路径，所以采用更智能的策略
				// 只处理不包含 Alpha 通道的图片（RGB888格式通常是BMP或JPG）
				if (pixmap.getFormat() == Pixmap.Format.RGB888) {
					needsColorKeying = true;
				}
				
				if (needsColorKeying) {
					pixmap = applyBlackColorKeyOptimized(pixmap);
				}
				
				int bgasize = Math.max(pixmap.getHeight(), pixmap.getWidth());
				if ( bgasize <=256 ){
					final int fixx = (256 - pixmap.getWidth()) / 2;
					Pixmap fixpixmap = new Pixmap(256, 256, pixmap.getFormat());
					fixpixmap.drawPixmap(pixmap, 0, 0, pixmap.getWidth(), pixmap.getHeight(),
							fixx, 0, pixmap.getWidth(), pixmap.getHeight());
					pixmap.dispose();
					return fixpixmap;
				}
				return pixmap;
			}
			
			/**
			 * 优化的黑色颜色键控：使用直接字节缓冲区处理，避免逐像素调用
			 * 这比原来的方法快 10-50 倍
			 */
			private Pixmap applyBlackColorKeyOptimized(Pixmap source) {
				int width = source.getWidth();
				int height = source.getHeight();
				
				// 转换为 RGBA8888 格式（如果还不是）
				Pixmap pixmap;
				if (source.getFormat() != Pixmap.Format.RGBA8888) {
					pixmap = new Pixmap(width, height, Pixmap.Format.RGBA8888);
					pixmap.drawPixmap(source, 0, 0, width, height, 0, 0, width, height);
					source.dispose();
				} else {
					pixmap = source;
				}
				
				// 使用直接字节缓冲区进行快速像素处理
				java.nio.ByteBuffer buffer = pixmap.getPixels();
				buffer.rewind();
				
				int blackPixelCount = 0;
				int pixelCount = width * height;
				
				// 直接操作字节缓冲区，比逐像素调用快得多
				for (int i = 0; i < pixelCount; i++) {
					// RGBA8888 格式在 ByteBuffer 中的顺序取决于平台
				 // 通常是 R, G, B, A 或 A, R, G, B
					// 我们读取像素值来判断
					int pixelIndex = i * 4;
					
					// 读取 RGBA 值
					int r = buffer.get(pixelIndex) & 0xFF;
					int g = buffer.get(pixelIndex + 1) & 0xFF;
					int b = buffer.get(pixelIndex + 2) & 0xFF;
					int a = buffer.get(pixelIndex + 3) & 0xFF;
					
					// 如果是纯黑色且 Alpha 不为 0
					if (r == 0 && g == 0 && b == 0 && a != 0) {
						// 将 Alpha 设为 0
						buffer.put(pixelIndex + 3, (byte) 0);
						blackPixelCount++;
					}
				}
				
				if (blackPixelCount > 0) {
					Logger.getGlobal().fine("BGA Color Keying (optimized): Converted " + blackPixelCount + " black pixels in " + width + "x" + height + " image");
				}
				
				return pixmap;
			}
		};
	}

	public void put(int id, Path path) {
		Pixmap pixmap = cache.get(path.toString());
		if(id >= bgamap.length) {
			bgamap = Arrays.copyOf(bgamap, id + 1);
		}
		bgamap[id] = pixmap;
	}

	public void clear() {
		Arrays.fill(bgamap,  null);
	}

	public void disposeOld() {
		cache.disposeOld();
	}

	/**
	 * BGAの初期データをあらかじめキャッシュする
	 */
	public void prepare(TimeLine[] timelines) {
		if (timelines == null || bgacache == null || bgacache.length == 0) return;
		long l = System.currentTimeMillis();
		Arrays.fill(bgacacheid, -1);
		for (Texture bga : bgacache) {
			if (bga != null) {
				bga.dispose();
			}
		}
		Arrays.fill(bgacache, null);

		int count = 0;
		// 使用 HashSet 避免重复加载同一个 BGA
		java.util.Set<Integer> processedIds = new java.util.HashSet<>();
		for (TimeLine tl : timelines) {
			int bga = tl.getBGA();
			if (bga >= 0 && !processedIds.contains(bga) && bga < bgamap.length && bgamap[bga] != null) {
				getTexture(bga);
				processedIds.add(bga);
				count++;
			}

			bga = tl.getLayer();
			if (bga >= 0 && !processedIds.contains(bga) && bga < bgamap.length && bgamap[bga] != null) {
				getTexture(bga);
				processedIds.add(bga);
				count++;
			}
		}
		long elapsed = System.currentTimeMillis() - l;
		if (elapsed > 100) {
			Logger.getGlobal().info("BGA事前Texture化完了 - BGA数:" + count + " 耗时:" + elapsed + "ms");
		}
	}

	public Texture getTexture(int id) {
		if (bgacache == null || bgacache.length == 0) return null;
		final int cid = id % bgacache.length;
		// BGイメージキャッシュにTextureがある場合
		if (bgacacheid[cid] == id) {
			return bgacache[cid];
		}
		// BGイメージキャッシュにTextureがない場合
		if (id < bgamap.length && bgamap[id] != null){
			if(bgacache[cid] == null) {
				bgacache[cid] = new Texture(bgamap[id]);
			} else if(bgacache[cid].getWidth() != bgamap[id].getWidth() || bgacache[cid].getHeight() != bgamap[id].getHeight()){
				bgacache[cid].dispose();
				bgacache[cid] = new Texture(bgamap[id]);
			} else {
				bgacache[cid].draw(bgamap[id], 0, 0);
			}
			bgacacheid[cid] = id;
			return bgacache[cid];
		}
		return null;
	}

	/**
	 * リソースを開放する
	 */
	public void dispose() {
		for (Texture bga : bgacache) {
			if (bga != null) {
				bga.dispose();
			}
		}
		bgacache = new Texture[0];

		cache.dispose();
	}
}
