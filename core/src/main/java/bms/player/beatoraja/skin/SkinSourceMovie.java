package bms.player.beatoraja.skin;

import java.util.Optional;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

import bms.player.beatoraja.MainState;
import bms.player.beatoraja.play.bga.GdxVideoProcessor;
import bms.player.beatoraja.play.bga.MovieProcessor;

/**
 * スキンのソースイメージ(ムービー)
 *
 * @author exch
 */
public class SkinSourceMovie extends SkinSource {

	/**
	 * 视频处理器（Android 使用 gdx-video）
	 */
	private MovieProcessor image;

	private boolean playing;

	private final int timer;

	private final TextureRegion region = new TextureRegion();

	public SkinSourceMovie(String s) {
		this(s, 0);
	}

	public SkinSourceMovie(String s, int timer) {
		// Android: 使用 gdx-video 硬件解码
		if (Gdx.app.getType() == Application.ApplicationType.Android) {
			GdxVideoProcessor gvp = new GdxVideoProcessor();
			gvp.create(s);
			image = gvp;
		} else {
			// Desktop: 视频播放已不支持
			image = null;
		}
		this.timer = timer;
	}

	public boolean validate() {
		return true;
	}

	public TextureRegion getImage(long time, MainState state) {
		if (image == null) return null;
		if(!playing) {
			image.play(time, true);
			playing = true;
		}
		image.update(time);
		Texture tex = image.getFrame();
		if(tex != null) {
			region.setTexture(tex);
			region.setRegion(tex);
			return region;
		}
		return null;
	}

	public MovieProcessor getMovieProcessor() {
		return image;
	}

	public void dispose() {
    	if(isNotDisposed()) {
    		Optional.ofNullable(image).ifPresent(MovieProcessor::dispose);
    		setDisposed();
    	}
	}
}
