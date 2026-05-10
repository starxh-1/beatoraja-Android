package bms.player.beatoraja.play;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.viewport.FitViewport;

import bms.player.beatoraja.Resolution;
import bms.player.beatoraja.input.BMSPlayerInputProcessor;
import bms.player.beatoraja.skin.SkinProperty;

/**
 * 兼容版 TouchKeyMapper：
 * 1. 禁用滑动切换按键：按下即绑定，滑动不改变响应键位。
 * 2. 兼容全设备：直接通过 BMSPlayerInputProcessor 触发逻辑键位，解决绑定 Controller 时的闪退问题。
 */
public class PlayTouchKeyMapper implements InputProcessor, Disposable {

    private Stage stage;
    private TouchKeyButton[] keyButtons;
    private BMSPlayerInputProcessor inputProcessor;
    private BMSPlayer player;
    private boolean enabled = false;
    private Texture whitePixel;

    private int logicW;
    private int logicH;

    // pointer ID -> 初始按下的按键索引 (-1 表示未按在键上)
    private int[] pointerMap = new int[64];

    private final Matrix4 oldProj = new Matrix4();

    private static final String[] KEY_LABELS = {"SCR", "1", "2", "3", "4", "5", "6", "7"};
    private static final Color SCRATCH_COLOR = new Color(0.8f, 0.2f, 0.2f, 0.1f);
    private static final Color WHITE_KEY_COLOR = new Color(0.9f, 0.9f, 0.9f, 0.1f);
    private static final Color BLACK_KEY_COLOR = new Color(0.3f, 0.3f, 0.3f, 0.1f);
    private static final Color LABEL_COLOR = new Color(1.0f, 1.0f, 1.0f, 0.1f);

    public PlayTouchKeyMapper(BMSPlayer player, Resolution resolution, BMSPlayerInputProcessor inputProcessor) {
        this.player = player;
        this.inputProcessor = inputProcessor;
        this.logicW = resolution.width;
        this.logicH = resolution.height;

        stage = new Stage(new FitViewport(logicW, logicH));
        Pixmap pixmap = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pixmap.setColor(Color.WHITE);
        pixmap.fill();
        whitePixel = new Texture(pixmap);
        pixmap.dispose();

        keyButtons = new TouchKeyButton[8];
        setupButtons();

        for (int i = 0; i < pointerMap.length; i++) pointerMap[i] = -1;
    }

    private void setupButtons() {
        float scratchWidth = logicW * 0.12f;
        float scratchHeight = logicH * 0.70f;
        float yOffset = logicH * 0.15f;

        // 索引说明：0=Scratch, 1~7=Key1~7
        keyButtons[0] = new TouchKeyButton(0, yOffset, scratchWidth, scratchHeight, KEY_LABELS[0], SCRATCH_COLOR);
        float keysX = scratchWidth;
        float keyWidth = (logicW - scratchWidth) / 7;
        for (int i = 0; i < 7; i++) {
            Color bgColor = (i % 2 == 0) ? WHITE_KEY_COLOR : BLACK_KEY_COLOR;
            keyButtons[i + 1] = new TouchKeyButton(keysX + i * keyWidth, yOffset, keyWidth, scratchHeight, KEY_LABELS[i + 1], bgColor);
        }
        for (TouchKeyButton button : keyButtons) stage.addActor(button);
    }

    /**
     * 同步按键状态到核心层
     * 只要有一个手指还在这个键上，状态就为 true
     */
    private void syncKeyState(int keyIdx) {
        if (keyIdx < 0 || keyIdx >= keyButtons.length) return;
        boolean stillPressed = false;
        for (int p = 0; p < pointerMap.length; p++) {
            if (pointerMap[p] == keyIdx) {
                stillPressed = true;
                break;
            }
        }

        // 核心修正：映射逻辑按键索引
        int logicIdx = (keyIdx == 0) ? 7 : keyIdx - 1;

        // 核心修正：使用 setKeyChanged 而不是 setKeyState。
        // setKeyChanged 会调用 keyChanged 并记录到 keylog 中，这是判定系统识别按键所必需的。
        // 使用游戏内的微秒时间戳 (TIMER_PLAY) 确保判定系统能正确匹配时间轴。
        inputProcessor.setKeyChanged(logicIdx, stillPressed, player.timer.getNowMicroTime(SkinProperty.TIMER_PLAY));
    }

    public void render(SpriteBatch sprite, BitmapFont font) {
        if (!enabled) return;

        // 指针粘滞清理
        for (int p = 0; p < pointerMap.length; p++) {
            if (pointerMap[p] != -1 && !Gdx.input.isTouched(p)) {
                int oldKeyIdx = pointerMap[p];
                pointerMap[p] = -1;
                syncKeyState(oldKeyIdx);
            }
        }

        oldProj.set(sprite.getProjectionMatrix());
        sprite.setProjectionMatrix(stage.getViewport().getCamera().combined);
        for (TouchKeyButton button : keyButtons) button.drawCustom(sprite, whitePixel, font);
        sprite.setProjectionMatrix(oldProj);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) resetAllKeys();
    }

    private void resetAllKeys() {
        for (int i = 0; i < pointerMap.length; i++) {
            if (pointerMap[i] != -1) {
                int keyIdx = pointerMap[i];
                pointerMap[i] = -1;
                syncKeyState(keyIdx);
            }
        }
    }

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        if (!enabled) return false;
        if (pointer >= pointerMap.length) return false;

        Vector2 coords = stage.screenToStageCoordinates(new Vector2(screenX, screenY));
        for (int i = 0; i < keyButtons.length; i++) {
            if (keyButtons[i].getBounds().contains(coords.x, coords.y)) {
                pointerMap[pointer] = i;
                syncKeyState(i);
                return true;
            }
        }
        // 消费事件但不映射到任何按键 - 防止触摸传播到Stage/FloatingMenu
        return true;
    }

    @Override
    public boolean touchUp(int screenX, int screenY, int pointer, int button) {
        if (!enabled) return false;
        if (pointer < pointerMap.length && pointerMap[pointer] != -1) {
            int keyIdx = pointerMap[pointer];
            pointerMap[pointer] = -1;
            syncKeyState(keyIdx);
            return true;
        }
        // 消费事件
        return true;
    }

    @Override
    public boolean touchDragged(int screenX, int screenY, int pointer) {
        if (!enabled) return false;
        // 消费所有触摸拖拽事件，防止传播到Stage
        return pointer < pointerMap.length;
    }

    @Override public boolean touchCancelled(int screenX, int screenY, int pointer, int button) { return touchUp(screenX, screenY, pointer, button); }
    @Override public void dispose() { stage.dispose(); whitePixel.dispose(); }
    public boolean isEnabled() { return enabled; }

    public boolean isConsumingTouch() {
        if (!enabled) return false;
        for (int p : pointerMap) {
            if (p != -1) return true;
        }
        return false;
    }

    private class TouchKeyButton extends Actor {
        private String label;
        private Color bgColor;
        private Rectangle bounds;
        public TouchKeyButton(float x, float y, float w, float h, String label, Color bgColor) {
            this.label = label; this.bgColor = bgColor; this.bounds = new Rectangle(x, y, w, h);
            setBounds(x, y, w, h);
        }
        public void drawCustom(SpriteBatch batch, Texture white, BitmapFont font) {
            batch.setColor(bgColor);
            batch.draw(white, bounds.x, bounds.y, bounds.width, bounds.height);
            batch.setColor(LABEL_COLOR);
            GlyphLayout layout = new GlyphLayout(font, label);
            font.draw(batch, label, bounds.x + (bounds.width - layout.width) / 2, bounds.y + (bounds.height + layout.height) / 2);
        }
        public Rectangle getBounds() { return bounds; }
    }

    @Override public boolean keyDown(int k) { return false; }
    @Override public boolean keyUp(int k) { return false; }
    @Override public boolean keyTyped(char c) { return false; }
    @Override public boolean mouseMoved(int x, int y) { return false; }
    @Override public boolean scrolled(float x, float y) { return false; }
}
