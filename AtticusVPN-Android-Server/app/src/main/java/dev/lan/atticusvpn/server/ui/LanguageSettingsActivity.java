/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;
import android.view.Gravity;
import java.util.LinkedHashMap;
import java.util.Map;
import dev.lan.atticusvpn.server.R;

/** 语言二级页面只保存显示语言；复用原有语言选择，不重启 WireGuard。 */
public final class LanguageSettingsActivity extends SettingsPageActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = createPage(R.string.language);
        RadioGroup languages = new RadioGroup(this);
        // 单选状态取自保存的语言，避免 Activity 重建时恢复过期选项。
        languages.setSaveEnabled(false);
        Map<Integer, String> tags = new LinkedHashMap<>();
        for (AppLanguage.Option option : AppLanguage.options()) {
            RadioButton button = new RadioButton(this);
            button.setId(View.generateViewId());
            button.setSaveEnabled(false);
            button.setText(option.label());
            button.setTag(option.tag());
            button.setTextDirection(View.TEXT_DIRECTION_LOCALE);
            button.setTextLocale(java.util.Locale.forLanguageTag(option.tag()));
            button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            button.setMinimumHeight(dp(48));
            button.setPaddingRelative(dp(8), dp(8), dp(8), dp(8));
            tags.put(button.getId(), option.tag());
            languages.addView(button);
        }
        restoreSelection(languages, tags);
        languages.setOnCheckedChangeListener((group, selectedId) -> {
            String tag = tags.get(selectedId);
            if (tag == null) return;
            if (!AppLanguage.current(this).equals(tag)) {
                if (AppLanguage.select(this, tag)) recreate();
                else {
                    restoreSelection(languages, tags);
                    Toast.makeText(this, R.string.language_save_failed, Toast.LENGTH_LONG).show();
                }
            }
        });
        content.addView(languages);
        addText(content, getString(R.string.language_help), 14);
    }

    private void restoreSelection(RadioGroup group, Map<Integer, String> tags) {
        String saved = AppLanguage.current(this);
        for (Map.Entry<Integer, String> entry : tags.entrySet())
            if (entry.getValue().equals(saved)) { group.check(entry.getKey()); return; }
    }
}
