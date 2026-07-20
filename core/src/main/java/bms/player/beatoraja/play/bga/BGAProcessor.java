package bms.player.beatoraja.play.bga;

import java.io.File;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.logging.Logger;

import bms.model.Layer;
import bms.model.*;
import bms.player.beatoraja.Config;
import bms.player.beatoraja.FileCache;
import bms.player.beatoraja.PlayerConfig;
import bms.player.beatoraja.ResourcePool;
import bms.player.beatoraja.play.BMSPlayer;
import bms.player.beatoraja.play.SkinBGA;
import bms.player.beatoraja.skin.Skin.SkinObjectRenderer;
import bms.player.beatoraja.skin.StretchType;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.BufferUtils;

/**
 * BGAのリソース管理、描画用クラス
 *
 * @author exch
 */
public class BGAProcessor {

	// TODO イベントレイヤー対応(現状はミスレイヤーのみ)

	private Config config;
	private PlayerConfig player;
	private SpriteBatch batch;
	private volatile float progress = 0;

	private volatile MovieProcessor[] movies = new MovieProcessor[0];

	private final ResourcePool<String, MovieProcessor> mpgresource;

	public static final String[] mov_extension = { "mp4", "wmv", "m4v", "webm", "mpg", "mpeg", "m1v", "m2v", "avi"};

	/**
	 * 再生中のBGAID
	 */
	private int playingbgaid = -1;
	/**
	 * 再生中のレイヤーID
	 */
	private int playinglayerid = -1;
	/**
	 * 再生中のミスレイヤーBGA ID
	 */
	private int playingmissbgaid = -1;
	/**
	 * ミスレイヤー表示開始時間
	 */
	private long misslayertime;

	private long getMisslayerduration;
	private long bga_start_time = 0;
	private long layer_start_time = 0;
	private long missbga_start_time = 0;

	private long time;
	private long lastProcessedTime = -2;

	private BGImageProcessor cache;

	private Texture blanktex;

	// Framebuffer for offscreen BGA rendering (for transparent lane background)
	private FrameBuffer bgaFramebuffer;
	private TextureRegion bgaFramebufferRegion;
	private boolean bgaFramebufferDirty = false;

	private volatile TimeLine[] timelines = new TimeLine[0];
	private int pos;
	private TextureRegion image;
	private Rectangle tmpRect = new Rectangle();

	private final Rectangle lastBGARect = new Rectangle();
	private StretchType lastStretch = StretchType.STRETCH;
	private boolean hasLastBGAParams = false;

	private boolean rbga;
	private boolean rlayer;
	private boolean rmissbga;

	private boolean isPortrait = false;

	public void setPortrait(boolean portrait) {
		Gdx.app.log("BGAProcessor", "setPortrait(" + portrait + ")");
		this.isPortrait = portrait;
	}

	public BGAProcessor(Config config, PlayerConfig player) {
		this.config = config;
		this.player = player;
		this.batch = new SpriteBatch();

		Pixmap blank = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
		blank.setColor(Color.BLACK);
		blank.fill();
		blanktex = new Texture(blank);
		blank.dispose();

		mpgresource = new ResourcePool<String, MovieProcessor>(Math.max(config.getSongResourceGen(), 1)) {
			@Override
			protected MovieProcessor load(String key) {
				// Android: gdx-video 硬件解码（MediaCodec）处理所有格式
				GdxVideoProcessor gvp = new GdxVideoProcessor();
				gvp.create(key);
				return gvp;
			}

			@Override
			protected void dispose(MovieProcessor resource) {
				resource.dispose();
			}
		};
		cache = new BGImageProcessor(256, Math.max(config.getSongResourceGen(), 1));
		image = new TextureRegion();
	}

	public synchronized void setModel(BMSModel model) {
		progress = 0;

		// 停止上一首歌的所有BGA视频播放器，释放硬件解码器
		for (MovieProcessor mp : movies) {
			if (mp != null) {
				mp.stop();
			}
		}
		// 先清理旧资源再加载新资源，避免新旧BGA/视频解码器同时驻留导致OOM
		cache.disposeOld();
		Gdx.app.postRunnable(() -> mpgresource.disposeOld());

		cache.clear();
		resetCurrentlyPlayingBGA();

		int id = 0;

		Array<TimeLine> tls = new Array<TimeLine>();

		if(model != null) {
			for(TimeLine tl : model.getAllTimeLines()) {
				if(tl.getBGA() != -1 || tl.getLayer() != -1 || tl.getMissBGA() != -1 || tl.getEventlayer().length > 0) {
					tls.add(tl);
				}
			}

			// BMS格納ディレクトリ
			File dpath = new File(model.getPath()).getParentFile();

			movies = new MovieProcessor[model.getBgaList().length];
			for (String name : model.getBgaList()) {
				if (progress == 1) {
					break;
				}
				FileHandle f = null;
				try {
					FileHandle baseHandle = Gdx.files.absolute(new File(dpath, name).getPath());
					if (FileCache.exists(baseHandle)) {
						final int index = name.lastIndexOf('.');
						String fex = null;
						if (index != -1) {
							fex = name.substring(index + 1).toLowerCase();
						}
						if (fex != null) {
							if (Arrays.asList(mov_extension).contains(fex)){
								name = name.substring(0, index);
								for (String mov : mov_extension) {
									final FileHandle mpgfile = Gdx.files.absolute(new File(dpath, name + "." + mov).getPath());
									if (FileCache.exists(mpgfile)) {
										f = mpgfile;
										break;
									}
								}
							}else if (Arrays.asList(BGImageProcessor.pic_extension).contains(fex)){
								name = name.substring(0, index);
								for (String pic : BGImageProcessor.pic_extension) {
									final FileHandle picfile = Gdx.files.absolute(new File(dpath, name + "." + pic).getPath());
									if (FileCache.exists(picfile)) {
										f = picfile;
										break;
									}
								}
							}else{
								f = baseHandle;
							}
						}
					}
					if (f == null) {
						final int index = name.lastIndexOf('.');
						if (index != -1) {
							name = name.substring(0, index);
						}
						for (String mov : mov_extension) {
							final FileHandle mpgfile = Gdx.files.absolute(new File(dpath, name + "." + mov).getPath());
							if (FileCache.exists(mpgfile)) {
								f = mpgfile;
								break;
							}
						}
						if (f == null) {
							for (String mov : BGImageProcessor.pic_extension) {
								final FileHandle picfile = Gdx.files.absolute(new File(dpath, name + "." + mov).getPath());
								if (FileCache.exists(picfile)) {
									f = picfile;
									break;
								}
							}
						}
					}
				} catch (Exception e) {
					Logger.getGlobal().warning(e.getMessage());
				}

				if (f != null) {
					boolean isMovie = false;
					String fileName = f.name().toLowerCase();
					Gdx.app.log("BGAProcessor", "Checking BGA ID " + id + ": " + f.path());
					for (String mov : mov_extension) {
						if (fileName.endsWith(mov)) {
							try {
								MovieProcessor mm = mpgresource.get(f.path());
								movies[id] = mm;
								isMovie = true;
								Gdx.app.log("BGAProcessor", "Assigned MovieProcessor to ID " + id);
								break;
							} catch (Throwable e) {
								Logger.getGlobal().warning("BGAファイル読み込み失敗。" + e.getMessage());
							}
						}
					}
					if(!isMovie) {
						Gdx.app.log("BGAProcessor", "Assigned Image to ID " + id);
						cache.put(id, f.path());
					}
				}

				progress += 1f / model.getBgaList().length;
				id++;
			}
		}
		timelines = tls.toArray(TimeLine.class);

		Logger.getGlobal().info("BGAファイル読み込み完了。BGA数:" + id);
		progress = 1;
	}

	public void abort() {
		progress = 1;
	}

	public void disposeOld() {
		cache.disposeOld();
		Gdx.app.postRunnable(() -> mpgresource.disposeOld());
	}
	/**
	 * BGAの初期データをあらかじめキャッシュする
	 */
	public void prepare(BMSPlayer player) {
		pos = 0;
		resetCurrentlyPlayingBGA();
		bgaFramebufferDirty = true;
		if(cache != null) {
			cache.prepare(timelines);
		}

		// 预加载视频解码器，减少首次播放延迟
		for (MovieProcessor mpg : movies) {
			if (mpg != null) {
				mpg.preloadDecoder();
			}
		}
		// 预加载视频文件，在 STATE_PRELOAD 期间完成文件 IO
		for (MovieProcessor mpg : movies) {
			if (mpg != null) {
				mpg.preload();
			}
		}
		// 初始化为 -1 而非 0，确保 time=0 的 BGA 事件不会被 prepareBGA() 跳过
		// （prepareBGA 中 tl.getTime() > this.time 条件：0 > -1 为 true，0 > 0 为 false）
		time = -1;
	}

	private void resetCurrentlyPlayingBGA() {
		playingbgaid = -1;
		playinglayerid = -1;
		misslayertime = 0;
		playingmissbgaid = -1;
		rmissbga = false;
		missbga_start_time = 0;
		rbga = false;
		rlayer = false;
	}

	private Texture getBGAData(long time, int id, boolean cont) {
		if (progress != 1 || id == -1) {
			return null;
		}

		if(movies[id] != null) {
			// 仅获取纹理。play() 和 update() 已在 prepareBGA 逻辑阶段处理。
			return movies[id].getFrame();
		}
		Texture tex = cache != null ? cache.getTexture(id) : null;
		return tex;
	}

	public void prepareBGA(long time) {
		if (time < 0 || timelines == null) {
			this.time = -1;
			this.pos = 0;
			this.lastProcessedTime = -2;
			return;
		}

		// Support seeking backwards
		if (time < this.time) {
			this.pos = 0;
			resetCurrentlyPlayingBGA();
			bgaFramebufferDirty = true;
		}

		// Avoid redundant processing if called multiple times in the same frame with same timestamp
		if (time == lastProcessedTime) {
			return;
		}
		lastProcessedTime = time;

		for (int i = pos; i < timelines.length; i++) {
			final TimeLine tl = timelines[i];
			if (tl.getTime() > time) {
				break;
			}

			if (tl.getTime() > this.time) {
				final int bga = tl.getBGA();
				if (bga == -2) {
					bgaFramebufferDirty = true;
					playingbgaid = -1;
					rbga = false;
					bga_start_time = 0;
				} else if (bga >= 0) {
					if (playingbgaid != bga) {
						bgaFramebufferDirty = true;
						if (bga < movies.length && movies[bga] != null) {
							movies[bga].play(tl.getMilliTime(), false);
						}
					}
					playingbgaid = bga;
					rbga = false;
					bga_start_time = tl.getTime();
				}

				final int layer = tl.getLayer();
				if (layer == -2) {
					bgaFramebufferDirty = true;
					playinglayerid = -1;
					rlayer = false;
					layer_start_time = 0;
				} else if (layer >= 0) {
					if (playinglayerid != layer) {
						bgaFramebufferDirty = true;
						if (layer < movies.length && movies[layer] != null) {
							movies[layer].play(tl.getMilliTime(), false);
						}
					}
					playinglayerid = layer;
					rlayer = false;
					layer_start_time = tl.getTime();
				}

				final int missbga = tl.getMissBGA();
				if (missbga == -2) {
					bgaFramebufferDirty = true;
					playingmissbgaid = -1;
					rmissbga = false;
					missbga_start_time = 0;
				} else if (missbga >= 0) {
					if (playingmissbgaid != missbga) {
						bgaFramebufferDirty = true;
						if (missbga < movies.length && movies[missbga] != null) {
							movies[missbga].play(tl.getMilliTime(), false);
						}
					}
					playingmissbgaid = missbga;
					rmissbga = false;
					missbga_start_time = tl.getTime();
				}
				pos = i;
			}
		}

		this.time = time;

		// 在逻辑更新阶段驱动视频解码，传入绝对游戏时间
		if (playingbgaid >= 0 && movies[playingbgaid] != null) {
			movies[playingbgaid].update(time);
			bgaFramebufferDirty = true;
		}
		if (playinglayerid >= 0 && movies[playinglayerid] != null && playinglayerid != playingbgaid) {
			movies[playinglayerid].update(time);
			bgaFramebufferDirty = true;
		}
		if (playingmissbgaid >= 0 && movies[playingmissbgaid] != null && playingmissbgaid != playingbgaid && playingmissbgaid != playinglayerid) {
			movies[playingmissbgaid].update(time);
			bgaFramebufferDirty = true;
		}
	}


	private int bgaDrawCount = 0;
	public void drawBGA(SkinBGA dst, SkinObjectRenderer sprite, Rectangle r) {
		// Portrait BGA position/size is now controlled entirely by the Lua skin (geo.bga).
		// Removing the full-viewport override so that Lua x/y/w/h/stretch take effect.
		if (bgaDrawCount < 3) {
			bgaDrawCount++;
			Gdx.app.log("BGAProcessor", "drawBGA: isPortrait=" + isPortrait
				+ " r=[" + r.x + "," + r.y + "," + r.width + "," + r.height + "]"
				+ " stretch=" + dst.getStretch()
				+ " time=" + time + " playingbgaid=" + playingbgaid + " playinglayerid=" + playinglayerid);
		}
		// Capture BGA drawing parameters for accurate lane background alignment
		if (!hasLastBGAParams || !lastBGARect.equals(r) || lastStretch != dst.getStretch()) {
			lastBGARect.set(r);
			lastStretch = dst.getStretch();
			hasLastBGAParams = true;
		}

		sprite.setColor(dst.getColor());
		sprite.setBlend(dst.getBlend());
		if (time < 0 || timelines == null) {
			sprite.draw(blanktex, r.x, r.y, r.width, r.height);
			return;
		}

		// poor/miss layer active state - poor 触发后必须完全覆盖 #BGA 和 #LAYER
		final boolean poorlayerActive = misslayertime != 0 && time >= misslayertime
				&& time < misslayertime + getMisslayerduration && playingmissbgaid >= 0;

		// 最底层：绘制黑色背景作为打底，确保透明区域有黑色底色
		// 保存原始混合模式，绘制黑色背景时使用不透明模式
		int originalBlend = sprite.getBlend();
		sprite.setBlend(0); // 不透明模式绘制纯黑背景
		sprite.draw(blanktex, r.x, r.y, r.width, r.height);
		sprite.setBlend(originalBlend); // 恢复原始混合模式

		// draw BGA (Background) - poor 触发时跳过 BGA 绘制，让上面的黑色画布
		// 充当 POORLAYER 透明区域的底色，避免 BGA 透过 POORLAYER 露出来
		final Texture playingbgatex = getBGAData(time - bga_start_time, playingbgaid, rbga);
		rbga = true;
		if (playingbgatex != null && !poorlayerActive) {
			if (movies[playingbgaid] != null) {
				sprite.setType(movies[playingbgaid].getRenderType());
				drawBGAFixRatio(dst, sprite, r, playingbgatex);
			} else {
				sprite.setType(SkinObjectRenderer.TYPE_LINEAR);
				drawBGAFixRatio(dst, sprite, r, playingbgatex);
			}
		}

		if (poorlayerActive) {
			// draw poor/miss BGA layer - 必须在 poor 触发后完全覆盖 #BGA 和 #LAYER，
			// 不能像 #LAYER 那样支持 alpha 透明
			final Texture misstex = getBGAData(time - missbga_start_time, playingmissbgaid, rmissbga);
			rmissbga = true;
			if (misstex != null) {
				if (movies[playingmissbgaid] != null) {
					sprite.setType(movies[playingmissbgaid].getRenderType());
				} else {
					sprite.setType(SkinObjectRenderer.TYPE_LINEAR);
				}
				drawBGAFixRatio(dst, sprite, r, misstex);
			}
		} else {
			// draw layer (Overlay) - 在背景之上绘制图层
			final Texture playinglayertex = getBGAData(time - layer_start_time, playinglayerid, rlayer);
			rlayer = true;
			if (playinglayertex != null) {
				// 确保在绘制 LAYER 前启用 Alpha Blending
				int layerBlend = sprite.getBlend();
				if (layerBlend == 0) {
					sprite.setBlend(0);
				}

				if (movies[playinglayerid] != null) {
					sprite.setType(movies[playinglayerid].getRenderType());
					drawBGAFixRatio(dst, sprite, r, playinglayertex);
				} else {
					sprite.setType(SkinObjectRenderer.TYPE_LAYER);
					drawBGAFixRatio(dst, sprite, r, playinglayertex);
				}

				if (layerBlend == 0) {
					sprite.setBlend(0);
				}
			}
		}
	}

	/**
	 * Modify the aspect ratio and draw BGA
	 */
	private int fixRatioLogCount = 0;
	private void drawBGAFixRatio(SkinBGA dst, SkinObjectRenderer sprite, Rectangle r, Texture bga){
		image.setTexture(bga);
		image.setRegion(0, 0, bga.getWidth(), bga.getHeight());
		if (fixRatioLogCount < 3) {
			fixRatioLogCount++;
			Gdx.app.log("BGAProcessor", "drawBGAFixRatio: tex=" + bga.getWidth() + "x" + bga.getHeight()
				+ " isPortrait=" + isPortrait + " r=[" + r.x + "," + r.y + "," + r.width + "," + r.height + "]");
		}
		if (isPortrait) {
			// In portrait mode, the BGA is rotated 270° around the center of 'r'.
			// After rotation:
			//   - The unrotated width (W) maps to vertical coverage: [centerY - W/2, centerY + W/2]
			//   - The unrotated height (H) maps to horizontal coverage: [centerX - H/2, centerX + H/2]
			// To fully cover the viewport (r.width x r.height), we need W >= r.height and H >= r.width.
			// Using a square target max(w,h) x max(w,h) for FIT_OUTER guarantees this.
			float maxDim = Math.max(r.width, r.height);
			tmpRect.set(0, 0, maxDim, maxDim);
			dst.getStretch().stretchRect(tmpRect, image, image);

			float centerX = r.x + r.width / 2f;
			float centerY = r.y + r.height / 2f;
			sprite.draw(image, centerX - tmpRect.width / 2f, centerY - tmpRect.height / 2f, tmpRect.width, tmpRect.height, 0.5f, 0.5f, 270f);
		} else {
			tmpRect.set(r);
			dst.getStretch().stretchRect(tmpRect, image, image);
			sprite.draw(image, tmpRect.x, tmpRect.y, tmpRect.width, tmpRect.height);
		}
	}

	/**
	 * Renders BGA to the shared framebuffer texture for use as lane background.
	 * This enables transparent lane areas to show the BGA underneath.
	 * @param width the width of the play area
	 * @param height the height of the play area
	 * @param stretch the stretch mode for BGA
	 */
	private static final IntBuffer viewportBuffer = BufferUtils.newIntBuffer(16);

	public void renderBGAToFramebuffer(int width, int height, StretchType stretch) {
		if (width <= 0 || height <= 0) return;

		// Create or resize framebuffer if needed
		if (bgaFramebuffer == null || bgaFramebuffer.getWidth() != width || bgaFramebuffer.getHeight() != height) {
			if (bgaFramebuffer != null) {
				bgaFramebuffer.dispose();
			}
			bgaFramebuffer = new FrameBuffer(Pixmap.Format.RGBA8888, width, height, false);
			bgaFramebufferRegion = new TextureRegion(bgaFramebuffer.getColorBufferTexture());
			bgaFramebufferRegion.flip(false, true); // FBO textures are Y-up, flip for standard Y-down regions
		}

		// Save current GL viewport to restore later (fixes "out of bounds" issue)
		Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, viewportBuffer);
		int prevX = viewportBuffer.get(0);
		int prevY = viewportBuffer.get(1);
		int prevW = viewportBuffer.get(2);
		int prevH = viewportBuffer.get(3);

		bgaFramebuffer.begin();
		// Clear with transparent
		Gdx.gl.glClearColor(0, 0, 0, 0);
		Gdx.gl.glClear(Gdx.gl.GL_COLOR_BUFFER_BIT);

		if (time < 0 || timelines == null) {
			bgaFramebuffer.end();
			Gdx.gl.glViewport(prevX, prevY, prevW, prevH);
			return;
		}

		// Use the tracked BGA rectangle if available for perfect alignment
		Rectangle targetRect;
		StretchType targetStretch;
		if (hasLastBGAParams) {
			targetRect = lastBGARect;
			targetStretch = lastStretch;
		} else {
			targetRect = new Rectangle(0, 0, width, height);
			targetStretch = stretch;
		}

		// Use the shared SpriteBatch to draw into the framebuffer
		batch.setProjectionMatrix(new Matrix4().setToOrtho2D(0, 0, width, height));
		batch.begin();
		// Draw black background (opaque)
		batch.draw(blanktex, 0, 0, width, height);

		// Adjust target rectangle to Y-up for drawing into LibGDX FBO
		// since skin coordinates (targetRect) are Y-down
		tmpRect.set(targetRect.x, (float)height - targetRect.y - targetRect.height, targetRect.width, targetRect.height);

		// poor/miss layer active - 同样跳过 BGA，让黑色画布作为 POORLAYER 透明区底色
		final boolean poorlayerActive = misslayertime != 0 && time >= misslayertime
				&& time < misslayertime + getMisslayerduration && playingmissbgaid >= 0;

		// Draw BGA background (skipped when POORLAYER is active)
		final Texture playingbgatex = getBGAData(time - bga_start_time, playingbgaid, rbga);
		if (playingbgatex != null && !poorlayerActive) {
			rbga = true;
			drawBGAFixRatioToRect(batch, tmpRect, playingbgatex, targetStretch);
		}

		if (poorlayerActive) {
			final Texture misstex = getBGAData(time - missbga_start_time, playingmissbgaid, rmissbga);
			rmissbga = true;
			if (misstex != null) {
				drawBGAFixRatioToRect(batch, tmpRect, misstex, targetStretch);
			}
		} else {
			// Draw layer
			final Texture playinglayertex = getBGAData(time - layer_start_time, playinglayerid, rlayer);
			if (playinglayertex != null) {
				rlayer = true;
				// 如果是图片图层，使用 layer shader 处理黑色透明
				if (movies[playinglayerid] == null) {
					batch.setShader(bms.player.beatoraja.ShaderManager.getShader("layer"));
					drawBGAFixRatioToRect(batch, tmpRect, playinglayertex, targetStretch);
					batch.setShader(null);
				} else {
					drawBGAFixRatioToRect(batch, tmpRect, playinglayertex, targetStretch);
				}
			}
		}
		batch.end();

		bgaFramebuffer.end();

		// Restore previous GL viewport
		Gdx.gl.glViewport(prevX, prevY, prevW, prevH);

		bgaFramebufferDirty = false;
	}

	private void drawBGAFixRatioToRect(SpriteBatch batch, Rectangle r, Texture bga, StretchType stretch) {
		image.setTexture(bga);
		image.setRegion(0, 0, bga.getWidth(), bga.getHeight());
		if (isPortrait) {
			// Same fix as drawBGAFixRatio: use square max(w,h) target for FIT_OUTER
			float maxDim = Math.max(r.width, r.height);
			tmpRect.set(0, 0, maxDim, maxDim);
			stretch.stretchRect(tmpRect, image, image);
			float centerX = r.x + r.width / 2f;
			float centerY = r.y + r.height / 2f;
			// Pivot must coincide with the image's center for the rotation to be visually centered.
			// batch.draw originX/Y are absolute pixel offsets of the pivot inside the unrotated image,
			// so originX = imageWidth/2 and originY = imageHeight/2; position the image's bottom-left
			// at (centerX - W/2, centerY - H/2). Using height/width swapped here leaves the pivot at
			// (centerX, centerY) but the image center offset by ((W-H)/2, (H-W)/2), which is what
			// was misaligning the FBO-rendered BGA used by LaneRenderer's lane background.
			batch.draw(image, centerX - tmpRect.width / 2f, centerY - tmpRect.height / 2f, tmpRect.width / 2f, tmpRect.height / 2f, tmpRect.width, tmpRect.height, 1f, 1f, 270f);
		} else {
			tmpRect.set(r);
			stretch.stretchRect(tmpRect, image, image);
			batch.draw(image, tmpRect.x, tmpRect.y, tmpRect.width, tmpRect.height);
		}
	}

	/**
	 * Returns the shared BGA texture that can be used as a background for lane rendering.
	 * Must be called after renderBGAToFramebuffer() has been called.
	 * @return the BGA texture, or null if not available
	 */
	public Texture getSharedBGATexture() {
		return bgaFramebuffer != null ? bgaFramebuffer.getColorBufferTexture() : null;
	}

	/**
	 * Returns the framebuffer region for reading BGA texture.
	 * @return the BGA framebuffer region, or null if not available
	 */
	public TextureRegion getSharedBGATextureRegion() {
		return bgaFramebufferRegion;
	}

	/**
	 * Gets the current BGA texture for use as a lane background.
	 * Returns the BGA texture at the current time, suitable for drawing as a lane background.
	 * This allows transparent lane areas to show the BGA underneath.
	 * @return the current BGA texture, or null if no BGA is available
	 */
	public Texture getCurrentBGAFrame(int width, int height) {
		if (time < 0 || timelines == null) {
			return null;
		}
		// Render BGA to framebuffer to get BGA with combined layers
		if (width > 0 && height > 0) {
			StretchType stretch;
			if (config != null) {
				switch (config.getBgaExpand()) {
					case Config.BGAEXPAND_FULL:
						stretch = StretchType.STRETCH;
						break;
					case Config.BGAEXPAND_KEEP_ASPECT_RATIO:
						stretch = StretchType.KEEP_ASPECT_RATIO_FIT_INNER;
						break;
					default:
						stretch = StretchType.KEEP_ASPECT_RATIO_NO_EXPANDING;
						break;
				}
			} else {
				stretch = StretchType.STRETCH;
			}

			// Only render if dirty or size changed
			if (bgaFramebufferDirty || bgaFramebuffer == null || bgaFramebuffer.getWidth() != width || bgaFramebuffer.getHeight() != height) {
				renderBGAToFramebuffer(width, height, stretch);
			}
			return getSharedBGATexture();
		}
		// Fallback to raw BGA data if width/height are invalid
		if (misslayertime != 0 && time >= misslayertime && time < misslayertime + getMisslayerduration && playingmissbgaid >= 0) {
			final Texture misstex = getBGAData(time - missbga_start_time, playingmissbgaid, rmissbga);
			rmissbga = true;
			if (misstex != null) {
				return misstex;
			}
		}
		return getBGAData(time - bga_start_time, playingbgaid, rbga);
	}

	/**
	 * Gets the current layer texture.
	 * @return the current layer texture, or null if no layer is available
	 */
	public Texture getCurrentLayerFrame() {
		return null; // Layer is combined in getCurrentBGAFrame
	}

	/**
	 * Check if BGA framebuffer needs update.
	 * @return true if the framebuffer needs to be refreshed
	 */
	public boolean isBGAFramebufferDirty() {
		return bgaFramebufferDirty;
	}

	/**
	 * Mark the BGA framebuffer as dirty (needs refresh).
	 */
	public void setBGAFramebufferDirty() {
		this.bgaFramebufferDirty = true;
	}

	/**
	 * ミスレイヤー開始時間を設定する
	 *
	 * @param time
	 *            ミスレイヤー開始時間(ms)
	 */
	public void setMisslayerTme(long time) {
		misslayertime = time;
		getMisslayerduration = player.getMisslayerDuration();
	}

	public void stop() {
		for (MovieProcessor mpg : movies) {
			if (mpg != null) {
				mpg.stop();
			}
		}
	}

	/**
	 * 切后台/锁屏时暂停所有视频播放器。
	 * Android MediaPlayer 对生命周期极敏感，必须在 pause()/hide() 中调用。
	 */
	public void pauseAll() {
		for (MovieProcessor mp : movies) {
			if (mp != null) {
				mp.pause();
			}
		}
	}

	/**
	 * 切回前台时恢复所有视频播放器。
	 * 在 resume() 中调用。
	 */
	public void resumeAll() {
		for (MovieProcessor mp : movies) {
			if (mp != null) {
				mp.resume();
			}
		}
	}

	/**
	 * リソースを開放する
	 */
	public void dispose() {
		if (cache != null) {
			cache.dispose();
		}
		mpgresource.dispose();
		if (blanktex != null) {
			blanktex.dispose();
		}
		if (bgaFramebuffer != null) {
			bgaFramebuffer.dispose();
		}
		if (batch != null) {
			batch.dispose();
		}
	}

	public float getProgress() {
		return progress;
	}
}
