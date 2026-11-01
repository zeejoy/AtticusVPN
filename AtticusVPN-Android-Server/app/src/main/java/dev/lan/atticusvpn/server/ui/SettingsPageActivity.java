/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

import android.graphics.Typeface;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import dev.lan.atticusvpn.server.R;

/** 设置页面共用返回、标题和滚动容器，沿用原生控件，不添加 UI 依赖。 */
abstract class SettingsPageActivity extends LocalizedActivity {
    protected final LinearLayout createPage(int titleResource) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        content.setPadding(padding, padding, padding, padding);
        content.setBackgroundColor(getColor(R.color.atticus_surface));
        scroll.addView(content);
        BackNavigation.addTo(this, content);
        TextView title = addText(content, getString(titleResource), 28);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(0, dp(12), 0, dp(16));
        setContentView(scroll);
        return content;
    }

    protected final Button addButton(LinearLayout content, int resource) {
        Button button = new Button(this);
        button.setText(resource);
        button.setAllCaps(false);
        button.setMinimumHeight(dp(48));
        content.addView(button, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return button;
    }

    protected final TextView addText(LinearLayout content, String value, int size) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(getColor(R.color.black));
        text.setPadding(0, dp(8), 0, dp(8));
        content.addView(text, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return text;
    }

    protected final int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
