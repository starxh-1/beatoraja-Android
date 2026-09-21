package bms.player.beatoraja.select;

import bms.player.beatoraja.Resolution;
import bms.player.beatoraja.input.KeyBoardInputProcesseor.ControlKeys;
import bms.player.beatoraja.select.bar.Bar;
import bms.player.beatoraja.select.bar.SearchWordBar;
import bms.player.beatoraja.skin.Skin;

import java.util.logging.Logger;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.scenes.scene2d.*;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.ui.TextField.TextFieldListener;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.FocusListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.badlogic.gdx.utils.viewport.StretchViewport;
import com.badlogic.gdx.utils.viewport.Viewport;

/**
 * 楽曲検索用テキストフィールド
 *
 * @author exch
 */
public class SearchTextField extends Stage {

	// TOTO ユーザー定義のBitmapFontも使えるようにしたい

	private final MusicSelector selector;

	/**
	 * フォント生成用クラス
	 */
	private FreeTypeFontGenerator generator;
	/**
	 * フォント
	 */
	private BitmapFont searchfont;

	private TextField search;
	/**
	 * 画面クリック感知用Actor
	 */
	private Group screen;

	/**
	 * 构造与主渲染完全一致的视口:
	 * <ul>
	 *   <li>世界尺寸用<b>皮肤分辨率</b>而非 config.resolution —— 搜索框区域 r 是从 LR2 模板
	 *       坐标换算到皮肤空间的（LR2SkinCSVLoader: dstw/dsth），主渲染的投影也是
	 *       setToOrtho2D(0,0,skinW,skinH)。若这里用 config.resolution(默认 HD)，皮肤是
	 *       FULLHD/SD 时两者坐标空间不一致，纵向（以及横向）就对不齐。</li>
	 *   <li>拉伸全屏(config.stretchFullscreen)时主渲染把皮肤线性铺满整个 surface，
	 *       对应 StretchViewport；等比模式下是 FitViewport（与 MainController 的
	 *       pillarbox/letterbox 计算等价）。</li>
	 * </ul>
	 */
	private static Viewport createViewport(MusicSelector selector, Resolution resolution) {
		Skin skin = selector.getSkin();
		float w = skin != null ? skin.getWidth() : 0;
		float h = skin != null ? skin.getHeight() : 0;
		if (w <= 0 || h <= 0) { // 皮肤未声明尺寸时回退到 config 分辨率
			w = resolution.width;
			h = resolution.height;
		}
		boolean stretch = selector.main != null && selector.main.getConfig() != null
				&& selector.main.getConfig().isStretchFullscreen();
		return stretch ? new StretchViewport(w, h) : new FitViewport(w, h);
	}

	public SearchTextField(MusicSelector selector, Resolution resolution) {
		super(createViewport(selector, resolution));
		this.selector = selector;

		final Rectangle r = ((MusicSelectSkin) selector.getSkin()).getSearchTextRegion();

		try {
			generator = new FreeTypeFontGenerator(bms.player.beatoraja.MainController.resolveFontFileHandle(selector.main.getConfig().getSystemfontpath()));
			FreeTypeFontGenerator.FreeTypeFontParameter parameter = new FreeTypeFontGenerator.FreeTypeFontParameter();
			parameter.size = (int) r.height;
			parameter.incremental = true;
			searchfont = generator.generateFont(parameter);

			final TextField.TextFieldStyle textFieldStyle = new TextField.TextFieldStyle(); // background
			textFieldStyle.font = searchfont;
			textFieldStyle.fontColor = Color.WHITE;

			Pixmap cursorp = new Pixmap(8, 8, Pixmap.Format.RGBA8888);
			cursorp.setColor(Color.toIntBits(255, 255, 255, 255));
			cursorp.fill();
			textFieldStyle.cursor = new TextureRegionDrawable(new TextureRegion(new Texture(cursorp)));
			cursorp.dispose();

			Pixmap selectionp = new Pixmap(2, 8, Pixmap.Format.RGBA8888);
			selectionp.setColor(Color.toIntBits(255, 255, 255, 255));
			selectionp.fill();
			textFieldStyle.selection = new TextureRegionDrawable(new TextureRegion(new Texture(selectionp)));
			selectionp.dispose();

			textFieldStyle.messageFont = searchfont;
			textFieldStyle.messageFontColor = Color.GRAY;

			search = new TextField("", textFieldStyle);
			search.setMessageText("search song");
			search.setTextFieldListener(new TextFieldListener() {

				public void keyTyped(TextField textField, char key) {
					if (key == '\n' || key == 13) {
						if (textField.getText().length() > 0) {
							SearchWordBar swb = new SearchWordBar(selector, textField.getText());
							int count = swb.getChildren().length;
							if (count > 0) {
								selector.getBarManager().addSearch(swb);
								selector.getBarManager().updateBar(null);
								selector.getBarManager().setSelected(swb);
								textField.setText("");
								textField.setMessageText(count + " song(s) found");
								textFieldStyle.messageFontColor = Color.valueOf("00c0c0");
							} else {
								textField.setText("");
								textField.setMessageText("no song found");
								textFieldStyle.messageFontColor = Color.DARK_GRAY;
								selector.main.getInputProcessor().isControlKeyPressed(ControlKeys.ENTER);
							}
						} else {
							// 空输入回车 = 删除光标当前停着的那个搜索 folder。
							// 搜索结果会一直挂在根目录里（看过一次还想再看），所以需要一个删除手势：
							// 滑到 `Search : 'love'` 上，打开搜索框不输入任何东西直接回车，就删掉这一条
							// （别的搜索 folder 不动）。空输入本来就搜不出结果，正好拿来当删除。
							final BarManager barmanager = selector.getBarManager();
							final Bar selected = barmanager.getSelected();
							if (barmanager.removeSearch(selected)) {
								barmanager.updateBar(null);
							} else {
								textField.setMessageText("no search folder here");
								textFieldStyle.messageFontColor = Color.DARK_GRAY;
							}
							// 必须吞掉这次 ENTER：上面删完会重建列表、光标落到别的 bar 上，
							// 若这次按下漏给 MusicSelectInputProcessor，会被当成「打开 folder /
							// 开始游戏」（ControlKeys.ENTER 的 text=false，文本输入态下照样被记录）。
							// 既有的 "no song found" 分支出于同样原因也在吞。
							selector.main.getInputProcessor().isControlKeyPressed(ControlKeys.ENTER);
						}

						Gdx.app.log("SearchTextField", "Deactivating text mode: Enter/Newline pressed");
						deactivateTextInput(selector, false);
					}
					if (!searchfont.getData().hasGlyph(key)) {
						FreeTypeFontGenerator.FreeTypeFontParameter parameter = new FreeTypeFontGenerator.FreeTypeFontParameter();
						parameter.size = (int) r.height;
						parameter.characters += textField.getText() + key;
						BitmapFont newsearchfont = generator.generateFont(parameter);
						textFieldStyle.font = newsearchfont;
						textFieldStyle.messageFont = newsearchfont;
						searchfont.dispose();
						searchfont = newsearchfont;
						textField.appendText(String.valueOf(key));
					}

				}

			});
			search.setBounds(r.x, r.y, r.width, r.height);
			search.setMaxLength(50);
			search.setFocusTraversal(false);

			search.addListener(new FocusListener() {
				@Override
				public void keyboardFocusChanged(FocusListener.FocusEvent event, Actor actor, boolean focused) {
					if (!focused) {
						Gdx.app.log("SearchTextField", "Focus lost, deactivating text input");
						deactivateTextInput(selector, false);
					}
				}
			});

			// 覆盖 OnscreenKeyboard，防止 libGDX 的 DefaultAndroidInput 独立控制 IME。
			// 键盘的显示/隐藏统一由 AndroidLauncher.setTextInputActive() 管理，
			// 避免两套键盘控制逻辑互相冲突导致 IME 闪烁。
			search.setOnscreenKeyboard(new TextField.OnscreenKeyboard() {
				@Override
				public void show(boolean visible) {
					if (!visible) {
						Gdx.input.setOnscreenKeyboardVisible(false);
					}
				}
			});

			search.setVisible(true);

			// 关键：使用touchDown（在获得焦点前）而不是clicked，确保isTextInputActive在键盘显示前已设置
			// 防止onWindowFocusChanged在TextField获得焦点时立即隐藏键盘
			search.addListener(new InputListener() {
				@Override
				public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
					// 在触摸按下时立即设置文本输入模式
					selector.main.getInputProcessor().getKeyBoardInputProcesseor()
						.setTextInputMode(true);
					return false; // 返回false让事件继续传递给TextField
				}
			});

			screen = new Group();
			// 世界坐标 = 皮肤空间（与 r 一致），全屏点击判定才不会错位
			screen.setBounds(0, 0, getViewport().getWorldWidth(), getViewport().getWorldHeight());
			screen.addListener(new ClickListener() {
				@Override
				public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
					// 只有在点击搜索框外部时才调用 unfocus
					boolean focusNotNull = getKeyboardFocus() != null;
					boolean outside = !r.contains(x, y);

					// 添加详细日志，帮助定位是否由于坐标不匹配导致误触发 unfocus
					if (focusNotNull) {
						Gdx.app.log("SearchTextField", "Touch detected while focused: x=" + x + ", y=" + y + ", r=" + r + ", outside=" + outside);
					}

					if (focusNotNull && outside) {
						Gdx.app.log("SearchTextField", "Triggering deactivateTextInput from screen touchDown");
						deactivateTextInput(selector, true);
					}
					return false;
				}
			});
			screen.addActor(search);
			addActor(screen);
		} catch (GdxRuntimeException e) {
			Logger.getGlobal().warning("Search Text読み込み失敗");
		}
	}

	private boolean isDeactivating = false;

	public void deactivateTextInput(MusicSelector selector, boolean resetText) {
		if (isDeactivating) return;
		isDeactivating = true;
		try {
			Gdx.app.log("SearchTextField", "deactivateTextInput() called, resetText=" + resetText);
			if (search != null) {
				if (resetText) {
					search.setText("");
					search.setMessageText("search song");
					search.getStyle().messageFontColor = Color.GRAY;
				}
				search.getOnscreenKeyboard().show(false);
			}
			if (getKeyboardFocus() == search) {
				setKeyboardFocus(null);
			}
			selector.main.getInputProcessor().getKeyBoardInputProcesseor().setTextInputMode(false);
		} finally {
			isDeactivating = false;
		}
	}

	public void unfocus(MusicSelector selector) {
		deactivateTextInput(selector, true);
	}

	public void dispose() {
//		super.dispose();
		if (search != null && selector != null) {
			deactivateTextInput(selector, false);
		}
		if(generator != null) {
			generator.dispose();
			generator = null;
		}
		if(searchfont != null) {
			searchfont.dispose();
			searchfont = null;
		}
	}

	public Rectangle getSearchBounds() {
		return search != null ? new Rectangle(search.getX(), search.getY(), search.getWidth(), search.getHeight()) : null;
	}
}
