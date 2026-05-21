package bms.player.beatoraja;

import com.badlogic.gdx.files.FileHandle;
import java.util.ArrayDeque;
import java.util.logging.Logger;

import bms.model.BMSModel;
import bms.player.beatoraja.FileCache;
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
	private ArrayDeque<Thread> audioloaders = new ArrayDeque<Thread>();
	/**
	 * BMSのBGAリソース
	 */
	private BGAProcessor bga;

	private boolean bgaon;
	/**
	 * BGA読み込みタスク
	 */
	private ArrayDeque<Thread> bgaloaders = new ArrayDeque<Thread>();
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
			FileHandle stageFileHandle = f.parent().child(model.getStagefile());
			if (FileCache.exists(stageFileHandle)) {
				Pixmap pix = PixmapResourcePool.loadPicture(stageFileHandle.path());
				if(pix != null) {
					stagefile = new TextureRegion(new Texture(pix));
					pix.dispose();
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
			FileHandle backBmpHandle = f.parent().child(model.getBackbmp());
			if (FileCache.exists(backBmpHandle)) {
				Pixmap pix = PixmapResourcePool.loadPicture(backBmpHandle.path());
				if(pix != null) {
					backbmp = new TextureRegion(new Texture(pix));
					pix.dispose();
				}
			}
		} catch(Throwable e) {
			Logger.getGlobal().warning(e.getMessage());
		}

		this.model = model;
		while(!audioloaders.isEmpty() && !audioloaders.getFirst().isAlive()) {
			audioloaders.removeFirst();
		}
		while(!bgaloaders.isEmpty() && !bgaloaders.getFirst().isAlive()) {
			bgaloaders.removeFirst();
		}

		if(MainLoader.getIllegalSongCount() == 0) {
			// Audio, BGAともキャッシュがあるため、何があっても全リロードする
			final BMSModel bgamodel = config.getBga() == Config.BGA_ON || (config.getBga() == Config.BGA_AUTO && (mode.mode == BMSPlayerMode.Mode.AUTOPLAY || mode.mode == BMSPlayerMode.Mode.REPLAY)) ? model : null;
			// bgaon を同期的に設定する（Skin.prepare() が静的条件として評価するため、
			// 後台スレッド内で設定すると競態条件で SkinBGA が永久に削除される可能性がある）
			bgaon = bgamodel != null;
			Thread bgaloader = new Thread(() -> {
				try {
					bga.abort();
					bga.setModel(bgamodel);
				} catch (Throwable e) {
					Logger.getGlobal().severe(e.getClass().getName() + " : " + e.getMessage());
					e.printStackTrace();
				}
			});
			bgaloaders.addLast(bgaloader);
			bgaloader.start();
			Thread audioloader = new Thread(() -> {
				try {
					audio.abort();
					audio.setModel(model);
				} catch (Throwable e) {
					Logger.getGlobal().severe(e.getClass().getName() + " : " + e.getMessage());
					e.printStackTrace();
				}
			});
			audioloaders.addLast(audioloader);
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
		if(!audioloaders.isEmpty() && audioloaders.getLast().isAlive()) {
			return false;
		}
		if(!bgaloaders.isEmpty() && bgaloaders.getLast().isAlive()) {
			return false;
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
