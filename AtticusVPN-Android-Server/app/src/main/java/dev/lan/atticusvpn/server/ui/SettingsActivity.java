/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import dev.lan.atticusvpn.server.R;

/** 设置目录只负责导航；语言选择和设备信息分别进入二级页面。 */
public final class SettingsActivity extends SettingsPageActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = createPage(R.string.settings);
        addButton(content, R.string.language).setOnClickListener(view ->
                startActivity(new Intent(this, LanguageSettingsActivity.class)));
        addButton(content, R.string.appearance).setOnClickListener(view ->
                startActivity(new Intent(this, AppearanceSettingsActivity.class)));
        addButton(content, R.string.device_capabilities).setOnClickListener(view ->
                startActivity(new Intent(this, DeviceCapabilitiesActivity.class)));
    }
}
