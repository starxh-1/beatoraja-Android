package bms.player.beatoraja.play.bga;

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
		};
	}

	public void put(int id, String path) {
		Pixmap pixmap = cache.get(path);
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
