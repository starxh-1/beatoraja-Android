package bms.player.beatoraja.result.debug;

import com.badlogic.gdx.Gdx;

import java.util.logging.Logger;

/**
 * 用于诊断 Result 界面卡死问题的内联探针。
 * 将本类中的方法调用插入到 MusicResult 的关键路径中，通过 logcat 观察时序。
 *
 * 使用方法：在 MusicResult.create() / render() / input() 等方法的
 * 不同阶段插入 Diagnostics.log("阶段名") 即可。
 *
 * 测试环境注册：
 *   adb logcat -s ResultFreezeDiag:V
 */
public final class ResultFreezeDiagnostics {

    private static final String TAG = "ResultFreezeDiag";
    private static final Logger log = Logger.getLogger(TAG);
    private static long lastTimestamp = 0;
    private static long freezeStartTime = 0;
    private static int frozenFrameCount = 0;
    private static final long FREEZE_THRESHOLD_MS = 500;

    public static void log(String stage) {
        long now = System.currentTimeMillis();
        long elapsed = lastTimestamp > 0 ? now - lastTimestamp : 0;
        String msg = String.format("[%tT.%tL] %-40s (+%dms)",
            now, now, stage, elapsed);
        safeLog(msg);
        lastTimestamp = now;
    }

    private static void safeLog(String msg) {
        try {
            if (Gdx.app != null) {
                Gdx.app.log(TAG, msg);
            } else {
                System.out.println(TAG + ": " + msg);
            }
        } catch (Exception e) {
            System.out.println(TAG + ": " + msg);
        }
    }

    public static void probeCreateBegin(Object result) {
        log("create:start");
    }

    /** updateScoreDatabase() 调用 readScoreData() 前 */
    public static void probeReadScoreBegin() {
        log("create:readScoreData:begin");
    }

    /** updateScoreDatabase() 调用 readScoreData() 后 */
    public static void probeReadScoreEnd() {
        log("create:readScoreData:end");
    }

    /** loadSkin 前 */
    public static void probeLoadSkinBegin() {
        log("create:loadSkin:begin");
    }

    /** loadSkin 后 */
    public static void probeLoadSkinEnd() {
        log("create:loadSkin:end");
    }

    /** MusicResult.create() 结束 */
    public static void probeCreateEnd() {
        log("create:end");
    }

    /** Skin.prepare() 前 */
    public static void probeSkinPrepareBegin() {
        log("skin:prepare:begin");
    }

    /** Skin.prepare() 后 */
    public static void probeSkinPrepareEnd() {
        log("skin:prepare:end");
    }

    /** Skin.drawAllObjects() 调用前后 */
    public static void probeSkinDrawBegin() {
        log("skin:draw:begin");
    }

    public static void probeSkinDrawEnd() {
        log("skin:draw:end");
    }

    /** SkinGaugeGraphObject.prepare() 中 rebuildTextures 调用的耗时 */
    public static void probeGaugeGraphRebuildBegin() {
        log("gauge:rebuild:begin");
    }

    public static void probeGaugeGraphRebuildEnd() {
        log("gauge:rebuild:end");
    }

    /** MusicResult.render() 每帧调用 — 检测帧是否超时 */
    public static void probeRenderFrame() {
        long now = System.currentTimeMillis();
        if (lastTimestamp > 0) {
            long frameTime = now - lastTimestamp;
            if (frameTime > FREEZE_THRESHOLD_MS) {
                frozenFrameCount++;
                if (freezeStartTime == 0) {
                    freezeStartTime = now;
                }
                log("render: FREEZE #" + frozenFrameCount + " frameTime=" + frameTime + "ms");
            }
        }
        lastTimestamp = now;
    }

    /** 检测 FADEOUT 计时器的启动 */
    public static void probeFadeoutStart(long sceneTime, long currentTime) {
        log("render:fadeout:start scene=" + sceneTime + " current=" + currentTime);
    }

    /** 检测用户按键触发退出 */
    public static void probeInputEscape() {
        log("input:ESCAPE pressed");
    }

    /** MusicResult.input() 中的状态 */
    public static void probeInputState(int state, boolean fadeoutActive, boolean startInputActive, boolean scoreDataNull) {
        if (frozenFrameCount > 0) {
            log(String.format("input:state=%d fadeout=%s input=%s scoreNull=%s (freeze frames=%d)",
                state, fadeoutActive, startInputActive, scoreDataNull, frozenFrameCount));
        }
    }

    /** 重置计数器 */
    public static void reset() {
        lastTimestamp = 0;
        freezeStartTime = 0;
        frozenFrameCount = 0;
    }

    // ═══════════════════════════════════════════════════════════
    // 诊断模式 2: Score 写入线程的 DB 锁竞态探测
    // ═══════════════════════════════════════════════════════════

    /**
     * 在 ScoreDatabaseAccessor 中获得锁时记录。
     * 如果看到 "lock:WAIT" 和 "lock:ACQUIRED" 之间间隔 > 500ms，
     * 说明存在锁等待。
     */
    public static void probeDBLockWait(String operation) {
        log("db:lock:WAIT " + operation);
    }

    public static void probeDBLockAcquired(String operation) {
        log("db:lock:ACQUIRED " + operation);
    }

    // ═══════════════════════════════════════════════════════════
    // 诊断模式 3: BGA Processor + 视频资源竞态探测
    // ═══════════════════════════════════════════════════════════

    /**
     * 在 BGAProcessor.prepare() / prepareBGA() 被调用时记录，
     * 确认 result 阶段是否意外被 BGA 渲染路径调用。
     */
    public static void probeBGAPrepare(boolean fromResult) {
        log("bga:prepare " + (fromResult ? "FROM_RESULT" : "FROM_PLAY"));
    }

    public static void probeBGADraw(boolean fromResult) {
        log("bga:draw " + (fromResult ? "FROM_RESULT" : "FROM_PLAY"));
    }

    // ═══════════════════════════════════════════════════════════
    // 诊断模式 4: 帧率与 continuousRendering 状态探测
    // ═══════════════════════════════════════════════════════════

    public static void probeFPS(int fps, int targetFPS) {
        if (frozenFrameCount > 0 && frozenFrameCount % 60 == 0) {
            log("fps:current=" + fps + " target=" + targetFPS + " (freeze active)");
        }
    }

    /**
     * 探测 continuous rendering 是否导致非预期的高速渲染
     */
    public static void probeContinuousRenderingState(boolean isContinuous) {
        log("continuousRendering=" + isContinuous);
    }
}
