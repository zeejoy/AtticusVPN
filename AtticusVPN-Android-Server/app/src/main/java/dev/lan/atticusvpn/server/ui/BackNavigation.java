/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

import android.app.Activity;
import android.graphics.PorterDuff;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import dev.lan.atticusvpn.server.R;

/** 原生页面共用的左上角返回箭头，不包含服务控制或配置读取操作。 */
final class BackNavigation {
    private BackNavigation() { }

    static void addTo(Activity activity, LinearLayout content) {
        // 48dp 点击区域包含 24dp 图标；深浅主题分别提供白/黑前景色。
        ImageButton back = new ImageButton(activity);
        back.setImageResource(R.drawable.ic_settings_back);
        back.setScaleType(ImageView.ScaleType.FIT_CENTER);
        back.setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12));
        back.setColorFilter(activity.getColor(R.color.black), PorterDuff.Mode.SRC_IN);
        TypedValue selectable = new TypedValue();
        activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, selectable, true);
        back.setBackgroundResource(selectable.resourceId);
        back.setContentDescription(activity.getString(R.string.back));
        back.setOnClickListener(view -> activity.finish());
        LinearLayout.LayoutParams backLayout = new LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48));
        backLayout.gravity = Gravity.START;
        content.addView(back, backLayout);
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
