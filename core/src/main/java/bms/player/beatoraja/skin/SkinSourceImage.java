package bms.player.beatoraja.skin;

import bms.player.beatoraja.MainState;
import bms.player.beatoraja.skin.property.TimerProperty;
import bms.player.beatoraja.skin.property.TimerPropertyFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

/**
 * スキンのソースイメージ
 *
 * @author exch
 */
public final class SkinSourceImage extends SkinSource {

	/**
	 * イメージ
	 */
	private TextureRegion[] image;

	private final TimerProperty timer;

	private final int cycle;

	public SkinSourceImage(TextureRegion image) {
		this(new TextureRegion[] { image }, 0, 0);
	}

	public SkinSourceImage(TextureRegion[] image, int timer, int cycle) {
		this(image, timer > 0 ? TimerPropertyFactory.getTimerProperty(timer) : null, cycle);
	}

	public SkinSourceImage(TextureRegion[] image, TimerProperty timer, int cycle) {
		this.image = image;
		this.timer = timer;
		this.cycle = cycle;
	}

	/**
	 * Path-based lazy constructor. Texture is loaded on first {@link #getImage(long, MainState)}.
	 * Mirrors the eager {@code getSourceImage(Texture, x, y, w, h, divx, divy)} layout from
	 * {@code LR2SkinCSVLoader}: divides the source texture into a {@code divx * divy} grid.
	 */
	public SkinSourceImage(String texturePath, int x, int y, int w, int h,
						   int divx, int divy, int timer, int cycle, boolean usecim) {
		this.texturePath = texturePath;
		this.srcX = x;
		this.srcY = y;
		this.srcW = w;
		this.srcH = h;
		this.divx = divx > 0 ? divx : 1;
		this.divy = divy > 0 ? divy : 1;
		this.timer = timer > 0 ? TimerPropertyFactory.getTimerProperty(timer) : null;
		this.cycle = cycle;
		this.usecim = usecim;
	}

	/**
	 * True iff constructed via the path-based constructor (texture not yet loaded).
	 * Used by {@link SkinImage#load()} to skip eager {@code cachedImage} population, which
	 * would otherwise trigger the lazy load during {@code Skin.prepare()} before drawing.
	 */
	public boolean isLazy() {
		return texturePath != null;
	}

	public boolean validate() {
		// Lazy mode: do NOT trigger texture load here. Skin.prepare() runs validate() on every
		// object before drawing, so eager loading here defeats the lazy optimization. If the
		// path is set we trust it; missing files fall back to a no-draw frame on first getImage().
		if (image == null) {
			return texturePath != null;
		}
		if(image.length == 0) {
			return false;
		}

		boolean exist = false;
		for(TextureRegion tr : image) {
			if(tr != null) {
				exist = true;
			}
		}

		if(!exist) {
			return false;
		}
		return true;
	}

	public TextureRegion getImage(long time, MainState state) {
		if (image == null && texturePath != null) {
			loadImage();
		}
		if (image != null && image.length > 0) {
			return image[getImageIndex(image.length, time, state)];
		}
		return null;
	}

	public TextureRegion[] getImages() {
		if (image == null) {
			loadImage();
		}
		return image;
	}

	private void loadImage() {
		if (texturePath == null) return;
		Texture tex = SkinLoader.getTexture(texturePath, usecim);
		if (tex == null) {
			image = new TextureRegion[0];
			return;
		}
		int w = srcW == -1 ? tex.getWidth() : srcW;
		int h = srcH == -1 ? tex.getHeight() : srcH;
		TextureRegion[] regions = new TextureRegion[divx * divy];
		for (int i = 0; i < divx; i++) {
			for (int j = 0; j < divy; j++) {
				regions[divx * j + i] = new TextureRegion(tex, srcX + w / divx * i, srcY + h / divy * j, w / divx, h / divy);
			}
		}
		image = regions;
	}

	private int getImageIndex(int length, long time, MainState state) {
		if (cycle == 0) {
			return 0;
		}

		if (timer != null) {
			if (timer.isOff(state)) {
				return 0;
			}
			time -= timer.get(state);
		}
		if (time < 0) {
			return 0;
		}
		// System.out.println(index + " / " + image.length);
		return (int) ((time * length / cycle) % length);
	}

	public void dispose() {
    	if(isNotDisposed()) {
    		Optional.ofNullable(image).ifPresent(image -> Stream.of(image).filter(Objects::nonNull).forEach(tr -> tr.getTexture().dispose()));
    		setDisposed();
    	}
	}

	// Lazy-mode fields. Only set when constructed via the path-based constructor.
	// Non-final so eager constructors can leave them untouched (Java disallows reassignment to final
	// fields even with default initializers).
	private String texturePath;
	private int srcX;
	private int srcY;
	private int srcW;
	private int srcH;
	private int divx;
	private int divy;
	private boolean usecim;

}