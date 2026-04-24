package bms.player.beatoraja.play.bga;

import java.nio.file.*;
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

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.Array;

/**
 * BGAのリソース管理、描画用クラス
 *
 * @author exch
 */
public class BGAProcessor {

	// TODO イベントレイヤー対応(現状はミスレイヤーのみ)

	private PlayerConfig player;
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
	 * ミスレイヤー表示開始時間
	 */
	private long misslayertime;

	private long getMisslayerduration;
	/**
	 * 現在のミスレイヤーシーケンス
	 */
	private Layer misslayer = null;

	private long bga_start_time = 0;
	private long layer_start_time = 0;

	private long time;

	private BGImageProcessor cache;

	private Texture blanktex;

	private volatile TimeLine[] timelines = new TimeLine[0];
	private int pos;
	private TextureRegion image;
	private Rectangle tmpRect = new Rectangle();

	private boolean rbga;
	private boolean rlayer;

	/**
	 * 是否在 Android 平台上运行（使用 gdx-video 硬件解码）
	 */
	private final boolean useGdxVideo;

	public BGAProcessor(Config config, PlayerConfig player) {
		this.player = player;
		this.useGdxVideo = Gdx.app.getType() == Application.ApplicationType.Android;

		Pixmap blank = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
		blank.setColor(Color.BLACK);
		blank.fill();
		blanktex = new Texture(blank);
		blank.dispose();

		mpgresource = new ResourcePool<String, MovieProcessor>(Math.max(config.getSongResourceGen(), 1)) {
			@Override
			protected MovieProcessor load(String key) {
				if (useGdxVideo) {
					// Android 平台分两条路径：
					// 1) .mpg/.avi 等旧格式 → JCodec 纯 Java 软解码（避免 FFmpeg JNI 冲突）
					// 2) .mp4/.webm 等现代格式 → gdx-video 硬件解码（MediaCodec）
					if (JCodecVideoProcessor.isJCodecFormat(key)) {
						Logger.getGlobal().info("JCodec fallback for legacy format: " + key);
						JCodecVideoProcessor jcp = new JCodecVideoProcessor();
						jcp.create(key);
						return jcp;
					}
					GdxVideoProcessor gvp = new GdxVideoProcessor();
					gvp.create(key);
					return gvp;
				} else {
					// Desktop: FFmpeg 软解码已移除（JavaCV 依赖已清理）
					throw new UnsupportedOperationException("FFmpegProcessor has been removed. Desktop video playback is not supported.");
				}
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

		cache.clear();
		resetCurrentlyPlayingBGA();

		int id = 0;

		Array<TimeLine> tls = new Array<TimeLine>();

		if(model != null) {
			for(TimeLine tl : model.getAllTimeLines()) {
				if(tl.getBGA() != -1 || tl.getLayer() != -1 || tl.getEventlayer().length > 0) {
					tls.add(tl);
				}
			}

			// BMS格納ディレクトリ
			Path dpath = Paths.get(model.getPath()).getParent();

			movies = new MovieProcessor[model.getBgaList().length];
			for (String name : model.getBgaList()) {
				if (progress == 1) {
					break;
				}
				FileHandle f = null;
				try {
					FileHandle baseHandle = Gdx.files.absolute(dpath.resolve(name).toString());
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
									final FileHandle mpgfile = Gdx.files.absolute(dpath.resolve(name + "." + mov).toString());
									if (FileCache.exists(mpgfile)) {
										f = mpgfile;
										break;
									}
								}
							}else if (Arrays.asList(BGImageProcessor.pic_extension).contains(fex)){
								name = name.substring(0, index);
								for (String pic : BGImageProcessor.pic_extension) {
									final FileHandle picfile = Gdx.files.absolute(dpath.resolve(name + "." + pic).toString());
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
							final FileHandle mpgfile = Gdx.files.absolute(dpath.resolve(name + "." + mov).toString());
							if (FileCache.exists(mpgfile)) {
								f = mpgfile;
								break;
							}
						}
						if (f == null) {
							for (String mov : BGImageProcessor.pic_extension) {
								final FileHandle picfile = Gdx.files.absolute(dpath.resolve(name + "." + mov).toString());
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
					for (String mov : mov_extension) {
						if (fileName.endsWith(mov)) {
							try {
								MovieProcessor mm = mpgresource.get(f.path());
								movies[id] = mm;
								isMovie = true;
								break;
							} catch (Throwable e) {
								Logger.getGlobal().warning("BGAファイル読み込み失敗。" + e.getMessage());
								// 减少过多的 codec 相关日志输出
								// e.printStackTrace();
							}
						}
					}
					if(!isMovie) {
						cache.put(id, Paths.get(f.path()));
					}
				}

				progress += 1f / model.getBgaList().length;
				id++;
			}
		}
		timelines = tls.toArray(TimeLine.class);

		disposeOld();

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
		if(cache != null) {
			cache.prepare(timelines);
		}

		// 初始化为 -1 而非 0，确保 time=0 的 BGA 事件不会被 prepareBGA() 跳过
		// （prepareBGA 中 tl.getTime() > this.time 条件：0 > -1 为 true，0 > 0 为 false）
		time = -1;
	}

	private void resetCurrentlyPlayingBGA() {
		playingbgaid = -1;
		playinglayerid = -1;
		misslayertime = 0;
		misslayer = null;
		rbga = false;
		rlayer = false;
	}

	private Texture getBGAData(long time, int id, boolean cont) {
		if (progress != 1 || id == -1) {
			return null;
		}

		if(movies[id] != null) {
			if (!cont) {
				movies[id].play(time, false);
			}
			// 仅获取纹理，不再触发 update() 导致的 GL 状态混乱
			return movies[id].getFrame();
		}
		Texture tex = cache != null ? cache.getTexture(id) : null;
		return tex;
	}

	public void prepareBGA(long time) {
		if (time < 0 || timelines == null) {
			this.time = -1;
			return;
		}
		for (int i = pos; i < timelines.length; i++) {
			final TimeLine tl = timelines[i];
			if (tl.getTime() > time) {
				break;
			}

			if (tl.getTime() > this.time) {
				final int bga = tl.getBGA();
				if (bga == -2) {
					playingbgaid = -1;
					rbga = false;
					bga_start_time = 0;
				} else if (bga >= 0) {
					playingbgaid = bga;
					rbga = false;
					bga_start_time = tl.getTime();
				}

				final int layer = tl.getLayer();
				if (layer == -2) {
					playinglayerid = -1;
					rlayer = false;
					layer_start_time = 0;
				} else if (layer >= 0) {
					playinglayerid = layer;
					rlayer = false;
					layer_start_time = tl.getTime();
				}

				final Layer[] eventlayer = tl.getEventlayer();

				for(Layer poor : eventlayer) {
					if (poor.event.type == Layer.EventType.MISS) {
						misslayer = poor;
					}
				}
			} else {
				pos++;
			}
		}

		this.time = time;

		// 在逻辑更新阶段提前驱动视频解码，确保渲染时 GL 状态稳定
		if (playingbgaid >= 0 && movies[playingbgaid] != null) {
			movies[playingbgaid].update(time - bga_start_time);
		}
		if (playinglayerid >= 0 && movies[playinglayerid] != null && playinglayerid != playingbgaid) {
			movies[playinglayerid].update(time - layer_start_time);
		}
	}


	public void drawBGA(SkinBGA dst, SkinObjectRenderer sprite, Rectangle r) {
		sprite.setColor(dst.getColor());
		sprite.setBlend(dst.getBlend());
		if (time < 0 || timelines == null) {
			sprite.draw(blanktex, r.x, r.y, r.width, r.height);
			return;
		}

		// 最底层：绘制黑色背景作为打底，确保透明区域有黑色底色
		// 保存原始混合模式，绘制黑色背景时使用不透明模式
		int originalBlend = sprite.getBlend();
		sprite.setBlend(0); // 不透明模式绘制纯黑背景
		sprite.draw(blanktex, r.x, r.y, r.width, r.height);
		sprite.setBlend(originalBlend); // 恢复原始混合模式

		if (misslayer != null && misslayertime != 0 && time >= misslayertime && time < misslayertime + getMisslayerduration) {
			// draw miss layer
			final Layer.Sequence[] seq = misslayer.sequence[0];
			final int index = seq[(int) ((seq.length - 1) * (time - misslayertime) / getMisslayerduration)].id;
			if(index != Integer.MIN_VALUE) {
				Texture miss = getBGAData(time, index, true);
				if (miss != null) {
					sprite.setType(SkinObjectRenderer.TYPE_LINEAR);
					drawBGAFixRatio(dst, sprite, r, miss);
				}
			}
		} else {
		// draw BGA (Background) - 中间层：绘制BGA背景层
		final Texture playingbgatex = getBGAData(time - bga_start_time, playingbgaid, rbga);
		rbga = true;
		if (playingbgatex != null) {
			if (movies[playingbgaid] != null) {
				sprite.setType(movies[playingbgaid].getRenderType());
				drawBGAFixRatio(dst, sprite, r, playingbgatex);
			} else {
				sprite.setType(SkinObjectRenderer.TYPE_LINEAR);
				drawBGAFixRatio(dst, sprite, r, playingbgatex);
			}
		}

		// draw layer (Overlay) - 最上层：在背景之上绘制图层，启用 Alpha 混合
		final Texture playinglayertex = getBGAData(time - layer_start_time, playinglayerid, rlayer);
		rlayer = true;
			if (playinglayertex != null) {
				// 确保在绘制 LAYER 前启用 Alpha Blending
				// SkinObjectRenderer 的 preDraw() 会根据 blend 值自动设置混合函数
				// 这里我们确保使用标准的 Alpha 混合
				int layerBlend = sprite.getBlend();
				if (layerBlend == 0) {
					// 如果未设置混合模式，强制使用 Alpha 混合
					sprite.setBlend(2); // 使用 case 2: GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA
				}

				if (movies[playinglayerid] != null) {
					sprite.setType(movies[playinglayerid].getRenderType());
					drawBGAFixRatio(dst, sprite, r, playinglayertex);
				} else {
					sprite.setType(SkinObjectRenderer.TYPE_LAYER);
					drawBGAFixRatio(dst, sprite, r, playinglayertex);
				}

				// 恢复原始混合设置
				if (layerBlend == 0) {
					sprite.setBlend(0);
				}
			}
		}
	}

	/**
	 * Modify the aspect ratio and draw BGA
	 */
	private void drawBGAFixRatio(SkinBGA dst, SkinObjectRenderer sprite, Rectangle r, Texture bga){
		tmpRect.set(r);
		image.setTexture(bga);
		image.setRegion(0, 0, bga.getWidth(), bga.getHeight());
		dst.getStretch().stretchRect(tmpRect, image, image);
		sprite.draw(image, tmpRect.x, tmpRect.y, tmpRect.width, tmpRect.height);
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
	}

	public float getProgress() {
		return progress;
	}
}
