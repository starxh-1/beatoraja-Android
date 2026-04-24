package bms.player.beatoraja.song;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;

public class SongUtils {

    private static final int Polynomial = 0xEDB88320;

    public static String crc32(String path, String[] rootdirs, String bmspath) {
        if (path == null) return "0";
        path = path.replace('\\', '/');

        for (String s : rootdirs) {
            String rs = s.replace('\\', '/');
            if (rs.endsWith("/")) rs = rs.substring(0, rs.length() - 1);

            int lastIndex = rs.lastIndexOf('/');
            String parent = (lastIndex == -1) ? "" : rs.substring(0, lastIndex);

            if (parent.equals(path)) {
                return "e2977170";
            }
        }

        if (bmspath != null && path.startsWith(bmspath)) {
            path = path.substring(bmspath.length());
            if (path.startsWith("/")) path = path.substring(1);
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
