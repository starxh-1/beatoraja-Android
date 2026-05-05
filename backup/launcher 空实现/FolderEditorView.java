package bms.player.beatoraja.launcher;

import java.net.URL;
import java.util.ResourceBundle;

import bms.player.beatoraja.TableData.TableFolder;
import bms.player.beatoraja.song.SongDatabaseAccessor;

/**
 * Android 适配版：彻底剥离 JavaFX 依赖
 * 文件夹编辑器在手机端暂不可用，保留空方法外壳防止外部调用报错
 */
public class FolderEditorView {

    private SongDatabaseAccessor songdb;

    public void initialize(URL arg0, ResourceBundle arg1) {
        // 空实现
    }

    protected void init(SongDatabaseAccessor songdb) {
        this.songdb = songdb;
    }

    public void searchSongs() {
        // 空实现
    }

    public void updateTableFolder() {
        // 空实现
    }

    public void addTableFolder() {
        // 空实现
    }

    public void removeTableFolder() {
        // 空实现
    }

    public void moveTableFolderUp() {
        // 空实现
    }

    public void moveTableFolderDown() {
        // 空实现
    }

    public void addSongData() {
        // 空实现
    }

    public void removeSongData() {
        // 空实现
    }

    public void moveSongDataUp() {
        // 空实现
    }

    public void moveSongDataDown() {
        // 空实现
    }

    public TableFolder[] getTableFolder() {
        return new TableFolder[0];
    }

    public void setTableFolder(TableFolder[] folder) {
        // 空实现
    }
}
