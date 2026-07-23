package bms.player.beatoraja;

import com.badlogic.gdx.files.FileHandle;
import java.util.ArrayDeque;
import java.util.logging.Logger;

import bms.model.BMSModel;
import bms.player.beatoraja.audio.AudioDriver;
import bms.player.beatoraja.play.bga.BGAProcessor;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

/**
 * BMSの音源、BGAリソースを管理するクラス
 *
 * @author exch
 */
public class BMSResource {

 	/**
		 * 選曲中のBMS
		 */
	private BMSModel model;
	/**
	 * BMSの音源リソース
	 */
	private AudioDriver audio;
	/**
	 * 音源読み込みタスク
	 */
	private final ArrayDeque<Thread> audioloaders = new ArrayDeque<Thread>();
	/**
	 * BMSのBGAリソース
	 */
	private BGAProcessor bga;

	private boolean bgaon;
	/**
	 * BGA読み込みタスク
	 */
	private final ArrayDeque<Thread> bgaloaders = new ArrayDeque<Thread>();
	/**
	 * backbmp
	 */
	private TextureRegion backbmp;
	/**
	 * stagefile
	 */
	private TextureRegion stagefile;

	private Pixmap stagefilePix;

	/**
	 * stagefile
	 */
	private TextureRegion banner;

	private Pixmap bannerPix;

	public BMSResource(AudioDriver audio, Config config, PlayerConfig player) {
		this.audio = audio;
		bga = new BGAProcessor(config, player);
	}

	public boolean setBMSFile(BMSModel model, final FileHandle f, final Config config, BMSPlayerMode mode) {
		if(stagefile != null) {
			stagefile.getTexture().dispose();
			stagefile = null;
		}
		try {
			String sf = model.getStagefile();
			if (sf != null && !sf.isEmpty()) {
				String resolved = PixmapResourcePool.findImagePath(f.parent().child(sf).path());
				if (resolved != null) {
					Pixmap pix = PixmapResourcePool.loadPicture(resolved);
					if(pix != null) {
						stagefile = new TextureRegion(new Texture(pix));
						pix.dispose();
					}
				}
			}
		} catch(Throwable e) {
			Logger.getGlobal().warning(e.getMessage());
		}

		if(backbmp != null) {
			backbmp.getTexture().dispose();
			backbmp = null;
		}
		try {
			String bb = model.getBackbmp();
			if (bb != null && !bb.isEmpty()) {
				String resolved = PixmapResourcePool.findImagePath(f.parent().child(bb).path());
				if (resolved != null) {
					Pixmap pix = PixmapResourcePool.loadPicture(resolved);
					if(pix != null) {
						backbmp = new TextureRegion(new Texture(pix));
						pix.dispose();
					}
				}
			}
		} catch(Throwable e) {
			Logger.getGlobal().warning(e.getMessage());
		}

		this.model = model;
		synchronized(audioloaders) {
			while(!audioloaders.isEmpty() && !audioloaders.getFirst().isAlive()) {
				audioloaders.removeFirst();
			}
		}
		synchronized(bgaloaders) {
			while(!bgaloaders.isEmpty() && !bgaloaders.getFirst().isAlive()) {
				bgaloaders.removeFirst();
			}
		}

		if(MainLoader.getIllegalSongCount() == 0) {
			// Audio, BGAともキャッシュがあるため、何があっても全リロードする
			final BMSModel bgamodel = config.getBga() == Config.BGA_ON || (config.getBga() == Config.BGA_AUTO && (mode.mode == BMSPlayerMode.Mode.AUTOPLAY || mode.mode == BMSPlayerMode.Mode.REPLAY)) ? model : null;
			// bgaon を同期的に設定する（Skin.prepare() が静的条件として評価するため、
			// 後台スレッド内で設定すると競態条件で SkinBGA が永久に削除される可能性がある）
			bgaon = bgamodel != null;
			final Thread prevBgaloader;
			synchronized(bgaloaders) {
				prevBgaloader = bgaloaders.peekLast();
			}
			Thread bgaloader = new Thread(() -> {
				try {
					bga.abort();
					// 等待上一个BGA加载线程完全退出后再开始，防止硬件解码器堆积
					if (prevBgaloader != null && prevBgaloader.isAlive()) {
						prevBgaloader.join();
					}
					bga.setModel(bgamodel);
					// 在加载线程上预加载解码器/视频文件，避免阻塞 GL 线程的 STATE_PRELOAD 第一帧
					// （MediaCodec 首次打开每个几百 ms，N 个 BGA 串起来就是秒级）
					if (bgamodel != null) {
						bga.prepareDecoders();
					}
				} catch (Throwable e) {
					Logger.getGlobal().severe(e.getClass().getName() + " : " + e.getMessage());
					e.printStackTrace();
				}
			});
			synchronized(bgaloaders) {
				bgaloaders.addLast(bgaloader);
			}
			bgaloader.start();
			final Thread prevAudioloader;
			synchronized(audioloaders) {
				prevAudioloader = audioloaders.peekLast();
			}
			Thread audioloader = new Thread(() -> {
				try {
					audio.abort();
					// 等待上一个音频加载线程完全退出后再开始
					if (prevAudioloader != null && prevAudioloader.isAlive()) {
						prevAudioloader.join();
					}
					audio.setModel(model);
				} catch (Throwable e) {
					Logger.getGlobal().severe(e.getClass().getName() + " : " + e.getMessage());
					e.printStackTrace();
				}
			});
			synchronized(audioloaders) {
				audioloaders.addLast(audioloader);
			}
			audioloader.start();
		}
		return true;
	}

	public AudioDriver getAudioDriver() {
		return audio;
	}

	public BGAProcessor getBGAProcessor() {
		return bga;
	}

	public boolean isBGAOn() {
		return bgaon;
	}

	public boolean mediaLoadFinished() {
		synchronized(audioloaders) {
			if(!audioloaders.isEmpty() && audioloaders.getLast().isAlive()) {
				return false;
			}
		}
		synchronized(bgaloaders) {
			if(!bgaloaders.isEmpty() && bgaloaders.getLast().isAlive()) {
				return false;
			}
		}
		return true;
	}

	public TextureRegion getBackbmp() {
		return backbmp;
	}

	public TextureRegion getStagefile() {
		return stagefile;
	}

	public TextureRegion getBanner() {
		return banner;
	}

	public void setStagefile(Pixmap pixmap) {
		final TextureRegion oldstagefile = stagefile;
		if (pixmap != null) {
			if(stagefilePix != pixmap) {
				try {
					stagefile = new TextureRegion(new Texture(pixmap));
					stagefilePix = pixmap;
				} catch (Exception e) {
					// Pixmap may have been disposed by resource pool
					stagefile = oldstagefile;
					stagefilePix = null;
					return;
				}
			}
		} else {
			stagefile = null;
			stagefilePix = null;
		}
		if (oldstagefile != stagefile && oldstagefile != null) {
			oldstagefile.getTexture().dispose();
		}
	}

	public void setBanner(Pixmap pixmap) {
		final TextureRegion oldbanner = banner;
		if (pixmap != null) {
			if(bannerPix != pixmap) {
				try {
					banner = new TextureRegion(new Texture(pixmap));
					bannerPix = pixmap;
				} catch (Exception e) {
					// Pixmap may have been disposed by resource pool
					banner = oldbanner;
					bannerPix = null;
					return;
				}
			}
		} else {
			banner = null;
			bannerPix = null;
		}
		if (oldbanner != banner && oldbanner != null) {
			oldbanner.getTexture().dispose();
		}
	}

	public void dispose() {
		if (audio != null) {
			audio.dispose();
			audio = null;
		}
		if (bga != null) {
			bga.dispose();
			bga = null;
		}
		if(stagefile != null) {
			stagefile.getTexture().dispose();
			stagefile = null;
		}
		if(backbmp != null) {
			backbmp.getTexture().dispose();
			backbmp = null;
		}
	}
 }
