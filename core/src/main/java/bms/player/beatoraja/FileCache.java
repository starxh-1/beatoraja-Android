package bms.player.beatoraja;

import com.badlogic.gdx.files.FileHandle;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Android 性能优化：文件系统索引缓存
 * 避免在循环中频繁调用 File.exists()
 *
 * <p>线程约定：这是一个 GL 线程与后台写线程<b>共享</b>的静态缓存（GL 线程读、
 * {@code ReplayWriteThread} 等写完之后失效）。{@code dirCache} 用
 * {@link ConcurrentHashMap}（结构安全），值 {@code Set} 是「构建完成后一次性 put、
 * 之后不再修改」，所以读取无并发迭代问题。</p>
 */
public class FileCache {
    private static final Map<String, Set<String>> dirCache = new ConcurrentHashMap<>();

    /**
     * 失效世代。任何一次 {@link #invalidate} 都会自增，用来作废「正在构建中」的旧快照。
     *
     * <p>为什么需要它：{@link #exists} 是「get 未命中 → 列目录（慢 IO）→ put」，
     * 若在这段窗口里另一个线程写了文件并 {@code invalidate()}，那次 {@code remove}
     * 会作用在「尚未 put」的键上（空转），随后我们又把<b>写入前</b>列出的旧快照 put 进去 ——
     * 失效被吃掉，缓存里永久留着旧列表，症状还是「要重启才看得到新回放」。
     * 有了世代号就能识别这种情况并拒绝落缓存。</p>
     */
    private static final AtomicLong generation = new AtomicLong();

    public static void clear() {
        generation.incrementAndGet();
        dirCache.clear();
    }

    /**
     * 让 {@code file} 所在目录的缓存失效。
     *
     * <p>🔴 <b>写入/删除文件后必须调用</b>，否则 {@link #exists(FileHandle)} 会一直拿旧快照回答。
     * 这个缓存<b>没有过期时间</b>（唯一的失效点是 {@code BMSPlayer.create()} 与
     * {@code MainController.dispose()}），所以「先写文件、后查存在」的路径必然踩坑：
     * 最典型的就是回放 —— {@code MusicResult} 构造时就把 {@code replay/} 的目录列表
     * 缓存了（那时新 {@code .brd} 还没写），保存回放后再查永远是 false，
     * <b>要重启游戏才看得到新回放</b>。</p>
     *
     * <p>只失效<b>所在目录</b>这一条（键与 {@link #exists} 完全一致），
     * 不波及其它目录的 BGA/音频缓存。</p>
     *
     * <p>🔴 <b>执行顺序不能反</b>：先自增世代、再删条目。反过来的话，一个「已列完目录、
     * 还没 put」的线程会在 {@code remove} 之后把旧快照落回缓存，本次失效就丢了。</p>
     */
    public static void invalidate(FileHandle file) {
        if (file == null) return;
        // 先作废所有「正在构建」的快照，再删已缓存的条目。
        generation.incrementAndGet();
        try {
            FileHandle parent = file.parent();
            if (parent == null) return;
            dirCache.remove(file.type() + ":" + parent.path());
        } catch (Exception ignored) {
            // 失效失败只会让旧快照多留一会儿，不影响正确性之外的任何东西
        }
    }

    /**
     * 只在「构建期间没有任何失效发生」时落缓存。
     *
     * <p>package-private 是为了能被回归测试直接调用（验证「旧世代快照被拒绝」）。</p>
     *
     * @return 是否真的缓存了（false = 期间发生过失效，调用方应改问文件系统）
     */
    static boolean putIfFresh(String cacheKey, Set<String> files, long gen) {
        if (generation.get() != gen) return false;
        dirCache.put(cacheKey, files);
        return true;
    }

    public static boolean exists(FileHandle file) {
        if (file == null) return false;
        try {
            FileHandle parent = file.parent();
            if (parent == null || parent.path().isEmpty()) return file.exists();

            // 缓存键包含文件类型，防止 internal 和 absolute 路径冲突
            String cacheKey = file.type() + ":" + parent.path();
            final long gen = generation.get();
            Set<String> files = dirCache.get(cacheKey);
            if (files == null) {
                final Set<String> built = new HashSet<>();
                if (parent.exists()) {
                    FileHandle[] list = parent.list();
                    if (list != null) {
                        for (FileHandle f : list) {
                            built.add(f.name().toLowerCase(Locale.ROOT));
                        }
                    }
                }
                // 列表是在 gen 之后列出来的；若期间有人写/删了文件（generation 已变），
                // 这份列表可能已经过期 ⇒ 不落缓存，并把判断权交回文件系统。
                if (!putIfFresh(cacheKey, built, gen)) {
                    return file.exists();
                }
                files = built;
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
