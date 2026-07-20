package bms.player.beatoraja;

import java.io.File;
import java.io.IOException;
import java.util.logging.Logger;
import java.util.stream.Stream;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ObjectMap;

import bms.player.beatoraja.audio.AudioDriver;

/**
 * BGM、効果音セット管理用クラス
 *
 * @author exch
 */
public class SystemSoundManager {

	private final MainController main;
	/**
	 * 検出されたBGMセットのディレクトリパス
	 */
	private Array<File> bgms = new Array<File>();
	/**
	 * 現在のBGMセットのディレクトリパス
	 */
	private File currentBGMPath;
	/**
	 * 検出された効果音セットのディレクトリパス
	 */
	private Array<File> sounds = new Array<File>();
	/**
	 * 現在の効果音セットのディレクトリパス
	 */
	private File currentSoundPath;

	private ObjectMap<SoundType, String> soundmap = new ObjectMap<>();

	public SystemSoundManager(MainController main) {
		this.main = main;
		Config config = main.getConfig();
		if(config.getBgmpath() != null && config.getBgmpath().length() > 0) {
			scan(new File(config.getBgmpath()).getAbsoluteFile(), bgms, "select");
		}
		if(config.getSoundpath() != null && config.getSoundpath().length() > 0) {
			scan(new File(config.getSoundpath()).getAbsoluteFile(), sounds, "clear");
		}
		// BGM fallback: 当 filesDir/bgm/ 没找到任何集时,去 filesDir/sound/ 找带 select/decide 标记的目录
		// (默认的 sound/default/ 里有 select.mp3 + decide.ogg,既能当 sound 也能当 BGM)。
		// 这样游戏内 BGM 列表始终至少有 "default" 可选,用户放外部集后再切回去也能找到。
		if (bgms.size == 0 && config.getSoundpath() != null && config.getSoundpath().length() > 0) {
			scan(new File(config.getSoundpath()).getAbsoluteFile(), bgms, "select");
			if (bgms.size > 0) {
				Logger.getGlobal().info("BGM 集为空,从 sound/ 目录 fallback: " + bgms.size + " 个候选");
			}
		}
		Logger.getGlobal().info("検出されたBGM Set : " + bgms.size + " Sound Set : " + sounds.size);
	}

	public void shuffle() {
		if(bgms.size > 0) {
			currentBGMPath = bgms.get((int) (Math.random() * bgms.size));
		}
		if(sounds.size > 0) {
			currentSoundPath = sounds.get((int) (Math.random() * sounds.size));
		}
		Logger.getGlobal().info("BGM Set : " + currentBGMPath + " Sound Set : " + currentSoundPath);

		for(SoundType sound : SoundType.values()) {
			for(File p :getSoundPaths(sound)) {
				String newpath = p.getPath();
				String oldpath = soundmap.get(sound);
				if (newpath.equals(oldpath) && !sound.equals(SoundType.SELECT)) {
					break;
				}
				if (oldpath != null) {
					main.getAudioProcessor().dispose(oldpath);
				}
				soundmap.put(sound, newpath);
				Logger.getGlobal().info("Registered sound " + sound + " -> " + newpath);
				break;
			}
		}
	}

	public File getBGMPath() {
		return currentBGMPath;
	}

	public File getSoundPath() {
		return currentSoundPath;
	}

	private void scan(File p, Array<File> paths, String name) {
		// 在 Android 上，使用 File 类检查目录更为可靠
		java.io.File dir = p;
		if (dir.isDirectory()) {
			try {
				java.io.File[] subFiles = dir.listFiles();
				if (subFiles != null) {
					for (java.io.File sub : subFiles) {
						scan(sub, paths, name);
					}
				}
				// 检查该目录下是否存在音效文件
				boolean found = false;
				String[] exts = {".wav", ".ogg", ".mp3"};
				for (String ext : exts) {
					java.io.File f = new java.io.File(dir, name + ext);
					if (f.exists()) {
						found = true;
						break;
					}
				}
				if (found) {
					paths.add(p);
				}
			} catch (Exception e) {
				// 忽略扫描异常
			}
		}
	}

	public File[] getSoundPaths(SoundType type) {
		File p = type.isBGM ? currentBGMPath : currentSoundPath;

		Array<File> paths = new Array<File>();
		if(p != null) {
			for(FileHandle fh : AudioDriver.getPaths(new File(p, type.path).getPath())) {
				paths.add(new File(fh.path()));
			}
		}

		// 修复Android上默认sound路径问题
		// Android: 音效文件在 APK 的 assets 中，使用内部资源路径
		// Desktop: 使用当前工作目录
		if (com.badlogic.gdx.Gdx.app.getType() == com.badlogic.gdx.Application.ApplicationType.Android) {
			// Android: 使用内部资源路径 (assets/sound/default/)
			String internalPath = "sound/default/" + type.path;
			for(FileHandle fh : AudioDriver.getPaths(internalPath)) {
				paths.add(new File(fh.path()));
			}
		} else {
			// Desktop: 使用 beatoraja.root 系统属性或当前工作目录
			String soundRoot = System.getProperty("beatoraja.root", "");
			if (soundRoot.isEmpty()) {
				soundRoot = com.badlogic.gdx.Gdx.files.getLocalStoragePath();
			}
			String defaultPath = new File(new File(new File(soundRoot), "sound"), "default").getPath();
			defaultPath = new File(new File(defaultPath), type.path).getPath();
			for(FileHandle fh : AudioDriver.getPaths(defaultPath)) {
				paths.add(new File(fh.path()));
			}
		}

		if (paths.size > 0) {
			Logger.getGlobal().info("Found sound path for " + type + ": " + paths.get(0));
		} else {
			Logger.getGlobal().warning("No sound path found for " + type);
		}

		return paths.toArray(File.class);
	}

	public String getSound(SoundType sound) {
		return soundmap.get(sound);
	}

	public void play(SoundType sound, boolean loop) {
		final String path = soundmap.get(sound);
		if (path != null) {
			// 对于非循环效果音，在播放前先停止上一个，实现截断机制，避免快速触发时重叠导致声音过大或杂乱
			if (!loop) {
				main.getAudioProcessor().stop(path);
			}
			main.getAudioProcessor().play(path, main.getConfig().getAudioConfig().getSystemvolume(), loop);
		}
	}

	public void stop(SoundType sound) {
		final String path = soundmap.get(sound);
		if (path != null) {
			main.getAudioProcessor().stop(path);
		}
	}

	public enum SoundType {
		SCRATCH("scratch.wav",false),
		FOLDER_OPEN("f-open.wav",false),
		FOLDER_CLOSE("f-close.wav",false),
		OPTION_CHANGE("o-change.wav",false),
		OPTION_OPEN("o-open.wav",false),
		OPTION_CLOSE("o-close.wav",false),
		PLAY_READY("playready.wav",false),
		PLAY_STOP("playstop.wav",false),
		RESULT_CLEAR("clear.wav",false),
		RESULT_FAIL("fail.wav",false),
		RESULT_CLOSE("resultclose.wav",false),
		COURSE_CLEAR("course_clear.wav",false),
		COURSE_FAIL("course_fail.wav",false),
		COURSE_CLOSE("course_close.wav",false),
		GUIDESE_PG("guide-pg.wav",false),
		GUIDESE_GR("guide-gr.wav",false),
		GUIDESE_GD("guide-gd.wav",false),
		GUIDESE_BD("guide-bd.wav",false),
		GUIDESE_PR("guide-pr.wav",false),
		GUIDESE_MS("guide-ms.wav",false),
		SELECT("select.wav",true),
		DECIDE("decide.wav",true);

		public final boolean isBGM;
		public final String path;

		private SoundType(String path, boolean isBGM) {
			this.path = path;
			this.isBGM = isBGM;
		}
	}
}
