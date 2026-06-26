package bms.player.beatoraja.skin.lr2;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;

import bms.player.beatoraja.skin.SkinTextImage;
import bms.player.beatoraja.skin.lr2.LR2SkinLoader.Command;

/**
 * LR2のスキン定義用csvファイルのローダー
 *
 * @author exch
 */
public class LR2FontLoader extends LR2SkinLoader {
	private static final Charset MS932 = Charset.forName("MS932");
	private static final Charset SHIFT_JIS = Charset.forName("Shift_JIS");

	/**
	 * 生成するテキストイメージソース
	 */
	SkinTextImage.SkinTextImageSource textimage;
	/**
	 * lr2fontファイルパス
	 */
	File path;

	private final boolean usecim;

	public LR2FontLoader(boolean usecim) {
		this.usecim = usecim;
		addCommandWord(FontCommand.values());
	}

	protected SkinTextImage.SkinTextImageSource loadFont(File p) throws IOException {
		textimage = new SkinTextImage.SkinTextImageSource(usecim);
		this.path = p;

//		long l = System.nanoTime();
		try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(p), MS932))) {
			br.lines().forEach(line -> {
				processLine(line, null);
			});
		}
//		System.out.println(p.toString() + " -> " + (System.nanoTime() - l));

		return textimage;
	}

	protected static int[] parseInt(String[] s) {
		int[] result = new int[22];
		for (int i = 1; i < result.length && i < s.length; i++) {
			try {
				String val = s[i].trim();
				if (val.isEmpty()) continue;
				// 优化：避免使用正则表达式 replaceAll，改用普通 replace
				result[i] = Integer.parseInt(val.replace('!', '-').replace(" ", ""));
			} catch (Exception e) {

			}
		}
		return result;
	}
}

enum FontCommand implements Command<LR2FontLoader> {
	// size
	S ((loader, str) -> {
		loader.textimage.setSize(Integer.parseInt(str[1]));
	}),
	// margin
	M ((loader, str) -> {
		loader.textimage.setMargin(Integer.parseInt(str[1]));
	}),
	// texture
	T ((loader, str) -> {
		File imagefile = new File(loader.path.getParent(), str[2]);
		boolean exists;

		if (com.badlogic.gdx.Gdx.app.getType() == com.badlogic.gdx.Application.ApplicationType.Android) {
			// Android: 核心优化 - 移除极其缓慢的 Gdx.files.internal(...).exists()
			// 直接使用 PixmapResourcePool 提供的 O(1) 大小写不敏感缓存来定位文件
			java.io.File parentDir = imagefile.getParentFile();
			String parentPath = parentDir != null ? parentDir.getAbsolutePath() : "";
			String actualName = bms.player.beatoraja.PixmapResourcePool.findFileIgnoreCase(parentPath, imagefile.getName());
			if (actualName != null) {
				imagefile = new File(parentDir, actualName);
				exists = true;
			} else {
				// 只有在缓存未命中时才回退到系统级检查
				String path = imagefile.getPath().replace("\\", "/");
				exists = com.badlogic.gdx.Gdx.files.internal(path).exists() || com.badlogic.gdx.Gdx.files.absolute(path).exists();
			}
		} else {
			exists = imagefile.exists();
		}

		if (exists) {
			loader.textimage.setPath(Integer.parseInt(str[1]), imagefile.getPath());
		}
	}),
	// reference
	R ((loader, str) -> {
		try {
			int[] values = LR2FontLoader.parseInt(str);
			if (loader.textimage.getPath(values[2]) != null) {
				// System.out.println("Font loaded : " + values[1]);
                for(int code : mapCode(values[1])) {
                	loader.textimage.setImage(code, values[2], values[3], values[4], values[5], values[6]);
                }
			}
		} catch (Throwable e) {
			e.printStackTrace();
		}
	});

	private static final Charset SHIFT_JIS = Charset.forName("Shift_JIS");

	private static int[] mapCode(int code) {
		int sjiscode = code;
		byte[] sjisbyte;
        if (code == 288) {
            return new int[]{0x0000301c, 0x0000ff5e};
//        } else if(code == 25318) {
//            return new int[]{0x0000e326, 0x00007dba};
        } else if (code >= 8127) {
            sjiscode = (char) (code + 49281);
            sjisbyte = new byte[2];
            sjisbyte[1] = (byte) (sjiscode & 0xff);
            sjisbyte[0] = (byte) ((sjiscode & 0xff00) >> 8);
        } else if (code >= 256) {
            sjiscode = (char) (code + 32832);
            sjisbyte = new byte[2];
            sjisbyte[1] = (byte) (sjiscode & 0xff);
            sjisbyte[0] = (byte) ((sjiscode & 0xff00) >> 8);
        } else {
			sjisbyte = new byte[1];
			sjisbyte[0] = (byte) (sjiscode & 0xff);
		}

		byte[] bytes = new String(sjisbyte, SHIFT_JIS).getBytes(StandardCharsets.UTF_16LE);
		int utfcode = 0;
		for (int i = 0; i < bytes.length; i++) {
			utfcode |= (bytes[i] & 0xff) << (8 * i);
		}
		return new int[]{utfcode};
	}

	public final BiConsumer<LR2FontLoader, String[]> function;

	private FontCommand(BiConsumer<LR2FontLoader, String[]> function) {
		this.function = function;
	}

	public void execute(LR2FontLoader loader, String[] str) {
		function.accept(loader, str);
	}

}
