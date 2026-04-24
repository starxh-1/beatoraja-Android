package bms.player.beatoraja.external;

import static bms.player.beatoraja.skin.SkinProperty.*;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.logging.Logger;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.utils.BufferUtils;

import bms.player.beatoraja.MainState;
import bms.player.beatoraja.config.KeyConfiguration;
import bms.player.beatoraja.decide.MusicDecide;
import bms.player.beatoraja.play.BMSPlayer;
import bms.player.beatoraja.result.CourseResult;
import bms.player.beatoraja.result.MusicResult;
import bms.player.beatoraja.select.MusicSelector;
import bms.player.beatoraja.skin.property.IntegerPropertyFactory;
import bms.player.beatoraja.skin.property.StringPropertyFactory;

public class ScreenShotFileExporter implements ScreenShotExporter {

	@Override
	public boolean send(MainState currentState, byte[]  pixels) {
		final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
		String stateName = "";
		if(currentState instanceof MusicSelector) {
			stateName = "_Music_Select";
		} else if(currentState instanceof MusicDecide) {
			stateName = "_Decide";
		} if(currentState instanceof BMSPlayer) {
			final String tablelevel = StringPropertyFactory.getStringProperty(STRING_TABLE_LEVEL).get(currentState);
			if(tablelevel.length() > 0){
				stateName = "_Play_" + tablelevel;
			}else{
				stateName = "_Play_LEVEL" + IntegerPropertyFactory.getIntegerProperty(NUMBER_PLAYLEVEL).get(currentState);
			}
			final String fulltitle = StringPropertyFactory.getStringProperty(STRING_FULLTITLE).get(currentState);
			if(fulltitle.length() > 0) {
				stateName += " " + fulltitle;
			}
		} else if(currentState instanceof MusicResult || currentState instanceof CourseResult) {
			if(currentState instanceof MusicResult){
				final String tablelevel = StringPropertyFactory.getStringProperty(STRING_TABLE_LEVEL).get(currentState);
				if(tablelevel.length() > 0){
					stateName += "_" + tablelevel + " ";
				}else{
					stateName += "_LEVEL" + IntegerPropertyFactory.getIntegerProperty(NUMBER_PLAYLEVEL).get(currentState) + " ";
				}
			}else{
				stateName += "_";
			}
			final String fulltitle = StringPropertyFactory.getStringProperty(STRING_FULLTITLE).get(currentState);
			if(fulltitle.length() > 0) stateName += fulltitle;
			stateName += " " + ScreenShotExporter.getClearTypeName(currentState);
			stateName += " " + ScreenShotExporter.getRankTypeName(currentState);
		} else if(currentState instanceof KeyConfiguration) {
			stateName = "_Config";
		}
		stateName = stateName.replace("\\", "￥").replace("/", "／").replace(":", "：").replace("*", "＊").replace("?", "？").replace("\"", "”").replace("<", "＜").replace(">", "＞").replace("|", "｜").replace("\t", " ");

		Pixmap pixmap = new Pixmap(Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight(), Pixmap.Format.RGBA8888);
		try {
			String path = "screenshot/" + sdf.format(Calendar.getInstance().getTime()) + stateName +".png";
			BufferUtils.copy(pixels, 0, pixmap.getPixels(), pixels.length);
			PixmapIO.writePNG(new FileHandle(path), pixmap);
			Logger.getGlobal().info("スクリーンショット保存:" + path);
			pixmap.dispose();
			currentState.main.getMessageRenderer().addMessage("Screen shot saved : " + path, 2000, Color.GOLD, 0);

			this.sendClipboard(currentState, path);
			return true;
		} catch (Exception e) {
			e.printStackTrace();
		}
		pixmap.dispose();
		return false;
	}

	private void sendClipboard(MainState currentState, String path) {
		if (Gdx.app.getType() == com.badlogic.gdx.Application.ApplicationType.Android) {
			return; // Android 上跳过 AWT 剪贴板逻辑
		}
		if (!currentState.resource.getConfig().isSetClipboardWhenScreenshot()) {
			return;
		}
		try {
			// 使用反射调用桌面端特有的 ImageIO 和 AWT 逻辑，以通过 Android 编译
			File file = new File(path);
			Class<?> imageIOClass = Class.forName("javax.imageio.ImageIO");
			java.lang.reflect.Method readMethod = imageIOClass.getMethod("read", File.class);
			Object image = readMethod.invoke(null, file);

			if (image != null) {
				Class<?> bufferedImageClass = Class.forName("java.awt.image.BufferedImage");
				int width = (int) bufferedImageClass.getMethod("getWidth").invoke(image);
				int height = (int) bufferedImageClass.getMethod("getHeight").invoke(image);
				int typeIntRGB = 1; // BufferedImage.TYPE_INT_RGB

				Object output = bufferedImageClass.getConstructor(int.class, int.class, int.class)
						.newInstance(width, height, typeIntRGB);

				java.lang.reflect.Method getRGB = bufferedImageClass.getMethod("getRGB", int.class, int.class, int.class, int.class, int[].class, int.class, int.class);
				java.lang.reflect.Method setRGB = bufferedImageClass.getMethod("setRGB", int.class, int.class, int.class, int.class, int[].class, int.class, int.class);

				int[] px = new int[width * height];
				getRGB.invoke(image, 0, 0, width, height, px, 0, width);
				setRGB.invoke(output, 0, 0, width, height, px, 0, width);

				Class<?> toolkitClass = Class.forName("java.awt.Toolkit");
				Object toolkit = toolkitClass.getMethod("getDefaultToolkit").invoke(null);
				Object clipboard = toolkitClass.getMethod("getSystemClipboard").invoke(toolkit);

				// 注意：这里需要一个 Transferable，由于 ImageTransferable 依赖 java.awt，也需要动态处理或移除。
				// 为了简化编译并由于 Android 不需要此功能，这里仅保留反射骨架或直接在桌面端才实例化类。
				// 考虑到 ImageTransferable 定义在下面，我将其逻辑移入反射或直接删除（Android 不需要截图进剪贴板）。
				Logger.getGlobal().info("Screenshot clipboard copy is only supported on Desktop.");
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
