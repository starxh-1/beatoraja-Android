package bms.player.beatoraja.song;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;

public class SongUtils {

    private static final int Polynomial = 0xEDB88320;

    public static String crc32(String path, String[] rootdirs, String bmspath) {
        if (path == null) return "0";
        path = path.replace('\\', '/');
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);

        // 优先检查是否是根目录的父目录，返回特殊的 root CRC
        for (String s : rootdirs) {
            if (s == null) continue;
            String rs = s.replace('\\', '/');
            if (rs.endsWith("/")) rs = rs.substring(0, rs.length() - 1);

            int lastIndex = rs.lastIndexOf('/');
            String parent = (lastIndex == -1) ? "" : rs.substring(0, lastIndex);

            if (parent.equals(path)) {
                return "e2977170";
            }
        }

        // 修改逻辑：计算相对于“根目录父目录”的路径，从而保留根目录文件夹名作为唯一标识
        if (bmspath != null) {
            bmspath = bmspath.replace('\\', '/');
            if (bmspath.endsWith("/")) bmspath = bmspath.substring(0, bmspath.length() - 1);

            int lastSlash = bmspath.lastIndexOf('/');
            String rootParent = (lastSlash == -1) ? "" : bmspath.substring(0, lastSlash);

            if (!rootParent.isEmpty() && path.startsWith(rootParent)) {
                path = path.substring(rootParent.length());
                if (path.startsWith("/")) path = path.substring(1);
            } else if (path.startsWith(bmspath)) {
                // 如果父目录匹配不上，退回到相对于根目录的逻辑
                path = path.substring(bmspath.length());
                if (path.startsWith("/")) path = path.substring(1);
            }
        }

        int crc = 0xFFFFFFFF;
        // 模拟原版的 "\\\0" 结尾和字节处理逻辑
        String target = path.replace('/', '\\') + "\\\0";
        for (byte b : target.getBytes()) {
            crc ^= (b & 0xFF);
            for (int j = 0; j < 8; j++) {
                if ((crc & 1) != 0) {
                    crc = (crc >>> 1) ^ Polynomial;
                } else {
                    crc = crc >>> 1;
                }
            }
        }
        return Integer.toHexString(~crc);
    }
}
