package bms.player.beatoraja.play.bga;

import com.badlogic.gdx.graphics.Texture;


public interface MovieProcessor {

	/**
	 * 更新视频状态。
	 * 建议在渲染线程但在 draw() 外部调用，以避免破坏 GL 状态。
	 * @param time 当前游戏相对时间
	 */
	default void update(long time) {
	}

	/**
	 * 获取当前已解码的纹理帧，不触发解码更新。
	 * @return 视频纹理
	 */
	public abstract Texture getFrame();

	/**
	 * 動画の再生を開始する
	 * @param loop ループ再生する場合はtrue
	 */
	public abstract void play(long time, boolean loop);

	/**
	 * 動作の再生を停止する
	 */
	public abstract void stop();

	/**
	 * リソースを解放する
 	 */
	public abstract void dispose();

	/**
	 * 切后台/锁屏时暂停视频播放，保护硬件解码器资源
	 */
	default void pause() {
		stop();
	}

	/**
	 * 切回前台时恢复视频播放
	 */
	default void resume() {
		// 默认不做操作，子类按需覆写
	}

	/**
	 * 获取渲染类型，用于选择正确的 Shader
	 * 默认返回 TYPE_FFMPEG(3)，gdx-video 实现返回 TYPE_LINEAR(1)
	 */
	default int getRenderType() {
		return 3; // SkinObjectRenderer.TYPE_FFMPEG
	}

	/**
	 * 预加载视频资源（可选实现）
	 * 用于在游戏开始前预先初始化解码器和加载视频文件，避免播放时的延迟
	 * 默认不做任何操作，子类按需覆写
	 */
	default void preload() {
		// 默认不做操作
	}

	/**
	 * 仅预加载解码器，不加载视频文件。
	 * 子类可覆写以提前创建硬件解码器，减少首次播放延迟。
	 * 默认调用 preload() 保持向后兼容。
	 */
	default void preloadDecoder() {
		preload();
	}
}
