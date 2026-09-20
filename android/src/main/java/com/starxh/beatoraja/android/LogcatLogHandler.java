package com.starxh.beatoraja.android;

import android.util.Log;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * 把 core 模块的 {@code java.util.logging} 输出转发到 Android logcat。
 *
 * <p>core 里大量使用 {@code Logger.getGlobal().warning(...)}（皮肤加载、资源解析、预览渲染……），
 * 但 Java 日志默认写 stderr —— 在 Android 上等于丢进黑洞，真机 logcat 里一条都看不到。
 * 排查"皮肤预览画不出音符"时因此完全没有线索（只能靠看截图猜）。本 Handler 把它们接到 logcat，
 * 统一用 {@value #TAG} 作为 tag。</p>
 *
 * <p>由 {@link AndroidLauncher#onCreate} 调用 {@link #install()} 安装，幂等。</p>
 */
public final class LogcatLogHandler extends Handler {

	/** logcat 单条消息上限约 4000 字节，超长要分段，否则会被静默截断。 */
	private static final int MAX_CHUNK = 3500;

	private static final String TAG = "beatoraja";

	private static boolean installed;

	private LogcatLogHandler() {
	}

	/** 幂等安装：重复调用无副作用。 */
	public static synchronized void install() {
		if (installed) {
			return;
		}
		installed = true;
		try {
			final Logger root = LogManager.getLogManager().getLogger("");
			root.addHandler(new LogcatLogHandler());
			// 默认 root 是 INFO，但若有人把级别调高就会漏掉；这里显式放开到 INFO。
			final Level level = root.getLevel();
			if (level == null || level.intValue() > Level.INFO.intValue()) {
				root.setLevel(Level.INFO);
			}
		} catch (Throwable e) {
			Log.w(TAG, "安装 java.util.logging -> logcat 桥接失败", e);
		}
	}

	@Override
	public void publish(LogRecord record) {
		if (record == null || !isLoggable(record)) {
			return;
		}
		final StringBuilder sb = new StringBuilder();
		if (record.getLoggerName() != null) {
			sb.append('[').append(record.getLoggerName()).append("] ");
		}
		sb.append(formatMessage(record));
		if (record.getThrown() != null) {
			sb.append('\n').append(Log.getStackTraceString(record.getThrown()));
		}
		final String whole = sb.toString();

		final int level = record.getLevel().intValue();
		for (int start = 0; start < whole.length(); start += MAX_CHUNK) {
			final String part = whole.substring(start, Math.min(whole.length(), start + MAX_CHUNK));
			if (level >= Level.SEVERE.intValue()) {
				Log.e(TAG, part);
			} else if (level >= Level.WARNING.intValue()) {
				Log.w(TAG, part);
			} else if (level >= Level.INFO.intValue()) {
				Log.i(TAG, part);
			} else {
				Log.d(TAG, part);
			}
		}
	}

	/**
	 * 自己拼消息文本，不用 {@code Formatter} —— Android 上的 {@code Handler#formatMessage} 不存在
	 * （那是 {@code Formatter} 的方法），直接调会编译不过。
	 */
	private static String formatMessage(LogRecord record) {
		final String message = record.getMessage();
		final Object[] params = record.getParameters();
		if (message == null) {
			return "";
		}
		if (params == null || params.length == 0) {
			return message;
		}
		try {
			return java.text.MessageFormat.format(message, params);
		} catch (Throwable e) {
			return message;
		}
	}

	@Override
	public void flush() {
		// logcat 无需 flush
	}

	@Override
	public void close() {
		// 无资源可释放
	}
}
