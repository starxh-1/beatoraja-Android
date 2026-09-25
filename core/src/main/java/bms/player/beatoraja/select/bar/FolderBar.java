package bms.player.beatoraja.select.bar;

import bms.player.beatoraja.select.MusicSelector;
import bms.player.beatoraja.song.*;

import com.badlogic.gdx.Gdx;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * ファイルシステムと連動したフォルダバー。
 *
 * @author exch
 */
public class FolderBar extends DirectoryBar {

    private final FolderData folder;
    private final String crc;

    public FolderBar(MusicSelector selector, FolderData folder, String crc) {
        super(selector);
        this.folder = folder;
        this.crc = crc;
        this.childrenLoadState = ChildrenLoadState.UNLOADED;
        this.cachedChildren = null;
    }

    public final FolderData getFolderData() {
        return folder;
    }

    public final String getCRC() {
        return crc;
    }

    /**
     * 检查这是否是根文件夹（folder 为 null 的特殊情况）
     */
    public boolean isRootFolder() {
        return folder == null;
    }

    @Override
    public final String getTitle() {
        if (folder == null) {
            // 根文件夹的特殊情况
            return "";
        }
        return folder.getTitle();
    }

    @Override
    public Bar[] getChildren() {
        // 防止加载循环和重复查询
        if (childrenLoadState == ChildrenLoadState.LOADING) {
            Gdx.app.log("FolderBar", "Loading already in progress for folder: " + (folder != null ? folder.getTitle() : "[root]") + ", returning empty");
            return Bar.EMPTY;
        }

        if (childrenLoadState == ChildrenLoadState.LOADED) {
            return cachedChildren;
        }

        if (childrenLoadState == ChildrenLoadState.LOADED_EMPTY) {
            return Bar.EMPTY;
        }

        // 标记为加载中
        childrenLoadState = ChildrenLoadState.LOADING;
        Gdx.app.log("FolderBar", "Loading children for folder: " + (folder != null ? folder.getTitle() : "[root]") + ", CRC: " + crc);

        // 关键修复：getChildren 内部的所有 DB 查询都已被 try-catch 包装（返回空数组），
        // 但下面的代码仍可能抛运行时异常（Stream、crc32 等）。
        // 任何一个异常都会让 FolderBar 永久卡在 LOADING 状态，
        // 后续调用 getChildren 直接返回 EMPTY，导致子目录/歌曲消失。
        // 解决方案：捕获异常并重置为 UNLOADED，让下次调用可以重试。
        final SongDatabaseAccessor songdb = selector.getSongDatabase();
        final SongData[] songs;
        try {
            songs = songdb.getSongDatas("parent", crc);
        } catch (Throwable t) {
            Gdx.app.error("FolderBar", "getSongDatas failed for folder: " + (folder != null ? folder.getTitle() : "[root]") + ", reset to UNLOADED", t);
            childrenLoadState = ChildrenLoadState.UNLOADED;
            return Bar.EMPTY;
        }
        if (songs.length > 0) {
            cachedChildren = SongBar.toSongBarArray(songs);
            childrenLoadState = ChildrenLoadState.LOADED;
            Gdx.app.log("FolderBar", "Loaded " + cachedChildren.length + " song(s) for folder: " + (folder != null ? folder.getTitle() : "[root]"));
        } else {
            try {
                String[] bmsroot = songdb.getBmsRoot();

                // 优化：预先规范化所有根目录，避免在 map 循环中重复处理
                final List<String> normalizedRoots = new ArrayList<>();
                if (bmsroot != null) {
                    for (String root : bmsroot) {
                        if (root == null) continue;
                        String r1 = root.replace('\\', '/');
                        String r2 = (r1.endsWith("/") && r1.length() > 1) ? r1.substring(0, r1.length() - 1) : r1;
                        normalizedRoots.add(r2);
                    }
                }
                final String[] normalizedRootsArray = normalizedRoots.toArray(new String[0]);

                cachedChildren = Stream.of(songdb.getFolderDatas("parent", crc)).map(folderData -> {
                    String rawPath = folderData.getPath();
                    String path = rawPath.endsWith(File.separator) ? rawPath.substring(0, rawPath.length() - 1) : rawPath;

                    // 寻找匹配的 BMS 根目录
                    String normalizedPath = path.replace('\\', '/');
                    String matchingRoot = "";
                    for (String r : normalizedRootsArray) {
                        if (normalizedPath.startsWith(r) && r.length() > matchingRoot.length()) {
                            matchingRoot = r;
                        }
                    }

                    String ccrc = SongUtils.crc32(path, normalizedRootsArray, matchingRoot);
                    return new FolderBar(selector, folderData, ccrc);
                }).toArray(Bar[]::new);

                if (cachedChildren.length == 0) {
                    childrenLoadState = ChildrenLoadState.LOADED_EMPTY;
                    Gdx.app.log("FolderBar", "Loaded empty folder: " + (folder != null ? folder.getTitle() : "[root]"));
                } else {
                    childrenLoadState = ChildrenLoadState.LOADED;
                    Gdx.app.log("FolderBar", "Loaded " + cachedChildren.length + " subfolder(s) for folder: " + (folder != null ? folder.getTitle() : "[root]"));
                }
            } catch (Throwable t) {
                Gdx.app.error("FolderBar", "Failed to load subfolders for: " + (folder != null ? folder.getTitle() : "[root]") + ", reset to UNLOADED", t);
                cachedChildren = Bar.EMPTY;
                childrenLoadState = ChildrenLoadState.UNLOADED;
            }
        }

        return cachedChildren;
    }

    /**
     * フォルダ内の全譜面を DB から直接引き、クリアランプ集計（lamps[]）を更新する。
     *
     * <p>🔴 <b>必ず DB に問い合わせること。</b>以前は「{@code cachedChildren} から曲を集めて
     * {@code updateFolderStatus(songs)} を呼ぶ」実装だったが、集計が不完全になる:</p>
     * <ul>
     *   <li>キャッシュ済みの子からしか集めないので、<b>未ロードの子フォルダの曲が丸ごと落ちる</b>
     *       （このメソッドは {@code BarManager} が<b>別スレッド</b>から呼ぶので、
     *       その時点で子が {@code UNLOADED}/{@code LOADING} なのは普通に起きる）。</li>
     *   <li>しかも子フォルダは<b>1 階層しか</b>掘らない → 孫以降の曲が全部落ちる。</li>
     *   <li>{@code if (!songs.isEmpty())} なので空集合のとき {@code lamps[]} が
     *       <b>全 0 のまま</b>残る。</li>
     * </ul>
     * <p>上游 beatoraja は {@code songdb.getSongDatas("parent", ccrc)} で<b>全曲を直接取る</b>。
     * そちらに合わせる（キャッシュは {@code getChildren()} の表示用であって、
     * lamp 集計の入力ではない）。</p>
     *
     * <p>注: これは「表示中の folder lamp の色が違う」という症状の<b>原因ではなかった</b>
     * （原因は描画側の話で、当該フォルダのスコアが無い場合はどのみち集計は空になる）。
     * 純粋に<b>集計の網羅性</b>の修正として独立に成立する。</p>
     */
    public void updateFolderStatus() {
        final SongDatabaseAccessor songdb = selector.getSongDatabase();
        String path = folder.getPath();
        if (path.endsWith(String.valueOf(File.separatorChar))) {
            path = path.substring(0, path.length() - 1);
        }
        final String ccrc = SongUtils.crc32(path, new String[0], new File(".").getAbsolutePath());

        updateFolderStatus(songdb.getSongDatas("parent", ccrc));
    }

    /**
     * 强制刷新子节点缓存
     */
    public void clearChildrenCache() {
        this.childrenLoadState = ChildrenLoadState.UNLOADED;
        this.cachedChildren = null;
    }
}
