/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import dev.lan.atticusvpn.server.R;

/** API 24–32 使用资源上下文；API 33+ 同时由系统维护应用语言。 */
abstract class LocalizedActivity extends Activity {
    private String attachedLanguage;
    private String attachedAppearance;

    @Override protected void attachBaseContext(Context base) {
        attachedLanguage = AppLanguage.current(base);
        attachedAppearance = AppAppearance.signature(base);
        super.attachBaseContext(AppAppearance.wrap(AppLanguage.wrap(base)));
    }

    @Override protected void onCreate(Bundle state) {
        setTheme(R.style.Theme_AtticusServer);
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 29) {
            // 应用已自行实现深/浅资源，禁止系统 Force Dark 再次把浅色画面改成深色。
            // 限制只作用于本窗口绘制，不关闭手机的系统/开发者强制深色选项。
            getWindow().getDecorView().setForceDarkAllowed(false);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (!attachedLanguage.equals(AppLanguage.current(this))
                || !attachedAppearance.equals(AppAppearance.signature(this))) {
            // 仅重建显示层；不发送 START/STOP/RELOAD，不销毁应用级 Root 队列。
            recreate();
        }
    }
}
