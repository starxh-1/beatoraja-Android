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
import bms.player.beatoraja.skin.SkinObject;
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
    // pointer ID -> 按下时的游戏时间戳（微秒），在 touchDown 时立即捕获
    private long[] pointerPressTime = new long[64];

    private final Matrix4 oldProj = new Matrix4();

    private static final Color SCRATCH_COLOR = new Color(0.8f, 0.2f, 0.2f, 0.0f);
    private static final Color WHITE_KEY_COLOR = new Color(0.9f, 0.9f, 0.9f, 0.0f);
    private static final Color BLACK_KEY_COLOR = new Color(0.3f, 0.3f, 0.3f, 0.0f);
    private static final Color LABEL_COLOR = new Color(1.0f, 1.0f, 1.0f, 0.0f);

    private boolean isPortrait = false;
    private static final int OP_PORTRAIT = 1101;

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

        for (int i = 0; i < pointerMap.length; i++) { pointerMap[i] = -1; pointerPressTime[i] = Long.MIN_VALUE; }
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

        // 根据 laneregion 数量创建新按钮
        keyButtons = new TouchKeyButton[laneRegions.length];

        for (int i = 0; i < laneRegions.length; i++) {
            String label = getKeyLabel(i, laneRegions.length);
            Color color = getKeyColor(i, laneRegions.length);
            keyButtons[i] = new TouchKeyButton(0, 0, 0, 0, label, color);
            stage.addActor(keyButtons[i]);
        }

        regionsInitialized = true;
    }

    /**
     * 动态同步轨道位置到触摸区域，实现“跟着 LaneRenderer 走”
     */
    private void updateRegionsFromLanes() {
        if (!regionsInitialized) return;
        PlaySkin skin = (PlaySkin) player.getSkin();
        if (skin == null) return;

        SkinNote skinNote = null;
        for (SkinObject obj : skin.getAllSkinObjects()) {
            if (obj instanceof SkinNote) {
                skinNote = (SkinNote) obj;
                break;
            }
        }
        if (skinNote == null) return;

        SkinNote.SkinLane[] lanes = skinNote.getLanes();
        if (lanes == null || lanes.length != keyButtons.length) return;

        boolean isPortrait = player.getLanerender().isPortrait();
        float touchExtension = isPortrait ? logicH * 0.05f : logicH * 0.15f;

        for (int i = 0; i < lanes.length; i++) {
            Rectangle r = lanes[i].region; // 获取当前帧的轨道基础区域

            // 计算所有偏移量的总和（如 LIFT、LaneCover 等）
            float offsetX = 0;
            float offsetY = 0;
            for (SkinObject.SkinOffset o : lanes[i].getSkinOffsets()) {
                if (o != null) {
                    offsetX += o.x;
                    offsetY += o.y;
                }
            }

            float stageY;
            float finalX = r.x + offsetX;
            float finalY = r.y + offsetY;

            if (isPortrait) {
                // 竖屏逻辑：映射 Stage y=0 -> 右，y=1080 -> 左
                // 此时 finalY 包含 LIFT 等带来的偏移。直接使用以实现“跟着轨道走”。
                stageY = finalY;
            } else {
                // 横屏逻辑：正常 Y 轴翻转，同时应用偏移
                stageY = logicH - finalY - r.height;
            }

            // 扩展触摸区域高度
            // 在竖屏模式下，Y轴对应厚度，X轴对应轨道方向。
            // 为了防止按键“粘连”（冲突），在竖屏模式下取消 Y 轴扩展。
            float currentTouchExtension = isPortrait ? 0 : touchExtension;
            float extendedY = stageY - currentTouchExtension;
            float extendedHeight = r.height + currentTouchExtension * 2;

            // 确保不超出屏幕边界
            if (extendedY < 0) {
                extendedHeight += extendedY;
                extendedY = 0;
            }
            if (extendedY + extendedHeight > logicH) {
                extendedHeight = logicH - extendedY;
            }

            keyButtons[i].updateBounds(finalX, extendedY, r.width, extendedHeight);
        }
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
        // 对于 14K (16 lanes with 2 SCR)
        if (totalLanes == 16) {
            if (laneIdx < 7) return laneIdx;
            if (laneIdx > 7 && laneIdx < 15) return laneIdx - 8;
        }
        // 对于 7K/5K 等，直接返回 0-indexed 序号以匹配皮肤视觉标签 (0, 1, 2...)
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
    private void syncKeyState(int laneIdx, long pressTimeMicro) {
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

        // 使用 touchDown 时捕获的高精度时间戳（微秒），而非当前时间
        // TimerManager 已改为实时计算，此处时间精度不再受帧率限制
        final long timestamp = stillPressed ? pressTimeMicro
            : player.timer.getNowMicroTime(SkinProperty.TIMER_PLAY);
        inputProcessor.setKeyChanged(keyIdx, stillPressed, timestamp);
    }

    public void render(SpriteBatch sprite, BitmapFont font) {
        if (!enabled) return;

        // 同步位置
        updateRegionsFromLanes();

        // 清理已断开的指针
        for (int p = 0; p < pointerMap.length; p++) {
            if (pointerMap[p] != -1 && !Gdx.input.isTouched(p)) {
                int oldKeyIdx = pointerMap[p];
                pointerMap[p] = -1;
                final long pressTime = pointerPressTime[p];
                pointerPressTime[p] = Long.MIN_VALUE;
                syncKeyState(oldKeyIdx, pressTime);
            }
        }

        // 绘制触摸按键区域
        for (int i = 0; i < keyButtons.length; i++) {
            if (keyButtons[i] != null) {
                keyButtons[i].drawCustom(sprite, whitePixel, font);
            }
        }
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
                final long pressTime = pointerPressTime[i];
                pointerPressTime[i] = Long.MIN_VALUE;
                syncKeyState(keyIdx, pressTime);
            }
        }
    }

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        if (!enabled) return false;
        if (!regionsInitialized) return false;
        if (pointer >= pointerMap.length) return false;

        // 立即捕获高精度时间戳 -- TimerManager 现已实时计算，精度 ~1μs
        final long pressTime = player.timer.getNowMicroTime(SkinProperty.TIMER_PLAY);

        Vector2 coords = stage.screenToStageCoordinates(new Vector2(screenX, screenY));
        for (int i = 0; i < keyButtons.length; i++) {
            if (keyButtons[i] != null && keyButtons[i].getBounds().contains(coords.x, coords.y)) {
                pointerMap[pointer] = i;
                pointerPressTime[pointer] = pressTime;
                syncKeyState(i, pressTime);
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
            final long pressTime = pointerPressTime[pointer];
            pointerPressTime[pointer] = Long.MIN_VALUE;
            syncKeyState(keyIdx, pressTime);
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

        public void updateBounds(float x, float y, float w, float h) {
            this.bounds.set(x, y, w, h);
            setBounds(x, y, w, h);
        }

        public void drawCustom(SpriteBatch batch, Texture white, BitmapFont font) {
            batch.setColor(bgColor.r, bgColor.g, bgColor.b, 0f);
            batch.draw(white, bounds.x, bounds.y, bounds.width, bounds.height);
        }

        public Rectangle getBounds() { return bounds; }
    }

    @Override public boolean keyDown(int k) { return false; }
    @Override public boolean keyUp(int k) { return false; }
    @Override public boolean keyTyped(char c) { return false; }
    @Override public boolean mouseMoved(int x, int y) { return false; }
    @Override public boolean scrolled(float x, float y) { return false; }
}
