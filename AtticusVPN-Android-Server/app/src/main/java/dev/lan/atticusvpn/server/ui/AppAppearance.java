/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

import android.content.Context;
import android.content.res.Configuration;

/** 显示偏好与语言、VPN 配置分离；只包装应用资源，不改变手机系统深浅色设置。 */
final class AppAppearance {
    static final String DARK = "dark";
    static final String LIGHT = "light";
    static final String SYSTEM = "system";
    private static final String KEY = "appearance";

    private AppAppearance() {}

    static String current(Context context) {
        final String saved = AppLanguage.preferences(context).getString(KEY, SYSTEM);
        return DARK.equals(saved) ? DARK : LIGHT.equals(saved) ? LIGHT : SYSTEM;
    }

    static boolean select(Context context, String mode) {
        if (!DARK.equals(mode) && !LIGHT.equals(mode) && !SYSTEM.equals(mode)) return false;
        return AppLanguage.preferences(context).edit().putString(KEY, mode).commit();
    }

    static Context wrap(Context base) {
        final Configuration configuration = new Configuration(base.getResources().getConfiguration());
        final String mode = current(base);
        final int night = DARK.equals(mode) ? Configuration.UI_MODE_NIGHT_YES
                : LIGHT.equals(mode) ? Configuration.UI_MODE_NIGHT_NO
                : base.getApplicationContext().getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK;
        configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
        return base.createConfigurationContext(configuration);
    }

    static String signature(Context context) {
        final String mode = current(context);
        return mode + (SYSTEM.equals(mode) ? ":" + (context.getApplicationContext()
                .getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) : "");
    }
}
