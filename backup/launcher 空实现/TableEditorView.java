package bms.player.beatoraja.launcher;

import java.nio.file.Path;
import java.net.URL;
import java.util.ResourceBundle;
import bms.player.beatoraja.song.SongDatabaseAccessor;
import bms.player.beatoraja.song.SongData;

/**
 * Android 适配版：彻底剥离 JavaFX 和 AWT 依赖
 */
public class TableEditorView {

    public void initialize(URL arg0, ResourceBundle arg1) {}

    protected void init(SongDatabaseAccessor songdb) {}

    public void update(Path p) {}

    public void commit() {}

    public static boolean isMd5OrSha256Hash(String text) {
        return false;
    }

    protected static void displayChartDetailsDialog(SongDatabaseAccessor songdb, SongData song, String... extraData) {}
}
