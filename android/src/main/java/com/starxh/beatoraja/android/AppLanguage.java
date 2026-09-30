package com.starxh.beatoraja.android;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;

import java.util.Locale;

/**
 * UI 语言解析的**唯一入口**。
 *
 * <p>游戏本体（{@link AndroidLauncher}）与设置页
 * （{@code com.starxh.beatoraja.android.compose.SettingsActivity}）都要按同一套规则
 * 决定当前语言，所以判定逻辑只能有一份。以前两边各写半套，于是互相打架 —— 见下面第一条。</p>
 *
 * <p>🔴 <b>不许拿 {@link Locale#getDefault()} 当语言来源。</b>它是<b>进程</b>默认 locale，
 * 而 {@link #apply} 会把它改掉；下一次再读，读到的就是「上次我们自己设的值」。
 * 旧代码正是这样把语言钉死的：{@code SettingsActivity} 从 {@code Locale.getDefault()}
 * 抄一份写进 {@code config_sys.json}，{@link AndroidLauncher} 下次启动又拿它当语言 ——
 * 于是把手机改成繁体中文，App 依然是日语。要拿语言就用 {@link #current(Context)}
 * （本 app 自己的 Configuration，框架已按「应用单独设置的语言 → 系统语言」算好）。</p>
 *
 * <p>🔴 <b>中文必须区分简繁。</b>{@code zh} 单独出现时，Android 的资源匹配按
 * <b>Hans</b>（简体）处理；繁中设备（{@code zh-TW / zh-HK / zh-MO / zh-Hant}）
 * 如果不显式落到 {@link Locale#TRADITIONAL_CHINESE}，就会一路掉到默认资源（英文）。
 * 所以这里简繁判定优先看 script，其次看地区。</p>
 *
 * <p>语言标签与资源目录一一对应：{@code en}→{@code values}、
 * {@code ja}→{@code values-ja}、{@code ko}→{@code values-ko}、
 * {@code zh}→{@code values-zh}（简）、{@code zh-TW}→{@code values-zh-rTW}（繁）、
 * {@code fr}/{@code de}/{@code pt}/{@code es}。不在支持列表里的语言一律回落英文。</p>
 */
public final class AppLanguage {

    private AppLanguage() {
    }

    // 写进 config_sys.json 的语言标签
    public static final String TAG_EN = "en";
    public static final String TAG_JA = "ja";
    public static final String TAG_KO = "ko";
    public static final String TAG_ZH_HANS = "zh";
    public static final String TAG_ZH_HANT = "zh-TW";
    public static final String TAG_FR = "fr";
    public static final String TAG_DE = "de";
    public static final String TAG_PT = "pt";
    public static final String TAG_ES = "es";

    /**
     * 本 app 当前应使用的语言（未经支持列表过滤）。
     *
     * <p>取的是<b>本 app 自己的 Configuration</b>：如果用户在系统里给本应用单独设过语言
     * （Android 13+ 的「应用语言」），框架已经把它算进这个 Configuration；否则它就是系统语言。
     * 拿不到时退回设备语言。</p>
     */
    public static Locale current(Context context) {
        if (context != null && context.getResources() != null) {
            Locale own = firstOf(context.getResources().getConfiguration());
            if (own != null) {
                return own;
            }
        }
        return deviceLocale();
    }

    /** 本 app 当前语言，过滤到受支持的语言（不支持 → 英文）。 */
    public static Locale currentResolved(Context context) {
        return resolve(current(context));
    }

    /** 本 app 当前语言对应的 {@code values-*} 标签（写 config_sys.json 用）。 */
    public static String currentTag(Context context) {
        return tagOf(currentResolved(context));
    }

    /**
     * 设备语言。只在 {@link #current(Context)} 拿不到东西时兜底。
     *
     * <p>用 {@link Resources#getSystem()} 而不是 {@link Locale#getDefault()}：
     * 后者会被 {@link #apply} 改掉，前者始终反映设备设置。</p>
     */
    public static Locale deviceLocale() {
        Locale locale = firstOf(Resources.getSystem().getConfiguration());
        return locale != null ? locale : Locale.ENGLISH;
    }

    /**
     * 原始语言 → 受支持的语言。**这个方法是幂等的**
     * （{@code resolve(resolve(x)) == resolve(x)}），所以重复调用安全。
     */
    public static Locale resolve(Locale locale) {
        if (locale == null) {
            return Locale.ENGLISH;
        }
        String lang = locale.getLanguage();
        if (lang == null) {
            return Locale.ENGLISH;
        }
        switch (lang.toLowerCase(Locale.ROOT)) {
            case "ja":
            case "jp": // 旧代码容忍过的别名
                return Locale.JAPANESE;
            case "ko":
                return Locale.KOREAN;
            case "zh":
                return resolveChinese(locale);
            case "fr":
                return Locale.FRENCH;
            case "de":
                return Locale.GERMAN;
            case "pt":
                return new Locale("pt");
            case "es":
                return new Locale("es");
            default:
                // 不支持的语言不要把它设进进程默认 locale：那样会让 String.format /
                // toLowerCase 之类跟着变（例如土耳其语的 I 问题），反而更危险。
                return Locale.ENGLISH;
        }
    }

    /**
     * 简繁判定：先看 script（{@code zh-Hant} / {@code zh-Hans}），
     * 没有 script 再看地区（{@code TW} / {@code HK} / {@code MO} → 繁）。
     */
    private static Locale resolveChinese(Locale locale) {
        String script = locale.getScript();
        if (script != null && !script.isEmpty()) {
            return "Hant".equalsIgnoreCase(script)
                    ? Locale.TRADITIONAL_CHINESE
                    : Locale.SIMPLIFIED_CHINESE;
        }
        String country = locale.getCountry();
        if (country != null) {
            String c = country.toUpperCase(Locale.ROOT);
            if (c.equals("TW") || c.equals("HK") || c.equals("MO")) {
                return Locale.TRADITIONAL_CHINESE;
            }
        }
        // zh / zh-CN / zh-SG / zh-MY / 没写地区的，一律当简体
        return Locale.SIMPLIFIED_CHINESE;
    }

    /** 受支持语言 → {@code values-*} 标签。 */
    public static String tagOf(Locale locale) {
        Locale t = resolve(locale);
        if (Locale.JAPANESE.equals(t)) {
            return TAG_JA;
        }
        if (Locale.KOREAN.equals(t)) {
            return TAG_KO;
        }
        if (Locale.TRADITIONAL_CHINESE.equals(t)) {
            return TAG_ZH_HANT;
        }
        if (Locale.SIMPLIFIED_CHINESE.equals(t)) {
            return TAG_ZH_HANS;
        }
        if (Locale.FRENCH.equals(t)) {
            return TAG_FR;
        }
        if (Locale.GERMAN.equals(t)) {
            return TAG_DE;
        }
        String lang = t.getLanguage() == null ? "" : t.getLanguage();
        if (lang.equals("pt")) {
            return TAG_PT;
        }
        if (lang.equals("es")) {
            return TAG_ES;
        }
        return TAG_EN;
    }

    /**
     * 应用语言：同步进程默认 locale，并把 locale 写进该 Context 的 Configuration。
     *
     * <p>{@code Resources.updateConfiguration} 虽然被标记为 deprecated，
     * 但它是本项目一直以来的做法，且对「同一个进程内两个 Activity 保持一致」有效——
     * 不动它，避免引入新的行为差异。</p>
     */
    public static void apply(Context context, Locale locale) {
        Locale target = resolve(locale);
        Locale.setDefault(target);
        if (context == null) {
            return;
        }
        Resources res = context.getResources();
        Configuration config = new Configuration(res.getConfiguration());
        config.setLocale(target);
        res.updateConfiguration(config, res.getDisplayMetrics());
    }

    /** 取 Configuration 里的首选语言（API 24 之前是单数形式）。 */
    private static Locale firstOf(Configuration config) {
        if (config == null) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (config.getLocales() != null && config.getLocales().size() > 0) {
                return config.getLocales().get(0);
            }
            return null;
        }
        return config.locale;
    }
}
