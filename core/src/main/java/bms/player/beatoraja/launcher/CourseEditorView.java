package bms.player.beatoraja.launcher;

import java.net.URL;
import java.util.ResourceBundle;

import bms.player.beatoraja.CourseData;
import bms.player.beatoraja.song.SongDatabaseAccessor;

/**
 * Android 适配版：阉割掉 JavaFX 桌面 UI
 * 段位编辑器在手机端暂不可用，保留空方法防止外部调用报错
 */
public class CourseEditorView {

    private SongDatabaseAccessor songdb;

    public void initialize(URL arg0, ResourceBundle arg1) {
        // 空实现
    }

    protected void setSongDatabaseAccessor(SongDatabaseAccessor songdb) {
        this.songdb = songdb;
    }

    public void searchSongs() {
        // 空实现
    }

    public CourseData[] getCourseData() {
        return new CourseData[0];
    }

    public void setCourseData(CourseData[] course) {
        // 空实现
    }

    public void updateCourseData() {
        // 空实现
    }

    public void addCourseData() {
        // 空实现
    }

    public void removeCourseData() {
        // 空实现
    }

    public void moveCourseDataUp() {
        // 空实现
    }

    public void moveCourseDataDown() {
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
}
