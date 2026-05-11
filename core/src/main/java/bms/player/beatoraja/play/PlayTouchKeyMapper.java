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
 * 触摸按键映射器 - 基于 Skin 的 laneregion 动态创建触摸区域
 *
 * 支持所有键数的模式（5key/7key/9key/14key等），自动适配
 */
public class PlayTouchKeyMapper implements InputProcessor, Disposable {

    private Stage stage;
    private TouchKeyButton[] keyButtons;
    private BMSPlayerInputProcessor inputProcessor;
    private BMSPlayer player;
    private LaneProperty laneProperty;
    private boolean enabled = false;
    private boolean regionsInitialized = false;
    private Texture whitePixel;

    private int logicW;
    private int logicH;

    // pointer ID -> 按下的按键索引 (-1 表示未按在任何键上)
    private int[] pointerMap = new int[64];

    private final Matrix4 oldProj = new Matrix4();

    private static final Color SCRATCH_COLOR = new Color(0.8f, 0.2f, 0.2f, 0.3f);
    private static final Color WHITE_KEY_COLOR = new Color(0.9f, 0.9f, 0.9f, 0.3f);
    private static final Color BLACK_KEY_COLOR = new Color(0.3f, 0.3f, 0.3f, 0.3f);
    private static final Color LABEL_COLOR = new Color(1.0f, 1.0f, 1.0f, 0.8f);

    public PlayTouchKeyMapper(BMSPlayer player, Resolution resolution, BMSPlayerInputProcessor inputProcessor, LaneProperty laneProperty) {
        this.player = player;
        this.inputProcessor = inputProcessor;
        this.laneProperty = laneProperty;
        this.logicW = resolution.width;
        this.logicH = resolution.height;

        stage = new Stage(new FitViewport(logicW, logicH));
        Pixmap pixmap = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pixmap.setColor(Color.WHITE);
        pixmap.fill();
        whitePixel = new Texture(pixmap);
        pixmap.dispose();

        // 初始化空的按钮数组，将在 updateRegionsFromSkin 中填充
        keyButtons = new TouchKeyButton[0];

        for (int i = 0; i < pointerMap.length; i++) pointerMap[i] = -1;
    }

    /**
     * 从 Skin 的 laneregion 更新触摸区域
     * 需要在 skin 加载完成后调用
     */
    public void updateRegionsFromSkin() {
        PlaySkin skin = (PlaySkin) player.getSkin();
        if (skin == null) {
            Gdx.app.log("PlayTouchKeyMapper", "Skin not available yet, skip update");
            return;
        }

        Rectangle[] laneRegions = skin.getLaneRegion();
        if (laneRegions == null || laneRegions.length == 0) {
            Gdx.app.log("PlayTouchKeyMapper", "Lane region not available, skip update");
            return;
        }

        
        // 清除旧的按钮

        // 根据 laneregion 数量创建新按钮
        keyButtons = new TouchKeyButton[laneRegions.length];

        // 触摸区域向下延伸的额外高度（像素）
        float touchExtension = logicH * 0.15f;

        for (int i = 0; i < laneRegions.length; i++) {
            Rectangle r = laneRegions[i];
            // beatoraja skin 使用 Y 轴向下坐标（原点左上），Stage 使用 Y 轴向上坐标
            // 需要将 skin 坐标转换为 stage 坐标
            float stageY = logicH - r.y - r.height;

            // 扩展触摸区域高度
            float extendedY = stageY - touchExtension;
            float extendedHeight = r.height + touchExtension * 2;
            // 确保不超出屏幕边界
            if (extendedY < 0) {
                extendedHeight += extendedY;
                extendedY = 0;
            }
            if (extendedY + extendedHeight > logicH) {
                extendedHeight = logicH - extendedY;
            }
            keyButtons[i] = new TouchKeyButton(r.x, extendedY, r.width, extendedHeight, "", Color.CLEAR);
            stage.addActor(keyButtons[i]);
        }

        regionsInitialized = true;
    }

    /**
     * 根据 lane 索引和总数获取键标签
     */
    private String getKeyLabel(int laneIdx, int totalLanes) {
        // 判断是否是 scratch 键（根据 Mode.scratchKey 规则：scratch 在特定索引位置）
        if (isScratchKey(laneIdx, totalLanes)) {
            return "SCR";
        }
        // 返回键号（从 1 开始）
        int keyNum = getLogicalKeyNumber(laneIdx, totalLanes);
        return String.valueOf(keyNum);
    }

    /**
     * 根据 lane 索引和总数判断是否是 scratch 键
     */
    private boolean isScratchKey(int laneIdx, int totalLanes) {
        // 根据 Mode 定义判断 scratch 位置
        // BEAT_5K: scratch at 5 (total 6)
        // BEAT_7K: scratch at 7 (total 8)
        // BEAT_14K: scratch at 7, 15 (total 16)
        // POPN_9K: no scratch (total 9)
        if (totalLanes == 6) {
            return laneIdx == 5; // BEAT_5K
        } else if (totalLanes == 8) {
            return laneIdx == 7; // BEAT_7K
        } else if (totalLanes == 16) {
            return laneIdx == 7 || laneIdx == 15; // BEAT_14K
        } else if (totalLanes == 9) {
            return false; // POPN_9K has no scratch
        }
        // 默认处理：假设 totalLanes - 1 是 scratch（适用于单 scratch 模式）
        if (totalLanes > 1 && laneIdx == totalLanes - 1) {
            return true;
        }
        return false;
    }

    /**
     * 获取逻辑键号（从 1 开始，scratch 返回特殊值）
     * lane index 直接对应 logical key index
     */
    private int getLogicalKeyNumber(int laneIdx, int totalLanes) {
        // 对于单 scratch 模式（如 7K），scratch 是最后一个
        // 对于双 scratch 模式（如 14K），两个 scratch 各有各的索引
        // 对于无 scratch 模式（如 9K），直接返回 laneIdx + 1
        if (totalLanes == 9) {
            // POPN_9K: 1-9
            return laneIdx + 1;
        }
        // 其他模式：lane index 就是 logical key index
        return laneIdx;
    }

    /**
     * 根据 lane 索引和总数获取键背景颜色
     */
    private Color getKeyColor(int laneIdx, int totalLanes) {
        if (isScratchKey(laneIdx, totalLanes)) {
            return SCRATCH_COLOR;
        }
        // 交替颜色
        if (laneIdx % 2 == 0) {
            return WHITE_KEY_COLOR;
        } else {
            return BLACK_KEY_COLOR;
        }
    }

    /**
     * 同步按键状态到核心层
     */
    private void syncKeyState(int laneIdx) {
        if (laneIdx < 0 || laneIdx >= keyButtons.length) return;
        boolean stillPressed = false;
        for (int p = 0; p < pointerMap.length; p++) {
            if (pointerMap[p] == laneIdx) {
                stillPressed = true;
                break;
            }
        }

        // 通过 LaneProperty 获取该 lane 对应的真正 key index
        int keyIdx = laneIdx;
        if (laneProperty != null) {
            int[][] laneToKey = laneProperty.getLaneToKey();
            if (laneIdx < laneToKey.length && laneToKey[laneIdx].length > 0) {
                keyIdx = laneToKey[laneIdx][0];
            }
        }
        inputProcessor.setKeyChanged(keyIdx, stillPressed, player.timer.getNowMicroTime(SkinProperty.TIMER_PLAY));
    }

    public void render(SpriteBatch sprite, BitmapFont font) {
        if (!enabled) return;

        // 清理已断开的指针
        for (int p = 0; p < pointerMap.length; p++) {
            if (pointerMap[p] != -1 && !Gdx.input.isTouched(p)) {
                int oldKeyIdx = pointerMap[p];
                pointerMap[p] = -1;
                syncKeyState(oldKeyIdx);
            }
        }

        // 触摸区域不绘制，只保留触摸检测功能
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
        if (!regionsInitialized) return false;
        if (pointer >= pointerMap.length) return false;

        Vector2 coords = stage.screenToStageCoordinates(new Vector2(screenX, screenY));
        for (int i = 0; i < keyButtons.length; i++) {
            if (keyButtons[i] != null && keyButtons[i].getBounds().contains(coords.x, coords.y)) {
                pointerMap[pointer] = i;
                syncKeyState(i);
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean touchUp(int screenX, int screenY, int pointer, int button) {
        if (!enabled) return false;
        if (!regionsInitialized) return false;
        if (pointer < pointerMap.length && pointerMap[pointer] != -1) {
            int keyIdx = pointerMap[pointer];
            pointerMap[pointer] = -1;
            syncKeyState(keyIdx);
            return true;
        }
        return true;
    }

    @Override
    public boolean touchDragged(int screenX, int screenY, int pointer) {
        if (!enabled) return false;
        if (!regionsInitialized) return false;
        // 消费所有触摸拖拽事件，防止传播到 Stage
        return pointer < pointerMap.length;
    }

    @Override public boolean touchCancelled(int screenX, int screenY, int pointer, int button) { return touchUp(screenX, screenY, pointer, button); }
    @Override public void dispose() { stage.dispose(); whitePixel.dispose(); }
    public boolean isEnabled() { return enabled; }
    public boolean isRegionsInitialized() { return regionsInitialized; }

    public void setLaneProperty(LaneProperty laneProperty) {
        this.laneProperty = laneProperty;
    }

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
            this.label = label;
            this.bgColor = bgColor;
            this.bounds = new Rectangle(x, y, w, h);
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
