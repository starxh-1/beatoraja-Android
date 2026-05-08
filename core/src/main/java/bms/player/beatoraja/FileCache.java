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

            // 缓存键包含文件类型，防止 internal 和 absolute 路径冲突
            String cacheKey = file.type() + ":" + parent.path();
            Set<String> files = dirCache.get(cacheKey);
            if (files == null) {
                if (!parent.exists()) {
                    // 如果父目录都不存在，缓存一个空集合并返回 false
                    dirCache.put(cacheKey, new HashSet<>());
                    return false;
                }
                files = new HashSet<>();
                FileHandle[] list = parent.list();
                if (list != null) {
                    for (FileHandle f : list) {
                        files.add(f.name().toLowerCase(Locale.ROOT));
                    }
                }
                dirCache.put(cacheKey, files);
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
