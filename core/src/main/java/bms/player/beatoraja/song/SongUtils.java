package bms.player.beatoraja.song;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.zip.CRC32;

public class SongUtils {

    private static final String ROOT_CRC = "e2977170";

    /**
     * 计算路径的 CRC32 值。
     * 兼容 beatoraja 的路径处理逻辑，同时使用 Java 标准库进行优化。
     */
    public static String crc32(String path, String[] rootdirs, String bmspath) {
        if (path == null) return "0";

        // 统一使用正斜杠处理，并去除末尾斜杠
        final String p1 = path.replace('\\', '/');
        final String normalizedPath = (p1.endsWith("/") && p1.length() > 1) ? p1.substring(0, p1.length() - 1) : p1;

        // 1. 检查是否是根目录的父目录
        if (rootdirs != null) {
            for (String s : rootdirs) {
                if (s == null) continue;
                final String rs1 = s.replace('\\', '/');
                final String rs = (rs1.endsWith("/") && rs1.length() > 1) ? rs1.substring(0, rs1.length() - 1) : rs1;

                final int lastIndex = rs.lastIndexOf('/');
                final String parent = (lastIndex == -1) ? "" : rs.substring(0, lastIndex);

                if (Objects.equals(parent, normalizedPath)) {
                    return ROOT_CRC;
                }
            }
        }

        // 2. 计算相对路径逻辑
        String targetPath = normalizedPath;
        if (bmspath != null) {
            final String bs1 = bmspath.replace('\\', '/');
            final String normalizedBmsPath = (bs1.endsWith("/") && bs1.length() > 1) ? bs1.substring(0, bs1.length() - 1) : bs1;

            final int lastSlash = normalizedBmsPath.lastIndexOf('/');
            final String rootParent = (lastSlash == -1) ? "" : normalizedBmsPath.substring(0, lastSlash);

            if (!rootParent.isEmpty() && normalizedPath.startsWith(rootParent)) {
                String sub = normalizedPath.substring(rootParent.length());
                targetPath = sub.startsWith("/") ? sub.substring(1) : sub;
            } else if (normalizedPath.startsWith(normalizedBmsPath)) {
                String sub = normalizedPath.substring(normalizedBmsPath.length());
                targetPath = sub.startsWith("/") ? sub.substring(1) : sub;
            }
        }

        // 3. 模拟 beatoraja 的结尾处理并计算 CRC
        // 原逻辑：path.replace('/', '\\') + "\\\0"
        final String finalTarget = targetPath.replace('/', '\\') + "\\\0";

        final CRC32 crc32 = new CRC32();
        crc32.update(finalTarget.getBytes(StandardCharsets.UTF_8));

        // beatoraja 使用的是 Integer.toHexString(~crc)，即标准的 32位 hex 字符串
        return Integer.toHexString((int) crc32.getValue());
    }
}
