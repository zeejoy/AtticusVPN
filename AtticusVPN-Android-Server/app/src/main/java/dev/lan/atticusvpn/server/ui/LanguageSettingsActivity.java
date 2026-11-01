/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;
import dev.lan.atticusvpn.server.R;

/** 语言二级页面只保存显示语言；复用原有语言选择，不重启 WireGuard。 */
public final class LanguageSettingsActivity extends SettingsPageActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = createPage(R.string.language);
        RadioGroup languages = new RadioGroup(this);
        // 单选状态取自保存的语言，避免 Activity 重建时恢复过期选项。
        languages.setSaveEnabled(false);
        RadioButton chinese = new RadioButton(this);
        chinese.setId(View.generateViewId());
        chinese.setSaveEnabled(false);
        chinese.setText(R.string.language_chinese);
        RadioButton traditional = new RadioButton(this);
        traditional.setId(View.generateViewId());
        traditional.setSaveEnabled(false);
        traditional.setText(R.string.language_traditional_chinese);
        RadioButton english = new RadioButton(this);
        english.setId(View.generateViewId());
        english.setSaveEnabled(false);
        english.setText(R.string.language_english);
        languages.addView(chinese);
        languages.addView(traditional);
        languages.addView(english);
        String currentLanguage = AppLanguage.current(this);
        languages.check(AppLanguage.CHINESE.equals(currentLanguage) ? chinese.getId()
                : AppLanguage.TRADITIONAL_CHINESE.equals(currentLanguage) ? traditional.getId()
                : english.getId());
        languages.setOnCheckedChangeListener((group, selectedId) -> {
            if (selectedId != chinese.getId() && selectedId != traditional.getId()
                    && selectedId != english.getId()) return;
            String tag = selectedId == chinese.getId() ? AppLanguage.CHINESE
                    : selectedId == traditional.getId() ? AppLanguage.TRADITIONAL_CHINESE
                    : AppLanguage.ENGLISH;
            if (!AppLanguage.current(this).equals(tag)) {
                if (AppLanguage.select(this, tag)) recreate();
                else {
                    String savedLanguage = AppLanguage.current(this);
                    languages.check(AppLanguage.CHINESE.equals(savedLanguage) ? chinese.getId()
                            : AppLanguage.TRADITIONAL_CHINESE.equals(savedLanguage)
                            ? traditional.getId() : english.getId());
                    Toast.makeText(this, R.string.language_save_failed, Toast.LENGTH_LONG).show();
                }
            }
        });
        content.addView(languages);
        addText(content, getString(R.string.language_help), 14);
    }
}
