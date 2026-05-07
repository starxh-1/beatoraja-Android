package bms.player.beatoraja;

import com.badlogic.gdx.files.FileHandle;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Android 性能优化：文件系统索引缓存
 * 避免在循环中频繁调用 File.exists()
 */
public class FileCache {
    private static final Map<String, Set<String>> dirCache = new ConcurrentHashMap<>();

    public static void clear() {
        dirCache.clear();
    }

    public static boolean exists(FileHandle file) {
        if (file == null) return false;
        try {
            FileHandle parent = file.parent();
            if (parent == null || parent.path().isEmpty()) return file.exists();

            String parentPath = parent.path();
            Set<String> files = dirCache.get(parentPath);
            if (files == null) {
                if (!parent.exists()) {
                    return file.exists();
                }
                files = new HashSet<>();
                FileHandle[] list = parent.list();
                if (list != null) {
                    for (FileHandle f : list) {
                        files.add(f.name().toLowerCase(Locale.ROOT));
                    }
                }
                dirCache.put(parentPath, files);
            }
            if (files.isEmpty()) {
                return file.exists();
            }
            return files.contains(file.name().toLowerCase(Locale.ROOT));
        } catch (Exception e) {
            return file.exists();
        }
    }
}
