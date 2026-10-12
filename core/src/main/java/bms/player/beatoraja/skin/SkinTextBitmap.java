package bms.player.beatoraja.skin;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

import bms.player.beatoraja.PixmapResourcePool;
import bms.player.beatoraja.skin.property.StringProperty;
import bms.player.beatoraja.skin.property.StringPropertyFactory;
import bms.player.beatoraja.skin.BitmapFontCache.CacheableBitmapFont;
import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import bms.player.beatoraja.skin.Skin.SkinObjectRenderer;

/**
 * .fnt ファイルをソースとして持つスキン用テキスト
 */
public final class SkinTextBitmap extends SkinText {

	private final SkinTextBitmapSource source;
	private BitmapFont font;
	private final GlyphLayout layout;
	private final float size;

	public SkinTextBitmap(SkinTextBitmapSource source, float size) {
		this(source, size, StringPropertyFactory.getStringProperty(-1));
	}

	public SkinTextBitmap(SkinTextBitmapSource source, float size, StringProperty property) {
		super(property);
		this.source = source;
		this.size = size;
		this.layout =new GlyphLayout();
		this.font = source.getFont();
	}

	@Override
	public void load() {
	}

	@Override
	public void prepareFont(String text) {
	}

	@Override
	protected void prepareText(String text) {
	}

	public void draw(SkinObjectRenderer sprite, float offsetX, float offsetY) {
		this.font = source.getFont();
		if (font == null)
			return;

		float scale = this.size / source.getOriginalSize();
		font.getData().setScale(scale);
		final float x = (getAlign() == 2 ? region.x - region.width : (getAlign() == 1 ? region.x - region.width / 2 : region.x));
		final boolean hasAngle = angle != 0;
		final float pivotX = region.x + centerx * region.width + offsetX;
		final float pivotY = region.y + centery * region.height + offsetY;

		if (source.getType() == SkinTextBitmapSource.TYPE_DISTANCE_FIELD ||
				source.getType() == SkinTextBitmapSource.TYPE_COLORED_DISTANCE_FIELD) {
			sprite.setType(SkinObjectRenderer.TYPE_DISTANCE_FIELD);
			if (hasAngle) {
				setLayout(color, region);
				sprite.draw(font, layout, x + offsetX, region.y + offsetY + region.getHeight(), shader -> {
					shader.setUniformf("u_outlineDistance", Math.max(0.1f, 0.5f - getOutlineWidth()/2f));
					shader.setUniformf("u_outlineColor", getOutlineColor());
					shader.setUniformf("u_shadowColor", getShadowColor());
					shader.setUniformf("u_shadowSmoothing", getShadowSmoothness() / 2f);
					shader.setUniformf("u_shadowOffset",
							new Vector2(getShadowOffset().x / source.getPageWidth(), getShadowOffset().y / source.getPageHeight()));
				}, pivotX, pivotY, angle);
			} else {
				setLayout(color, region);
				sprite.draw(font, layout, x + offsetX, region.y + offsetY + region.getHeight(), shader -> {
					shader.setUniformf("u_outlineDistance", Math.max(0.1f, 0.5f - getOutlineWidth()/2f));
					shader.setUniformf("u_outlineColor", getOutlineColor());
					shader.setUniformf("u_shadowColor", getShadowColor());
					shader.setUniformf("u_shadowSmoothing", getShadowSmoothness() / 2f);
					shader.setUniformf("u_shadowOffset",
							new Vector2(getShadowOffset().x / source.getPageWidth(), getShadowOffset().y / source.getPageHeight()));
				});
			}
		} else {
			// 🔴 上游在这里硬编码 TYPE_BILINEAR，而 bilinear shader 是纯透传 ——
			// 等于「什么都不做」，纹理会停在 libGDX 默认的 Nearest。
			// 2026-09-25：改成按 getFilter() 走 LINEAR，与 SkinTextFont 一致
			// （SkinTextureFilterPolicy 会给文字 setFilter(1)）。
			sprite.setType(getFilter() != 0 ? SkinObjectRenderer.TYPE_LINEAR : SkinObjectRenderer.TYPE_BILINEAR);
			if (hasAngle) {
				if (!getShadowOffset().isZero()) {
					setLayout(new Color(color.r / 2, color.g / 2, color.b / 2, color.a), region);
					sprite.draw(font, layout, x + getShadowOffset().x + offsetX,
						region.y - getShadowOffset().y + offsetY + region.getHeight(),
						pivotX, pivotY, angle);
				}
				setLayout(color, region);
				sprite.draw(font, layout, x + offsetX, region.y + offsetY + region.getHeight(),
					pivotX, pivotY, angle);
			} else {
				if (!getShadowOffset().isZero()) {
					setLayout(new Color(color.r / 2, color.g / 2, color.b / 2, color.a), region);
					sprite.draw(font, layout, x + getShadowOffset().x + offsetX, region.y - getShadowOffset().y + offsetY + region.getHeight());
				}
				setLayout(color, region);
				sprite.draw(font, layout, x + offsetX, region.y + offsetY + region.getHeight());
			}
		}
		font.getData().setScale(1);
		// 防线（同 SkinTextFont.draw 末尾）：把 renderer type 还原 NORMAL，避免升成
		// LINEAR 的 type 泄漏给下一个「继承 type」的对象（如选曲的 SkinDistributionGraph）。
		sprite.setType(SkinObjectRenderer.TYPE_NORMAL);
	}

	private void setLayout(Color c, Rectangle r) {
		if (isWrapping()) {
			layout.setText(font, getText(), c, r.getWidth(), ALIGN[getAlign()], true);
		} else {
			switch (getOverflow()) {
				case OVERFLOW_OVERFLOW -> {
					layout.setText(font, getText(), c, r.getWidth(), ALIGN[getAlign()], false);
				}
				case OVERFLOW_SHRINK -> {
					layout.setText(font, getText(), c, r.getWidth(), ALIGN[getAlign()], false);
					float actualWidth = layout.width;
					if (actualWidth > r.getWidth()) {
						font.getData().setScale(font.getData().scaleX * r.getWidth() / actualWidth, font.getData().scaleY);
						layout.setText(font, getText(), c, r.getWidth(), ALIGN[getAlign()], false);
					}
				}
				case OVERFLOW_TRUNCATE -> {
					layout.setText(font, getText(), 0, getText().length(), c, r.getWidth(), ALIGN[getAlign()], false, "");
				}
			}
		}
	}

	public void dispose() {
		source.dispose();
	}

	public static final class SkinTextBitmapSource implements Disposable {

		public static final int TYPE_STANDARD = 0;
		public static final int TYPE_DISTANCE_FIELD = 1;
		public static final int TYPE_COLORED_DISTANCE_FIELD = 2;

		private boolean usecim;
		private boolean useMipMaps;
		private File fontPath;
		private BitmapFont.BitmapFontData fontData;
		private Array<TextureRegion> regions;
		private BitmapFont font;
		private float originalSize;
		private int type;
		private float pageWidth;
		private float pageHeight;

		/**
		 * .fnt 解析 + ページ画像デコード専用の<b>直列</b>プール。
		 *
		 * <p>🔴 解析（{@code new BitmapFont.BitmapFontData}）もデコード（{@code new Pixmap}）も
		 * 純 CPU で GL を必要としないので背景スレッドへ逃がせる。
		 * ただし<b>直列にしているのはメモリのため</b> —— 1 フォントのデコードで
		 * 2048×2048 の Pixmap（16MB / 枚）をページ数ぶん（実測 5 枚 = 80MB）同時に抱える。
		 * 3 本並列にすると 240MB が一気に載って OOM するので、直列にして
		 * 同時に 1 フォントぶん（80MB 前後）で済むようにしている。
		 */
		private static final java.util.concurrent.ExecutorService PARSE_POOL =
				java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
					Thread t = new Thread(r, "skin-font-parse");
					t.setDaemon(true);
					return t;
				});

		/** 解析失敗時のリトライ間隔（ms）。 */
		private static final long PARSE_RETRY_INTERVAL_MS = 10_000L;

		/** 背景スレッドの解析結果。GL スレッドから読むので volatile。 */
		private volatile ParseResult asyncParsed;
		private volatile boolean parseStarted;
		private volatile boolean parseFailed;
		private volatile long parseFinishedAt;
		/**
		 * この source が破棄されたか。{@code dispose()} で立て、
		 * 背景デコードが「破棄されたのに Pixmap を作り続ける」のを止めさせる。
		 */
		private volatile boolean sourceDisposed;

		public SkinTextBitmapSource(File fontPath, boolean usecim) {
			this(fontPath, usecim, true);
		}

		public SkinTextBitmapSource(File fontPath, boolean usecim, boolean useMipMaps) {
			this.usecim = usecim;
			this.useMipMaps = useMipMaps;
			this.fontPath = fontPath;
		}

		/**
		 * Resolve a font Path to a proper FileHandle, handling Android's asset path system.
		 * On Android, relative paths need to be resolved via internal (assets) or absolute (external storage).
		 * On Desktop, the original File-based approach works fine.
		 */
		private static FileHandle resolveFileHandle(File fontPath) {
			String pathStr = fontPath.getPath().replace("\\", "/");

			if (Gdx.app.getType() == Application.ApplicationType.Android) {
				// 1. If path is already absolute, try absolute FileHandle directly
				if (fontPath.isAbsolute()) {
					FileHandle fh = Gdx.files.absolute(pathStr);
					if (fh.exists()) {
						Gdx.app.log("FontDebug", "Resolved .fnt via absolute: " + fh.path());
						return fh;
					}
				}

				// 2. Try with beatoraja.root prefix (skin files copied to external storage)
				String root = System.getProperty("beatoraja.root");
				if (root != null && !root.isEmpty()) {
					String absPath = root + "/" + pathStr;
					FileHandle fh = Gdx.files.absolute(absPath);
					if (fh.exists()) {
						Gdx.app.log("FontDebug", "Resolved .fnt via beatoraja.root: " + fh.path());
						return fh;
					}
				}

				// 3. Try internal (APK assets directory)
				FileHandle fh = Gdx.files.internal(pathStr);
				if (fh.exists()) {
					Gdx.app.log("FontDebug", "Resolved .fnt via internal: " + fh.path());
					return fh;
				}

				// 4. Try local (app private files directory)
				fh = Gdx.files.local(pathStr);
				if (fh.exists()) {
					Gdx.app.log("FontDebug", "Resolved .fnt via local: " + fh.path());
					return fh;
				}

				// Fallback: return internal handle (will fail with a clear error)
				Gdx.app.error("FontDebug", "Could not resolve .fnt file on Android: " + pathStr);
				return Gdx.files.internal(pathStr);
			} else {
				// Desktop: original behavior
				return new FileHandle(fontPath);
			}
		}

		/**
		 * .fnt テキストの解析結果。
		 *
		 * <p>🔴 {@code BitmapFont.BitmapFontData} の構築は<b>ファイルを読んで字形表を組むだけ</b>で
		 * OpenGL を一切使わない（ページ Texture は {@link #assemble} で GL スレッドが結び付ける）。
		 * だからこの段階だけを背景スレッドへ出せる —— そのために解析と装配を分けている。
		 */
		public static final class ParseResult {
			public final FileHandle fontFile;
			public final BitmapFont.BitmapFontData fontData;
			public final float originalSize;
			public final float pageWidth;
			public final float pageHeight;

			/** 各ページ画像の実パス（{@link #parseFont} が解決する）。 */
			public final String[] pagePaths;
			/**
			 * 各ページのデコード済み Pixmap。背景スレッドが順に充填する。
			 * null は「未デコード」または「デコード失敗」。
			 */
			public final Pixmap[] pagePixmaps;
			/**
			 * {@link #pagePixmaps} を GL スレッドの {@code assemble()} が消費済みか。
			 * 消費後は再アップロードできないので、もう一度必要になったら
			 * （{@code resume()} 後の GL コンテキスト再構築など）背景解析をやり直す。
			 */
			public volatile boolean pixmapsConsumed;

			ParseResult(FileHandle fontFile, BitmapFont.BitmapFontData fontData,
					float originalSize, float pageWidth, float pageHeight, String[] pagePaths) {
				this.fontFile = fontFile;
				this.fontData = fontData;
				this.originalSize = originalSize;
				this.pageWidth = pageWidth;
				this.pageHeight = pageHeight;
				this.pagePaths = pagePaths;
				this.pagePixmaps = new Pixmap[pagePaths.length];
			}
		}

		/**
		 * .fnt を解析して字形表を組む。<b>純 CPU・GL 不要</b>なので背景スレッドから呼べる。
		 *
		 * <p>🔴 実測: 1 ファイル 1.9MB / 17114 字形。これを GL スレッドでやると 1 個あたり
		 * 数百 ms〜秒オーダーになり、皮膚は十数個の .fnt を参照するので単一フレームが
		 * 5 秒を超える。主スレッドは {@code SurfaceView.surfaceChanged()} で「GL スレッドが
		 * resize を処理し終える」のを同期待ちしているため、そのまま ANR になる
		 * （2026-10-12 LEGION Y700 で実害。ANR スタックがこの行に乗っていた）。
		 *
		 * @return 解析結果。ファイルが開けない等は null
		 */
		public static ParseResult parseFont(File _fontPath) {
			try {
				FileHandle fh = resolveFileHandle(_fontPath);
				if (Gdx.app != null) {
					Gdx.app.log("FontDebug", "Loading .fnt: " + fh.path() + ", type: " + fh.type()
							+ ", exists: " + fh.exists());
				}
				BitmapFont.BitmapFontData _fontData = new BitmapFont.BitmapFontData(fh, false);

				// size が BitmapFont から取得できないので、独自に取得する
				float _originalSize;
				float _pageWidth = 0;
				float _pageHeight = 0;
				try (BufferedReader reader = new BufferedReader(new InputStreamReader(fh.read()), 512)) {
					String line = reader.readLine();
					_originalSize = (float) Integer.parseInt(line.substring(line.indexOf("size=") + 5).split(" ")[0]);
					line = reader.readLine();
					_pageWidth = (float) Integer.parseInt(line.substring(line.indexOf("scaleW=") + 7).split(" ")[0]);
					_pageHeight = (float) Integer.parseInt(line.substring(line.indexOf("scaleH=") + 7).split(" ")[0]);
				} catch (Exception e) {
					// scaleW/scaleH が読めなくても originalSize だけは必ず埋める。
					// 0 のままだと draw の size / originalSize が 0 除算になる。
					_originalSize = _fontData.lineHeight;
				}
				// ページ画像の実パスもここ（背景スレッド）で解決しておく。
				// デコード自体は decodePagePixmaps() が続けて行う。
				String fontPathStr = fh.path().replace("\\", "/");
				int lastSlash = fontPathStr.lastIndexOf('/');
				String fontDir = lastSlash > 0 ? fontPathStr.substring(0, lastSlash + 1) : "";
				String[] _pagePaths = new String[_fontData.imagePaths.length];
				for (int i = 0; i < _pagePaths.length; i++) {
					_pagePaths[i] = resolvePagePath(fontDir, _fontData.imagePaths[i]);
				}
				return new ParseResult(fh, _fontData, _originalSize, _pageWidth, _pageHeight, _pagePaths);
			} catch (Throwable e) {
				if (Gdx.app != null) {
					Gdx.app.error("FontError", "Failed to load .fnt file: " + _fontPath, e);
				}
				return null;
			}
		}

		/**
		 * .fnt の {@code page} 行に書かれたファイル名を実パスへ解決する。
		 *
		 * <p>元は {@code assemble()} の中にあったロジックをそのまま切り出したもの。
		 * デコードを背景スレッドへ移すにあたり、パス解決も解析側で済ませる必要が出たため。
		 */
		private static String resolvePagePath(String fontDir, String pageFileName) {
			String name = pageFileName.replace("\\", "/");
			boolean isAbsolutePath = name.startsWith("/")
					|| name.contains("/storage/emulated") || name.contains("/Android/data");
			if (isAbsolutePath) {
				return name;
			}
			if (name.contains("/") && !name.contains("/storage/") && !name.contains("/Android/data")) {
				return fontDir + name;
			}
			return name.contains("/") ? name : fontDir + name;
		}

		/**
		 * 各ページ画像を Pixmap へデコードする。<b>背景スレッド専用</b>。
		 *
		 * <p>🔴 ここが「操作した瞬間にカクつく」の主要因。実測の皮膚では 1 ページが
		 * 2048×2048 の PNG（16.8MB）で、しかも 1 個の .fnt が 5 ページ参照する
		 * （= 1 フォントあたり 84MB のファイルを読んで 80MB の Pixmap を作る）。
		 * これを GL スレッドでやると 1 フォントあたり 1〜2.5 秒かかり、
		 * 界面を開いた瞬間のコマ落ち・ANR になる。
		 * デコードは純 CPU（OpenGL 不要）なので背景へ出せる。
		 *
		 * <p>⚠️ {@code PixmapResourcePool} は<b>通さない</b>。あのプールは
		 * {@code SkinLoader.load()} のたびに {@code disposeOld()} で古い世代を破棄するので、
		 * 長生きする {@link ParseResult} から参照すると
		 * 「解放済み Pixmap を new Texture する」ことになり落ちる。
		 * ここで作った Pixmap の寿命は {@code assemble()} まで（アップロード直後に解放）。
		 */
		private void decodePagePixmaps(ParseResult pr) {
			for (int i = 0; i < pr.pagePaths.length; i++) {
				if (sourceDisposed) {
					// dispose() 済み。以降のページは作らない
					// （作っても誰も解放しないまま漏れるため）。
					return;
				}
				try {
					long t0 = System.currentTimeMillis();
					Pixmap pm = PixmapResourcePool.loadPicture(pr.pagePaths[i]);
					pr.pagePixmaps[i] = pm;
					if (pm != null && Gdx.app != null) {
						Gdx.app.log("FontPerf", "decode page " + i + " "
								+ pm.getWidth() + "x" + pm.getHeight()
								+ " in " + (System.currentTimeMillis() - t0) + "ms : " + pr.pagePaths[i]);
					}
				} catch (Throwable t) {
					pr.pagePixmaps[i] = null;
				}
			}
		}

		/**
		 * 解析済みデータにページ Texture を結び付けて {@link CacheableBitmapFont} にする。
		 * <b>GL スレッド専用</b>（Texture を作るため）。
		 */
		private CacheableBitmapFont assemble(ParseResult pr, int _type) {
			Array<TextureRegion> _regions = new Array<>(pr.pagePaths.length);
			long assembleStart = System.currentTimeMillis();
			for (int i = 0; i < pr.pagePaths.length; ++i) {
				// 🔴 ここは「アップロード」だけ。PNG のデコードは背景スレッドの
				// decodePagePixmaps() で済ませてある（16MB × 5 ページのデコードを
				// GL スレッドでやると界面を開いた瞬間に 1〜2.5 秒固まる）。
				Pixmap pixmap = pr.pagePixmaps[i];
				Texture tex = null;
				if (pixmap != null) {
					try {
						tex = new Texture(pixmap, useMipMaps);
					} catch (Throwable t) {
						tex = null;
					} finally {
						// アップロードが終われば CPU 側の Pixmap は不要。
						// 参照を切ってから解放する（二重 dispose を避ける）。
						pr.pagePixmaps[i] = null;
						pixmap.dispose();
					}
				}
				if (tex == null) {
					if (Gdx.app != null) {
						Gdx.app.error("FontDebug", "Failed to upload page " + i + ": " + pr.pagePaths[i]);
					}
					// Use a 1x1 white placeholder texture to avoid crash
					Pixmap placeholder = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
					placeholder.setColor(1, 1, 1, 1);
					placeholder.fill();
					tex = new Texture(placeholder);
					placeholder.dispose();
				}
				_regions.add(new TextureRegion(tex));
			}

			BitmapFont _font = null;
			try {
				_font = new BitmapFont(pr.fontData, _regions, true);
			} catch (Throwable e) {
				if (Gdx.app != null) {
					Gdx.app.error("FontError", "Failed to assemble .fnt font: " + fontPath, e);
				}
			}

			float _pageWidth = pr.pageWidth;
			float _pageHeight = pr.pageHeight;
			if ((_pageWidth <= 0 || _pageHeight <= 0) && _regions.size > 0) {
				_pageWidth = (float) _regions.get(0).getRegionWidth();
				_pageHeight = (float) _regions.get(0).getRegionHeight();
			}

			CacheableBitmapFont result = new CacheableBitmapFont();
			result.fontData = pr.fontData;
			result.regions = _regions;
			result.font = _font;
			result.originalSize = pr.originalSize;
			result.type = _type;
			result.pageWidth = _pageWidth;
			result.pageHeight = _pageHeight;

			if (Gdx.app != null) {
				Gdx.app.log("FontPerf", "assemble " + pr.pagePaths.length + " pages in "
						+ (System.currentTimeMillis() - assembleStart) + "ms : "
						+ (fontPath != null ? fontPath.getName() : "?"));
			}

			return result;
		}

		/**
		 * 同期版（解析 + 装配をまとめて実行）。既存の呼び出し元とテストの入口として残す。
		 * <b>GL スレッドを長時間ブロックしうる</b>ので、通常の描画経路は
		 * {@link #getFont()} の非同期経路を通ること。
		 */
		public CacheableBitmapFont createCacheableFont(File _fontPath, int _type) {
			ParseResult pr = parseFont(_fontPath);
			if (pr == null) {
				// 解析失敗。旧実装と同じく「font == null の結果」を返し、
				// 描画側（draw の null ガード）で無描画に落とす。
				CacheableBitmapFont failed = new CacheableBitmapFont();
				failed.type = _type;
				return failed;
			}
			// 同期版はデコードもここで済ませる（アップロード元の Pixmap が要るため）。
			// ⚠️ 背景版と違い GL スレッドを 1〜2.5 秒止める。通常の描画経路からは呼ばないこと。
			decodePagePixmaps(pr);
			return assemble(pr, _type);
		}

		/** 背景スレッドで解析を 1 回だけ走らせる。 */
		private void startAsyncParse() {
			final File target = fontPath;
			try {
				PARSE_POOL.execute(() -> {
					try {
						ParseResult pr = parseFont(target);
						if (pr != null) {
							// 🔴 解析（.fnt テキスト）に続けてページ画像のデコードまで済ませてから
							// 公開する。デコードは 1 フォントあたり 84MB のファイルを読む重い処理で、
							// これを GL スレッドに残すと界面を開いた瞬間に固まる。
							decodePagePixmaps(pr);
							// volatile 書き込み。GL スレッドは次の getFont() で拾う。
							asyncParsed = pr;
							if (Gdx.app != null) {
								Gdx.app.log("FontDebug", "Async parsed .fnt: " + target.getName());
							}
						} else {
							parseFailed = true;
						}
					} catch (Throwable e) {
						parseFailed = true;
						if (Gdx.app != null) {
							Gdx.app.error("FontError", "Async parse failed: " + target, e);
						}
					} finally {
						parseFinishedAt = System.currentTimeMillis();
					}
				});
			} catch (Throwable e) {
				// プールが使えない異常時はその場で同期解析する
				// （描画は 1 回止まるが、文字が出ないまま固まるよりはよい）
				ParseResult pr = parseFont(target);
				if (pr != null) {
					decodePagePixmaps(pr);
					asyncParsed = pr;
				} else {
					parseFailed = true;
				}
				parseFinishedAt = System.currentTimeMillis();
			}
		}

		public BitmapFont getFont() {
			if (BitmapFontCache.Has(fontPath)) {
				applyCached(BitmapFontCache.Get(fontPath));
				return font;
			}

			// キャッシュ未ヒット。解析がまだなら背景スレッドを起動するだけで、
			// このフレームは描かない（SkinTextBitmap.draw に font == null ガードがある）。
			ParseResult pr = asyncParsed;
			if (pr != null && pr.pixmapsConsumed) {
				// 前回のアップロードで Pixmap を使い切った（resume() 後の GL コンテキスト再構築など、
				// もう一度装配が必要になった場合）。デコード済み Pixmap は残っていないので、
				// 背景解析（デコード込み）をやり直す。
				asyncParsed = null;
				parseStarted = false;
				parseFailed = false;
				pr = null;
			}
			if (pr == null) {
				if (!parseStarted) {
					parseStarted = true;
					startAsyncParse();
				} else if (parseFailed
						&& System.currentTimeMillis() - parseFinishedAt > PARSE_RETRY_INTERVAL_MS) {
					// 失敗したまま永久に固まらないよう、間隔を空けて再試行する
					parseFailed = false;
					startAsyncParse();
				}
				return null;
			}

			// 解析＋デコード済み → GL スレッドは「アップロード」だけを行う。
			// 1 フレーム 1 フォントまで（同一フレームに複数をアップロードすると
			// 単一フレームが 5 秒を超えて ANR になる）。
			if (!BitmapFontCache.allowBuildThisFrame()) {
				return null;
			}
			CacheableBitmapFont built = assemble(pr, type);
			// Pixmap は assemble() 内でアップロード直後に解放済み。再装配には再解析が要る。
			pr.pixmapsConsumed = true;
			BitmapFontCache.Set(fontPath, built);
			applyCached(built);
			return font;
		}

		/** キャッシュ済みの一式をこの source のフィールドへ展開する。 */
		private void applyCached(CacheableBitmapFont cached) {
			fontData = cached.fontData;
			regions = cached.regions;
			font = cached.font;
			originalSize = cached.originalSize;
			type = cached.type;
			pageWidth = cached.pageWidth;
			pageHeight = cached.pageHeight;
		}

		public float getOriginalSize() {
			return originalSize;
		}

		public int getType() {
			return type;
		}

		public void setType(int type) {
			this.type = type;
		}

		public float getPageWidth() {
			return pageWidth;
		}

		public float getPageHeight() {
			return pageHeight;
		}

		@Override
		public void dispose() {
			// 背景デコードを中断させる（decodePagePixmaps が sourceDisposed を見て止まる）。
			sourceDisposed = true;
			// デコード済みだがまだアップロードしていない Pixmap が残っていたら解放する
			// （解析完了〜装配の間に皮膚が破棄された場合）。
			// 残りはもう使えないので pixmapsConsumed を立てておく
			// ⇒ 次に必要になったら getFont() が背景解析からやり直す。
			ParseResult pr = asyncParsed;
			if (pr != null) {
				for (int i = 0; i < pr.pagePixmaps.length; i++) {
					Pixmap pm = pr.pagePixmaps[i];
					if (pm != null) {
						pr.pagePixmaps[i] = null;
						try { pm.dispose(); } catch (Throwable ignore) {}
					}
				}
				pr.pixmapsConsumed = true;
			}
			if (font != null) {
				font.dispose();
				font = null;
			}
		}
	}
}
