package bms.player.beatoraja;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
	private Array<Path> bgms = new Array<Path>();
	/**
	 * 現在のBGMセットのディレクトリパス
	 */
	private Path currentBGMPath;
	/**
	 * 検出された効果音セットのディレクトリパス
	 */
	private Array<Path> sounds = new Array<Path>();
	/**
	 * 現在の効果音セットのディレクトリパス
	 */
	private Path currentSoundPath;

	private ObjectMap<SoundType, String> soundmap = new ObjectMap<>();

	public SystemSoundManager(MainController main) {
		this.main = main;
		Config config = main.getConfig();
		if(config.getBgmpath() != null && config.getBgmpath().length() > 0) {
			scan(Paths.get(config.getBgmpath()).toAbsolutePath(), bgms, "select");
		}
		if(config.getSoundpath() != null && config.getSoundpath().length() > 0) {
			scan(Paths.get(config.getSoundpath()).toAbsolutePath(), sounds, "clear");
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
			for(Path p :getSoundPaths(sound)) {
				String newpath = p.toString();
				String oldpath = soundmap.get(sound);
				if (newpath.equals(oldpath)) {
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

	public Path getBGMPath() {
		return currentBGMPath;
	}

	public Path getSoundPath() {
		return currentSoundPath;
	}

	private void scan(Path p, Array<Path> paths, String name) {
		// 在 Android 上，使用 File 类检查目录更为可靠
		java.io.File dir = p.toFile();
		if (dir.isDirectory()) {
			try {
				java.io.File[] subFiles = dir.listFiles();
				if (subFiles != null) {
					for (java.io.File sub : subFiles) {
						scan(sub.toPath(), paths, name);
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

	public Path[] getSoundPaths(SoundType type) {
		Path p = type.isBGM ? currentBGMPath : currentSoundPath;

		Array<Path> paths = new Array<Path>();
		if(p != null) {
			for(FileHandle fh : AudioDriver.getPaths(p.resolve(type.path).toString())) {
				paths.add(Paths.get(fh.path()));
			}
		}
		
		// 修复Android上默认sound路径问题
		// Android 上，音效文件已经被 AndroidLauncher 复制到了外部存储的根目录下
		// 使用 beatoraja.root 系统属性获取正确的外部存储路径
		String soundRoot;
		if (com.badlogic.gdx.Gdx.app.getType() == com.badlogic.gdx.Application.ApplicationType.Android) {
			// Android: 使用 beatoraja.root 系统属性（由 AndroidLauncher 设置）
			soundRoot = System.getProperty("beatoraja.root", "");
			if (soundRoot.isEmpty()) {
				// 回退方案：尝试从 Config 获取
				soundRoot = com.badlogic.gdx.Gdx.files.getLocalStoragePath();
			}
		} else {
			// Desktop: 使用当前工作目录
			soundRoot = "";
		}
		
		String defaultPath = Paths.get(soundRoot).resolve("sound").resolve("default").resolve(type.path).toString();
		for(FileHandle fh : AudioDriver.getPaths(defaultPath)) {
			paths.add(Paths.get(fh.path()));
		}
		
		// 如果仍然没有找到，尝试在 Android 外部存储的绝对路径下查找
		if (paths.size == 0 && com.badlogic.gdx.Gdx.app.getType() == com.badlogic.gdx.Application.ApplicationType.Android) {
			String altPath = soundRoot + "/sound/default/" + type.path;
			for(FileHandle fh : AudioDriver.getPaths(altPath)) {
				paths.add(Paths.get(fh.path()));
			}
		}
		
		if (paths.size > 0) {
			Logger.getGlobal().info("Found sound path for " + type + ": " + paths.get(0));
		} else {
			Logger.getGlobal().warning("No sound path found for " + type + " (tried: " + defaultPath + ")");
		}
		
		return paths.toArray(Path.class);
	}

	public String getSound(SoundType sound) {
		return soundmap.get(sound);
	}

	public void play(SoundType sound, boolean loop) {
		final String path = soundmap.get(sound);
		if (path != null) {
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
