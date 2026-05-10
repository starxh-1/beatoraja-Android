package bms.player.beatoraja;

import static bms.player.beatoraja.Resolution.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.Serializable;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonWriter.OutputType;

/**
 * 各種設定項目。config.jsonで保持される
 *
 * @author exch
 */
public class Config implements Validatable, Serializable {

	/**
	 * 旧コンフィグパス。そのうち削除
	 */
	static Path configpath_old = Paths.get("config.json");
	/**
	 * コンフィグパス(UTF-8)
	 */
	static Path configpath = Paths.get(System.getProperty("user.dir", "."), "config_sys.json");

	public static void updateConfigPath() {
		String root = System.getProperty("beatoraja.root", System.getProperty("user.dir", "."));
		configpath = Paths.get(root, "config_sys.json");
		configpath_old = Paths.get(root, "config.json");
	}

	/**
	 * 選択中のプレイヤー名
	 */
	private String playername = "player1";
	/**
	 * ディスプレイモード
	 */
	private DisplayMode displaymode = DisplayMode.WINDOW;
	/**
	 * 垂直同期
	 */
	private boolean vsync;
	/**
	 * 解像度
	 */
	private Resolution resolution = HD;

	private boolean useResolution = true;
	private int windowWidth = 1280;
	private int windowHeight = 720;

	/**
	 * フォルダランプの有効/無効
	 */
	private boolean folderlamp = true;

	/**
	 * オーディオコンフィグ
	 */
	private AudioConfig audio;

	/**
	 * 最大FPS。垂直同期OFFの時のみ有効
	 */
	private int maxFramePerSecond = 300;

	/**
	 * Android 专用设置：是否允许无限制的高帧率
	 * 默认关闭，由帧率控制逻辑接管，避免系统调度导致帧率浮动
	 */
	private boolean androidUnlimitedFPS = false;

	/**
	 * Android 专用设置：是否启用稳定帧率模式（保留字段，已由绝对时间对齐帧率控制取代）
	 * 新版帧率控制逻辑不再区分此模式，始终使用绝对时间对齐方式
	 */
	private boolean androidStableFPS = true;

	/**
	 * Android 专用设置：Play 界面是否显示触摸按键
	 */
	private boolean showTouchKey = false;

	/**
	 * 获取是否启用 Android 无限制帧率
	 */
	public boolean isAndroidUnlimitedFPS() {
		return androidUnlimitedFPS;
	}

	/**
	 * 设置是否启用 Android 无限制帧率
	 */
	public void setAndroidUnlimitedFPS(boolean androidUnlimitedFPS) {
		this.androidUnlimitedFPS = androidUnlimitedFPS;
	}

	/**
	 * 获取是否启用 Android 稳定帧率模式
	 */
	public boolean isAndroidStableFPS() {
		return androidStableFPS;
	}

	/**
	 * 设置是否启用 Android 稳定帧率模式
	 */
	public void setAndroidStableFPS(boolean androidStableFPS) {
		this.androidStableFPS = androidStableFPS;
	}

	public boolean isShowTouchKey() {
		return showTouchKey;
	}

	public void setShowTouchKey(boolean showTouchKey) {
		this.showTouchKey = showTouchKey;
	}

	public boolean isShowAudioSpectrum() {
		return showAudioSpectrum;
	}

	public void setShowAudioSpectrum(boolean showAudioSpectrum) {
		this.showAudioSpectrum = showAudioSpectrum;
	}

	private boolean showAudioSpectrum = true;

	public boolean isSpectrumInGameArea() {
		return spectrumInGameArea;
	}

	public void setSpectrumInGameArea(boolean spectrumInGameArea) {
		this.spectrumInGameArea = spectrumInGameArea;
	}

	public boolean isShowFloatingMenuInPlay() {
		return showFloatingMenuInPlay;
	}

	public void setShowFloatingMenuInPlay(boolean showFloatingMenuInPlay) {
		this.showFloatingMenuInPlay = showFloatingMenuInPlay;
	}

	private boolean spectrumInGameArea = true;

	private boolean showFloatingMenuInPlay = true;

	private int prepareFramePerSecond = 0;
	/**
	 * 検索バー同時表示上限数
	 */
	private int maxSearchBarCount = 10;
	/**
	 * 所持していない楽曲バーを表示するかどうか
	 */
	private boolean showNoSongExistingBar = true;
	/**
	 * 選曲バー移動速度の最初
	 */
	private int scrolldurationlow = 300;
	/**
	 * 選曲バー移動速度の2つ目以降
	 */
	private int scrolldurationhigh = 50;
	/**
	 * 選曲バーとレーンカバーのアナログスクロール
	 */
	private boolean analogScroll = true;
	/**
	 * 選曲バー移動速度に関連（アナログスクロール）
	 */
	private int analogTicksPerScroll = 3;

	/**
	 * プレビュー再生
	 */
	private SongPreview songPreview = SongPreview.LOOP;
	/**
	 * スキン画像のキャッシュイメージを作成するかどうか
	 */
    private boolean cacheSkinImage = false;
    /**
     * songinfoデータベースを使用するかどうか
     */
    private boolean useSongInfo = true;

	private String songpath = SONGPATH_DEFAULT;
	public static final String SONGPATH_DEFAULT = "songdata.db";

	private String songinfopath = SONGINFOPATH_DEFAULT;
	public static final String SONGINFOPATH_DEFAULT = "songinfo.db";

	private String tablepath = TABLEPATH_DEFAULT;
	public static final String TABLEPATH_DEFAULT = "table";

	private String playerpath = PLAYERPATH_DEFAULT;
	public static final String PLAYERPATH_DEFAULT = "player";

	private String skinpath = SKINPATH_DEFAULT;
	public static final String SKINPATH_DEFAULT = "skin";

	private String bgmpath = "bgm";

	private String soundpath = "sound";

	private String systemfontpath = "font/VL-Gothic-Regular.ttf";
	private String messagefontpath = "font/VL-Gothic-Regular.ttf";
	/**
	 * BMSルートディレクトリパス
	 */
	private String[] bmsroot = new String[0];
	/**
	 * 難易度表URL
	 */
	private String[] tableURL = DEFAULT_TABLEURL;
	/**
	 * BGA表示
	 */
	private int bga = BGA_ON;
	public static final int BGA_ON = 0;
	public static final int BGA_AUTO = 1;
	public static final int BGA_OFF = 2;
	/**
	 * BGA拡大
	 */
	private int bgaExpand = BGAEXPAND_KEEP_ASPECT_RATIO;
	public static final int BGAEXPAND_FULL = 0;
	public static final int BGAEXPAND_KEEP_ASPECT_RATIO = 1;
	public static final int BGAEXPAND_OFF = 2;

	private int frameskip = 1;

	private boolean updatesong = false;

	private int skinPixmapGen = 4;
	private int stagefilePixmapGen = 2;
	private int bannerPixmapGen = 2;
	private int songResourceGen = 1;

	private boolean enableIpfs = true;
	private String ipfsurl = "https://gateway.ipfs.io/";

	private int irSendCount = 5;

	private boolean useDiscordRPC = false;
	private boolean setClipboardScreenshot = false;

	private static final String[] DEFAULT_TABLEURL = { "https://rattoto10.jounin.jp/table.html",
			"https://rattoto10.jounin.jp/table_insane.html",
			"https://rattoto10.jounin.jp/table_overjoy.html",
			"https://miraiscarlet.github.io/bms/table/genocide_normal/normal_bms.html",
			"https://miraiscarlet.github.io/bms/table/genocide_insane/insane_bms.html",
			"http://walkure.net/hakkyou/for_glassist/bms/?lamp=easy",
			"http://walkure.net/hakkyou/for_glassist/bms/?lamp=normal",
			"http://walkure.net/hakkyou/for_glassist/bms/?lamp=hard",
			"http://walkure.net/hakkyou/for_glassist/bms/?lamp=fc",
			"https://stellabms.xyz/sl/table.html",
			"https://stellabms.xyz/st/table.html",
			"https://mocha-repository.info/table/dpn_header.json",
			"https://mocha-repository.info/table/dpi_header.json",
			"https://stellabms.xyz/dp/table.html",
			"https://stellabms.xyz/dpst/table.html",
			"https://mocha-repository.info/table/ln_header.json",
			"https://pmsdifficulty.xxxxxxxx.jp/_pastoral_insane_table.html",
			"https://excln.github.io/table24k/table.html",
	};

	public Config() {
	}

	public String getPlayername() {
		return playername;
	}

	public void setPlayername(String playername) {
		this.playername = playername;
	}

	public boolean isVsync() {
		return vsync;
	}

	public void setVsync(boolean vsync) {
		this.vsync = vsync;
	}

	public int getBga() {
		return bga;
	}

	public void setBga(int bga) {
		this.bga = bga;
	}

	public AudioConfig getAudioConfig() {
		return audio;
	}

	public void setAudioConfig(AudioConfig audio) {
		this.audio = audio;
	}

	public int getMaxFramePerSecond() {
		return maxFramePerSecond;
	}

	public void setMaxFramePerSecond(int maxFramePerSecond) {
		this.maxFramePerSecond = maxFramePerSecond;
	}

	public int getPrepareFramePerSecond() {
		return prepareFramePerSecond;
	}

	public void setPrepareFramePerSecond(int prepareFramePerSecond) {
		this.prepareFramePerSecond = prepareFramePerSecond;
	}

	public String[] getBmsroot() {
		return bmsroot;
	}

	public void setBmsroot(String[] bmsroot) {
		this.bmsroot = bmsroot;
	}

	public String[] getTableURL() {
		return tableURL;
	}

	public void setTableURL(String[] tableURL) {
		this.tableURL = tableURL;
	}

	public boolean isFolderlamp() {
		return folderlamp;
	}

	public void setFolderlamp(boolean folderlamp) {
		this.folderlamp = folderlamp;
	}

	public Resolution getResolution() {
		return resolution;
	}

	public void setResolution(Resolution resolution) {
		this.resolution = resolution;
	}

	public int getWindowWidth() {
		return windowWidth;
	}

	public void setWindowWidth(int width) {
		this.windowWidth = width;
	}

	public int getWindowHeight() {
		return windowHeight;
	}

	public void setWindowHeight(int height) {
		this.windowHeight = height;
	}

	public int getFrameskip() {
		return frameskip;
	}

	public void setFrameskip(int frameskip) {
		this.frameskip = frameskip;
	}

	public String getBgmpath() {
		return getAbsolutePath(bgmpath);
	}

	public void setBgmpath(String bgmpath) {
		this.bgmpath = bgmpath;
	}

	public String getSoundpath() {
		return getAbsolutePath(soundpath);
	}

	public void setSoundpath(String soundpath) {
		this.soundpath = soundpath;
	}

	public int getMaxSearchBarCount() {
	    return maxSearchBarCount;
    }

    public void setMaxSearchBarCount(int maxSearchBarCount) {
	    this.maxSearchBarCount = maxSearchBarCount;
    }

	public boolean isShowNoSongExistingBar() {
		return showNoSongExistingBar;
	}

	public void setShowNoSongExistingBar(boolean showNoExistingSongBar) {
		this.showNoSongExistingBar = showNoExistingSongBar;
	}

	public int getScrollDurationLow(){
		return scrolldurationlow;
	}
	public void setScrollDutationLow(int scrolldurationlow){
		this.scrolldurationlow = scrolldurationlow;
	}
	public int getScrollDurationHigh(){
		return scrolldurationhigh;
	}
	public void setScrollDutationHigh(int scrolldurationhigh){
		this.scrolldurationhigh = scrolldurationhigh;
	}

    public boolean isAnalogScroll() {
        return analogScroll;
    }
    public void setAnalogScroll(boolean analogScroll) {
        this.analogScroll = analogScroll;
    }

    public int getAnalogTicksPerScroll() {
        return analogTicksPerScroll;
    }
    public void setAnalogTicksPerScroll(int analogTicksPerScroll) {
        this.analogTicksPerScroll = Math.max(analogTicksPerScroll, 1);
    }

	public SongPreview getSongPreview() {
		return songPreview;
	}

	public void setSongPreview(SongPreview songPreview) {
		this.songPreview = songPreview;
	}

	public boolean isUseSongInfo() {
		return useSongInfo;
	}

	public void setUseSongInfo(boolean useSongInfo) {
		this.useSongInfo = useSongInfo;
	}

	public int getBgaExpand() {
		return bgaExpand;
	}

	public void setBgaExpand(int bgaExpand) {
		this.bgaExpand = bgaExpand;
	}

	public boolean isCacheSkinImage() {
		return cacheSkinImage;
	}

	public void setCacheSkinImage(boolean cacheSkinImage) {
		this.cacheSkinImage = cacheSkinImage;
	}

	public boolean isUseDiscordRPC() {
		return useDiscordRPC;
	}

	public void setUseDiscordRPC(boolean useDiscordRPC) {
		this.useDiscordRPC = useDiscordRPC;
	}

	public boolean isSetClipboardWhenScreenshot() {
		return setClipboardScreenshot;
	}

	public void setClipboardWhenScreenshot(boolean setClipboardScreenshot) {
		this.setClipboardScreenshot = setClipboardScreenshot;
	}

	public boolean isUpdatesong() {
		return updatesong;
	}

	public void setUpdatesong(boolean updatesong) {
		this.updatesong = updatesong;
	}

	public DisplayMode getDisplaymode() {
		return displaymode;
	}

	public void setDisplaymode(DisplayMode displaymode) {
		this.displaymode = displaymode;
	}

	public int getSkinPixmapGen() {
		return skinPixmapGen;
	}

	public void setSkinPixmapGen(int skinPixmapGen) {
		this.skinPixmapGen = skinPixmapGen;
	}

	public int getStagefilePixmapGen() {
		return stagefilePixmapGen;
	}

	public void setStagefilePixmapGen(int stagefilePixmapGen) {
		this.stagefilePixmapGen = stagefilePixmapGen;
	}

	public int getBannerPixmapGen() {
		return bannerPixmapGen;
	}

	public void setBannerPixmapGen(int bannerPixmapGen) {
		this.bannerPixmapGen = bannerPixmapGen;
	}

	public int getSongResourceGen() {
		return songResourceGen;
	}

	public void setSongResourceGen(int songResourceGen) {
		this.songResourceGen = songResourceGen;
	}

	public boolean isEnableIpfs() {
		return enableIpfs;
	}

	public void setEnableIpfs(boolean enableIpfs) {
		this.enableIpfs = enableIpfs;
	}

	public String getIpfsUrl() {
		return ipfsurl;
	}

	public void setIpfsUrl(String ipfsUrl) {
		this.ipfsurl = ipfsUrl;
	}

	public static String getAbsolutePath(String path) {
		if (path == null) return null;
		java.io.File file = new java.io.File(path);
		if (file.isAbsolute()) return path;
		String userDir = System.getProperty("beatoraja.root", "");
		if (userDir.isEmpty()) return path;
		return new java.io.File(userDir, path).getAbsolutePath();
	}

	public String getSongpath() {
		return getAbsolutePath(songpath);
	}

	public void setSongpath(String songpath) {
		this.songpath = songpath;
	}

	public String getSonginfopath() {
		return getAbsolutePath(songinfopath);
	}

	public void setSonginfopath(String songinfopath) {
		this.songinfopath = songinfopath;
	}

	public String getTablepath() {
		return getAbsolutePath(tablepath);
	}

	public void setTablepath(String tablepath) {
		this.tablepath = tablepath;
	}

	public String getPlayerpath() {
		return getAbsolutePath(playerpath);
	}

	public void setPlayerpath(String playerpath) {
		this.playerpath = playerpath;
	}

	public String getSkinpath() {
		return getAbsolutePath(skinpath);
	}

	public void setSkinpath(String skinpath) {
		this.skinpath = skinpath;
	}

	public String getSystemfontpath() {
		return systemfontpath;
	}

	public void setSystemfontpath(String systemfontpath) {
		this.systemfontpath = systemfontpath;
	}

	public String getMessagefontpath() {
		return messagefontpath;
	}

	public void setMessagefontpath(String messagefontpath) {
		this.messagefontpath = messagefontpath;
	}

	public boolean validate() {
		displaymode = (displaymode != null) ? displaymode : DisplayMode.WINDOW;
		resolution = (resolution != null) ? resolution : Resolution.HD;

		windowWidth = MathUtils.clamp(windowWidth, Resolution.SD.width, Resolution.ULTRAHD.width);
		windowHeight = MathUtils.clamp(windowHeight, Resolution.SD.height, Resolution.ULTRAHD.height);

		if(audio == null) {
			audio = new AudioConfig();
		}
		audio.validate();

		maxFramePerSecond = MathUtils.clamp(maxFramePerSecond, 0, 1000);
		prepareFramePerSecond = MathUtils.clamp(prepareFramePerSecond, 0, 100000);
        maxSearchBarCount = MathUtils.clamp(maxSearchBarCount, 1, 100);
        songPreview = (songPreview != null) ? songPreview : SongPreview.LOOP;

		scrolldurationlow = MathUtils.clamp(scrolldurationlow, 2, 1000);
		scrolldurationhigh = MathUtils.clamp(scrolldurationhigh, 1, 1000);
		irSendCount = MathUtils.clamp(irSendCount, 1, 100);

		skinPixmapGen = MathUtils.clamp(skinPixmapGen, 0, 100);
		stagefilePixmapGen = MathUtils.clamp(stagefilePixmapGen, 0, 100);
		bannerPixmapGen = MathUtils.clamp(bannerPixmapGen, 0, 100);
		songResourceGen = MathUtils.clamp(songResourceGen, 0, 100);

		bmsroot = Validatable.removeInvalidElements(bmsroot);

		if(bmsroot == null || bmsroot.length == 0) {
			if(com.badlogic.gdx.Gdx.app.getType() == com.badlogic.gdx.Application.ApplicationType.Android) {
				// Android 平台的默认 BMS 路径由 AndroidLauncher 通过 SongDatabaseAccessor 设置
				// 这里不硬编码具体路径，避免引用 android.os 包导致跨平台编译失败
				// 如果此处被调用且 bmsroot 仍为空，使用回退路径
				bmsroot = new String[]{"/storage/emulated/0/Download/oraja_bms"};
			}
		}

		if(tableURL == null) {
			tableURL = DEFAULT_TABLEURL;
		}
		tableURL = Validatable.removeInvalidElements(tableURL);

		bga = MathUtils.clamp(bga, 0, 2);
		bgaExpand = MathUtils.clamp(bgaExpand, 0, 2);
		if (ipfsurl == null) {
			ipfsurl = "https://gateway.ipfs.io/";
		}

		songpath = songpath != null ? songpath : SONGPATH_DEFAULT;
		songinfopath = songinfopath != null ? songinfopath : SONGINFOPATH_DEFAULT;
		tablepath = tablepath != null ? tablepath : TABLEPATH_DEFAULT;
		playerpath = playerpath != null ? playerpath : PLAYERPATH_DEFAULT;
		skinpath = skinpath != null ? skinpath : SKINPATH_DEFAULT;
		return true;
	}

	public static Config read() {
		Config config = null;
		// 统一使用 absolute() 访问 beatoraja.root 下的绝对路径（Android 和 Desktop 均正确）
		FileHandle fh = com.badlogic.gdx.Gdx.files.absolute(configpath.toString());
		if (fh.exists()) {
			Json json = new Json();
			json.setIgnoreUnknownFields(true);
			try (Reader reader = fh.reader("UTF-8")) {
				config = json.fromJson(Config.class, reader);
				com.badlogic.gdx.Gdx.app.log("Config", "Successfully loaded from: " + fh.path());
			} catch (Exception e) {
				com.badlogic.gdx.Gdx.app.error("Config", "Failed to read config from: " + fh.path(), e);
			}
		}
		// 旧路径兼容：如果新路径不存在，尝试旧路径
		if (config == null) {
			FileHandle fhOld = com.badlogic.gdx.Gdx.files.absolute(configpath_old.toString());
			if (fhOld.exists()) {
				com.badlogic.gdx.Gdx.app.log("Config", "Trying old config path: " + fhOld.path());
				Json json = new Json();
				json.setIgnoreUnknownFields(true);
				try (Reader reader = fhOld.reader("UTF-8")) {
					config = json.fromJson(Config.class, reader);
					com.badlogic.gdx.Gdx.app.log("Config", "Successfully loaded from old path: " + fhOld.path());
				} catch (Exception e) {
					com.badlogic.gdx.Gdx.app.error("Config", "Failed to read old config from: " + fhOld.path(), e);
				}
			}
		}
		if(config == null) {
			com.badlogic.gdx.Gdx.app.log("Config", "Config file not found, creating new one with defaults");
			config = new Config();
		}
		config.validate();

		PlayerConfig.init(config);

		return config;
	}

	public static void write(Config config) {
		Json json = new Json();
		json.setUsePrototypes(false);
		json.setOutputType(OutputType.json);
		// 统一使用 absolute() 写入 beatoraja.root 下的绝对路径
		FileHandle fh = com.badlogic.gdx.Gdx.files.absolute(configpath.toString());
		// 确保父目录存在
		FileHandle parent = fh.parent();
		if (!parent.exists()) {
			parent.mkdirs();
		}
		try (Writer writer = fh.writer(false, "UTF-8")) {
			writer.write(json.prettyPrint(config));
			writer.flush();
			com.badlogic.gdx.Gdx.app.log("Config", "Successfully saved to: " + fh.path());
		} catch (IOException e) {
			com.badlogic.gdx.Gdx.app.error("Config", "Failed to save config", e);
		} catch (Throwable t) {
			com.badlogic.gdx.Gdx.app.error("Config", "Unexpected error when saving config", t);
		}
	}

	public int getIrSendCount() {
		return irSendCount;
	}

	public void setIrSendCount(int irSendCount) {
		this.irSendCount = irSendCount;
	}

	public boolean isUseResolution() {
		return useResolution;
	}

	public void setUseResolution(boolean useResolution) {
		this.useResolution = useResolution;
	}

	public enum DisplayMode {
		FULLSCREEN,BORDERLESS,WINDOW;
	}

	public enum SongPreview {
		NONE,ONCE,LOOP;
	}

    public Config copy() {
        Config c = new Config();
        // Copy all fields
        c.playername = this.playername;
        c.displaymode = this.displaymode;
        c.vsync = this.vsync;
        c.resolution = this.resolution;
        c.useResolution = this.useResolution;
        c.windowWidth = this.windowWidth;
        c.windowHeight = this.windowHeight;
        c.folderlamp = this.folderlamp;
        c.audio = this.audio != null ? this.audio.copy() : null;
        c.maxFramePerSecond = this.maxFramePerSecond;
        c.prepareFramePerSecond = this.prepareFramePerSecond;
        c.maxSearchBarCount = this.maxSearchBarCount;
        c.showNoSongExistingBar = this.showNoSongExistingBar;
        c.scrolldurationlow = this.scrolldurationlow;
        c.scrolldurationhigh = this.scrolldurationhigh;
        c.analogScroll = this.analogScroll;
        c.analogTicksPerScroll = this.analogTicksPerScroll;
        c.songPreview = this.songPreview;
        c.cacheSkinImage = this.cacheSkinImage;
        c.useSongInfo = this.useSongInfo;
        c.songpath = this.songpath;
        c.songinfopath = this.songinfopath;
        c.tablepath = this.tablepath;
        c.playerpath = this.playerpath;
        c.skinpath = this.skinpath;
        c.bgmpath = this.bgmpath;
        c.soundpath = this.soundpath;
        c.systemfontpath = this.systemfontpath;
        c.messagefontpath = this.messagefontpath;
        c.bmsroot = this.bmsroot != null ? this.bmsroot.clone() : null;
        c.tableURL = this.tableURL != null ? this.tableURL.clone() : null;
        c.bga = this.bga;
        c.bgaExpand = this.bgaExpand;
        c.frameskip = this.frameskip;
        c.updatesong = this.updatesong;
        c.skinPixmapGen = this.skinPixmapGen;
        c.stagefilePixmapGen = this.stagefilePixmapGen;
        c.bannerPixmapGen = this.bannerPixmapGen;
        c.songResourceGen = this.songResourceGen;
        c.enableIpfs = this.enableIpfs;
        c.ipfsurl = this.ipfsurl;
        c.irSendCount = this.irSendCount;
        c.useDiscordRPC = this.useDiscordRPC;
        c.setClipboardScreenshot = this.setClipboardScreenshot;
        c.androidUnlimitedFPS = this.androidUnlimitedFPS;
        c.androidStableFPS = this.androidStableFPS;
        c.showTouchKey = this.showTouchKey;
        return c;
    }

    public Config copy(Config changes) {
        Config c = new Config();
        c.playername = changes.playername != null ? changes.playername : this.playername;
        c.displaymode = changes.displaymode != null ? changes.displaymode : this.displaymode;
        c.vsync = changes.vsync;
        c.resolution = changes.resolution != null ? changes.resolution : this.resolution;
        c.useResolution = changes.useResolution;
        c.windowWidth = changes.windowWidth;
        c.windowHeight = changes.windowHeight;
        c.folderlamp = changes.folderlamp;
        c.audio = changes.audio != null ? changes.audio.copy() : this.audio;
        c.maxFramePerSecond = changes.maxFramePerSecond;
        c.prepareFramePerSecond = changes.prepareFramePerSecond;
        c.maxSearchBarCount = changes.maxSearchBarCount;
        c.showNoSongExistingBar = changes.showNoSongExistingBar;
        c.scrolldurationlow = changes.scrolldurationlow;
        c.scrolldurationhigh = changes.scrolldurationhigh;
        c.analogScroll = changes.analogScroll;
        c.analogTicksPerScroll = changes.analogTicksPerScroll;
        c.songPreview = changes.songPreview != null ? changes.songPreview : this.songPreview;
        c.cacheSkinImage = changes.cacheSkinImage;
        c.useSongInfo = changes.useSongInfo;
        c.songpath = changes.songpath != null ? changes.songpath : this.songpath;
        c.songinfopath = changes.songinfopath != null ? changes.songinfopath : this.songinfopath;
        c.tablepath = changes.tablepath != null ? changes.tablepath : this.tablepath;
        c.playerpath = changes.playerpath != null ? changes.playerpath : this.playerpath;
        c.skinpath = changes.skinpath != null ? changes.skinpath : this.skinpath;
        c.bgmpath = changes.bgmpath != null ? changes.bgmpath : this.bgmpath;
        c.soundpath = changes.soundpath != null ? changes.soundpath : this.soundpath;
        c.systemfontpath = changes.systemfontpath != null ? changes.systemfontpath : this.systemfontpath;
        c.messagefontpath = changes.messagefontpath != null ? changes.messagefontpath : this.messagefontpath;
        c.bmsroot = changes.bmsroot != null ? changes.bmsroot : this.bmsroot;
        c.tableURL = changes.tableURL != null ? changes.tableURL : this.tableURL;
        c.bga = changes.bga;
        c.bgaExpand = changes.bgaExpand;
        c.frameskip = changes.frameskip;
        c.updatesong = changes.updatesong;
        c.skinPixmapGen = changes.skinPixmapGen;
        c.stagefilePixmapGen = changes.stagefilePixmapGen;
        c.bannerPixmapGen = changes.bannerPixmapGen;
        c.songResourceGen = changes.songResourceGen;
        c.enableIpfs = changes.enableIpfs;
        c.ipfsurl = changes.ipfsurl != null ? changes.ipfsurl : this.ipfsurl;
        c.irSendCount = changes.irSendCount;
        c.useDiscordRPC = changes.useDiscordRPC;
        c.setClipboardScreenshot = changes.setClipboardScreenshot;
        c.androidUnlimitedFPS = changes.androidUnlimitedFPS;
        c.androidStableFPS = changes.androidStableFPS;
        c.showTouchKey = changes.showTouchKey;
        return c;
    }
}
