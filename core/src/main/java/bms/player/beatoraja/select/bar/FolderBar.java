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

        final SongDatabaseAccessor songdb = selector.getSongDatabase();
        final SongData[] songs = songdb.getSongDatas("parent", crc);
        if (songs.length > 0) {
            cachedChildren = SongBar.toSongBarArray(songs);
            childrenLoadState = ChildrenLoadState.LOADED;
            Gdx.app.log("FolderBar", "Loaded " + cachedChildren.length + " song(s) for folder: " + (folder != null ? folder.getTitle() : "[root]"));
        } else {
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
        }

        return cachedChildren;
    }

    public void updateFolderStatus() {
        // 对于根文件夹或子节点已加载的文件夹，从缓存的子节点中提取歌曲数据
        if (childrenLoadState == ChildrenLoadState.LOADED || childrenLoadState == ChildrenLoadState.LOADED_EMPTY) {
            if (cachedChildren == null) return;
            // 从缓存的子节点中提取歌曲数据
            List<SongData> songs = new ArrayList<>();
            for (Bar b : cachedChildren) {
                if (b instanceof SongBar) {
                    songs.add(((SongBar) b).getSongData());
                } else if (b instanceof FolderBar) {
                    // 对于子文件夹，递归获取其歌曲
                    FolderBar subFolder = (FolderBar) b;
                    if (subFolder.childrenLoadState == ChildrenLoadState.LOADED) {
                        for (Bar subBar : subFolder.cachedChildren) {
                            if (subBar instanceof SongBar) {
                                songs.add(((SongBar) subBar).getSongData());
                            }
                        }
                    }
                }
            }
            if (!songs.isEmpty()) {
                updateFolderStatus(songs.toArray(SongData.EMPTY));
            }
        } else {
            // 如果子节点还没加载，先加载子节点
            getChildren();
            // 然后重试更新
            if (childrenLoadState == ChildrenLoadState.LOADED) {
                updateFolderStatus();
            }
        }
    }

    /**
     * 强制刷新子节点缓存
     */
    public void clearChildrenCache() {
        this.childrenLoadState = ChildrenLoadState.UNLOADED;
        this.cachedChildren = null;
    }
}
