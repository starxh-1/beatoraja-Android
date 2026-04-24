package bms.player.beatoraja;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import bms.player.beatoraja.song.SQLiteSongDatabaseAccessor;
import bms.player.beatoraja.song.SongDatabaseAccessor;

/**
 * Android 适配版：彻底剥离 JavaFX、Swing 和 LWJGL 桌面端启动逻辑
 * 仅保留为 MainController 提供静态全局变量和数据库单例的功能
 */
public class MainLoader {

    private static SongDatabaseAccessor songdb;
    private static final Set<String> illegalSongs = new HashSet<String>();
    private static Path bmsPath;
    private static VersionChecker version;

    // 移除了 main() 和 start() 方法，因为 Android 的启动入口是 AndroidLauncher.java

    public static SongDatabaseAccessor getScoreDatabaseAccessor() {
        if(songdb == null) {
            try {
                Config config = Config.read();
                String songPath = config.getSongpath();
                // Android 路径修正
                if (com.badlogic.gdx.Gdx.app.getType() == com.badlogic.gdx.Application.ApplicationType.Android) {
                    if (!songPath.startsWith("/")) {
                        songPath = com.badlogic.gdx.Gdx.files.getLocalStoragePath() + songPath;
                    }
                }
                // If Android has provided a custom accessor (via MainLoader.setSongDatabaseAccessor), use it
                if (songdb == null) {
                    // fallback to default JDBC-based accessor
                    songdb = new SQLiteSongDatabaseAccessor(songPath, config.getBmsroot());
                }
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }
        }
        return songdb;
    }

    /**
     * Allow Android launcher to set a platform-specific SongDatabaseAccessor instance
     */
    public static void setSongDatabaseAccessor(SongDatabaseAccessor accessor) {
        if (accessor != null) songdb = accessor;
    }

    public static VersionChecker getVersionChecker() {
        if(version == null) {
            version = new DummyVersionChecker();
        }
        return version;
    }

    public static void setVersionChecker(VersionChecker version) {
        if(version != null) {
            MainLoader.version = version;
        }
    }

    public static Path getBMSPath() {
        return bmsPath;
    }

    public static void putIllegalSong(String hash) {
        illegalSongs.add(hash);
    }

    public static String[] getIllegalSongs() {
        return illegalSongs.toArray(new String[illegalSongs.size()]);
    }

    public static int getIllegalSongCount() {
        return illegalSongs.size();
    }

    public interface VersionChecker {
        public String getMessage();
        public String getDownloadURL();
    }

    // 移除了依赖桌面端网络的 GithubVersionChecker，改用 Dummy 防止报错
    private static class DummyVersionChecker implements VersionChecker {
        public String getMessage() {
            return "Android Version";
        }
        public String getDownloadURL() {
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown=true)
    static class GithubLastestRelease{
        public String name;
    }
}
