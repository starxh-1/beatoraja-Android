package bms.player.beatoraja.launcher;

/**
 * Android 适配版：彻底移除 JavaFX 依赖
 * 桌面端的表格视图在手机端暂不可用，保留空方法外壳防止外部调用报错
 *
 * @param <T>
 */
public class EditableTableView<T> {

    public void addItem(T item) {
        // 空实现
    }

    public void removeSelectedItems() {
        // 空实现
    }

    public void moveSelectedItemsUp() {
        // 空实现
    }

    public void moveSelectedItemsDown() {
        // 空实现
    }

    public void moveSelectedItemTop() {
        // 空实现
    }

    public void moveSelectedItemBottom() {
        // 空实现
    }
}
