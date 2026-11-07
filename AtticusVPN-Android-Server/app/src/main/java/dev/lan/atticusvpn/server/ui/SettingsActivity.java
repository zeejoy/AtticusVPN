/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.Button;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import dev.lan.atticusvpn.server.R;

/** 设置目录只负责导航；语言选择和设备信息分别进入二级页面。 */
public final class SettingsActivity extends SettingsPageActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = createPage(R.string.settings);
        option(content, R.string.vpn_settings, R.drawable.ic_settings_vpn).setOnClickListener(view ->
                startActivity(new Intent(this, VpnSettingsActivity.class)));
        option(content, R.string.background_title, R.drawable.ic_settings_background).setOnClickListener(view ->
                startActivity(new Intent(this, BackgroundAccessActivity.class)));
        option(content, R.string.language, R.drawable.ic_settings_language).setOnClickListener(view ->
                startActivity(new Intent(this, LanguageSettingsActivity.class)));
        option(content, R.string.appearance, R.drawable.ic_settings_appearance).setOnClickListener(view ->
                startActivity(new Intent(this, AppearanceSettingsActivity.class)));
        option(content, R.string.device_capabilities, R.drawable.ic_settings_device).setOnClickListener(view ->
                startActivity(new Intent(this, DeviceCapabilitiesActivity.class)));
        option(content, R.string.runtime_log_title, R.drawable.ic_settings_log).setOnClickListener(view ->
                startActivity(new Intent(this, RuntimeLogActivity.class)));
    }

    private Button option(LinearLayout content, int label, int icon) {
        Button button = addButton(content, label);
        Drawable drawable = getDrawable(icon).mutate();
        drawable.setTintList(button.getTextColors());
        drawable.setBounds(0, 0, dp(22), dp(22));
        // Compound icons are decorative; the existing label remains the sole accessibility name.
        button.setCompoundDrawablesRelative(drawable, null, null, null);
        button.setCompoundDrawablePadding(dp(14));
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setPaddingRelative(dp(18), dp(14), dp(18), dp(14));
        button.setMinimumHeight(dp(56));
        return button;
    }
}
