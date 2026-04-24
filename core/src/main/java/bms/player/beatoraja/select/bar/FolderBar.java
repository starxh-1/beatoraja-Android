package bms.player.beatoraja.select.bar;

import bms.player.beatoraja.select.MusicSelector;
import bms.player.beatoraja.song.*;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.Array;

import java.io.File;
import java.nio.file.Paths;
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
            Gdx.app.log("FolderBar", "Already loaded as empty folder: " + (folder != null ? folder.getTitle() : "[root]") + ", returning empty");
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
            final String rootpath = Paths.get(".").toAbsolutePath().toString();
            cachedChildren = Stream.of(songdb.getFolderDatas("parent", crc)).map(folder -> {
                String path = folder.getPath();
                if (path.endsWith(String.valueOf(File.separatorChar))) {
                    path = path.substring(0, path.length() - 1);
                }

                String ccrc = SongUtils.crc32(path, new String[0], rootpath);
                return new FolderBar(selector, folder, ccrc);
            }).toArray(Bar[]::new);

            if (cachedChildren.length == 0) {
                childrenLoadState = ChildrenLoadState.LOADED_EMPTY;
                Gdx.app.log("FolderBar", "Loaded empty folder (no songs/folders): " + (folder != null ? folder.getTitle() : "[root]"));
            } else {
                childrenLoadState = ChildrenLoadState.LOADED;
                Gdx.app.log("FolderBar", "Loaded " + cachedChildren.length + " subfolder(s) for folder: " + (folder != null ? folder.getTitle() : "[root]"));
            }
        }

        return cachedChildren;
    }

    public void updateFolderStatus() {
        Gdx.app.log("FolderBar", "updateFolderStatus called for: " + (folder != null ? folder.getTitle() : "[root]"));
        
        // 对于根文件夹或子节点已加载的文件夹，从缓存的子节点中提取歌曲数据
        if (childrenLoadState == ChildrenLoadState.LOADED || childrenLoadState == ChildrenLoadState.LOADED_EMPTY) {
            Gdx.app.log("FolderBar", "Using cached children for status update");
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
            if (songs.size() > 0) {
                Gdx.app.log("FolderBar", "Updating folder status with " + songs.size() + " songs");
                updateFolderStatus(songs.toArray(SongData.EMPTY));
            } else {
                Gdx.app.log("FolderBar", "No songs found in cache");
            }
        } else {
            Gdx.app.log("FolderBar", "Children not loaded, triggering load for status update");
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
