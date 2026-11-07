/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;
import java.util.Locale;
import java.util.List;
import dev.lan.atticusvpn.server.R;

/** 语言设置独立于 VPN 配置、密钥及服务生命周期，不会触发引擎重载。 */
public final class AppLanguage {
    private static final String PREFS = "atticus.display.preferences";
    private static final String KEY = "language";
    public static final String CHINESE = "zh-CN";
    public static final String TRADITIONAL_CHINESE = "zh-TW";
    public static final String ENGLISH = "en";
    public record Option(String tag, int label) { }
    private static final List<Option> OPTIONS = List.of(
            new Option(CHINESE, R.string.language_chinese),
            new Option(TRADITIONAL_CHINESE, R.string.language_traditional_chinese),
            new Option(ENGLISH, R.string.language_english),
            new Option("es", R.string.language_spanish),
            new Option("fr", R.string.language_french),
            new Option("de", R.string.language_german),
            new Option("pt-BR", R.string.language_portuguese),
            new Option("ru", R.string.language_russian),
            new Option("ar", R.string.language_arabic),
            new Option("hi", R.string.language_hindi),
            new Option("ja", R.string.language_japanese),
            new Option("ko", R.string.language_korean),
            new Option("id", R.string.language_indonesian));

    public static List<Option> options() { return OPTIONS; }

    private AppLanguage() {}

    /** 保存稳定语言码；保留旧码与中文脚本/地区优先级。 */
    static String normalize(String tag) {
        if (tag == null || tag.trim().isEmpty()) return ENGLISH;
        Locale locale = Locale.forLanguageTag(tag.replace('_', '-'));
        String language = locale.getLanguage();
        if ("in".equals(language)) language = "id"; // Older Android's Indonesian alias.
        if (!"zh".equals(language)) {
            for (Option option : OPTIONS) {
                if (option.tag().equals(language) || ("pt".equals(language) && option.tag().equals("pt-BR")))
                    return option.tag();
            }
            return ENGLISH;
        }
        if ("Hant".equals(locale.getScript())) return TRADITIONAL_CHINESE;
        if ("Hans".equals(locale.getScript())) return CHINESE;
        String region = locale.getCountry();
        return "TW".equals(region) || "HK".equals(region) || "MO".equals(region)
                ? TRADITIONAL_CHINESE : CHINESE;
    }

    public static String current(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            final LocaleManager manager = context.getSystemService(LocaleManager.class);
            if (manager != null && !manager.getApplicationLocales().isEmpty()) {
                // getLanguage() 只会返回 zh，丢失系统语言选择中的 Hant / TW 信息。
                return normalize(manager.getApplicationLocales().get(0).toLanguageTag());
            }
        }
        return normalize(preferences(context).getString(KEY, ENGLISH));
    }

    public static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean select(Context context, String tag) {
        final String selected = normalize(tag);
        // 先确认磁盘保存成功，重启后的选择才与当前界面一致；内容仅是语言码。
        if (!preferences(context).edit().putString(KEY, selected).commit()) return false;
        if (Build.VERSION.SDK_INT >= 33) {
            final LocaleManager manager = context.getSystemService(LocaleManager.class);
            if (manager != null) manager.setApplicationLocales(LocaleList.forLanguageTags(selected));
        }
        return true;
    }

    public static Context wrap(Context base) {
        final Configuration configuration = new Configuration(base.getResources().getConfiguration());
        final Locale selected = Locale.forLanguageTag(current(base));
        configuration.setLocales(new LocaleList(selected));
        configuration.setLayoutDirection(selected);
        return base.createConfigurationContext(configuration);
    }

    /** 服务每次生成通知时使用最新语言，不需要重启正在运行的 WireGuard。 */
    public static String text(Context context, int resource, Object... arguments) {
        return wrap(context).getString(resource, arguments);
    }
}
