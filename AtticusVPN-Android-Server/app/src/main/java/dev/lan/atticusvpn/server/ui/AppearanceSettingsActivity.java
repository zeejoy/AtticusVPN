/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;
import dev.lan.atticusvpn.server.R;

/** 只保存并应用显示模式；不发送服务器启停、重载、Root 或 DDNS 指令。 */
public final class AppearanceSettingsActivity extends SettingsPageActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        final LinearLayout content = createPage(R.string.appearance);
        final RadioGroup modes = new RadioGroup(this);
        modes.setSaveEnabled(false);
        final RadioButton dark = option(R.string.appearance_dark);
        final RadioButton light = option(R.string.appearance_light);
        final RadioButton system = option(R.string.appearance_system);
        modes.addView(dark);
        modes.addView(light);
        modes.addView(system);
        modes.check(selectedId(AppAppearance.current(this), dark, light, system));
        modes.setOnCheckedChangeListener((group, id) -> {
            if (id != dark.getId() && id != light.getId() && id != system.getId()) return;
            final String mode = id == dark.getId() ? AppAppearance.DARK
                    : id == light.getId() ? AppAppearance.LIGHT : AppAppearance.SYSTEM;
            if (mode.equals(AppAppearance.current(this))) return;
            if (AppAppearance.select(this, mode)) recreate();
            else {
                modes.check(selectedId(AppAppearance.current(this), dark, light, system));
                Toast.makeText(this, R.string.appearance_save_failed, Toast.LENGTH_LONG).show();
            }
        });
        content.addView(modes);
        addText(content, getString(R.string.appearance_help), 14);
    }

    private RadioButton option(int label) {
        final RadioButton button = new RadioButton(this);
        button.setId(View.generateViewId());
        button.setSaveEnabled(false);
        button.setText(label);
        button.setMinimumHeight(dp(48));
        return button;
    }

    private static int selectedId(String mode, RadioButton dark, RadioButton light, RadioButton system) {
        return AppAppearance.DARK.equals(mode) ? dark.getId()
                : AppAppearance.LIGHT.equals(mode) ? light.getId() : system.getId();
    }
}
