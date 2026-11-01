/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ImageView;
import dev.lan.atticusvpn.server.R;

/** 固定底部的对称椭圆形图标切换栏；仅导航，不包含业务操作。 */
final class HomePageNavigation extends LinearLayout {
    interface Listener { void selected(int index); }
    private final ImageView[] icons = new ImageView[3];
    private Listener listener;

    HomePageNavigation(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setLayoutDirection(LAYOUT_DIRECTION_LTR);
        setGravity(Gravity.CENTER);
        setPadding(dp(4), dp(4), dp(4), dp(4));
        final GradientDrawable outer = rounded(context.getColor(R.color.atticus_card), dp(28));
        outer.setStroke(dp(1), context.getColor(R.color.navigation_border));
        setBackground(outer);
        setElevation(dp(3));
        final int[] descriptions = {R.string.page_information, R.string.page_peers, R.string.page_connection};
        final int[] artwork = {R.drawable.atticus_page_server,
                R.drawable.atticus_page_peers, R.drawable.atticus_page_connection};
        for (int index = 0; index < icons.length; index++) {
            final int page = index;
            final ImageView icon = new ImageView(context);
            icon.setImageDrawable(UiArtwork.load(context, artwork[index]));
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setPadding(dp(12), dp(9), dp(12), dp(9));
            icon.setClickable(true);
            icon.setFocusable(true);
            icon.setMinimumHeight(dp(48));
            icon.setContentDescription(context.getString(descriptions[index]));
            icon.setOnClickListener(view -> { if (listener != null) listener.selected(page); });
            icons[index] = icon;
            addView(icon, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        select(0);
    }

    void setListener(Listener value) { listener = value; }

    void select(int selected) {
        for (int index = 0; index < icons.length; index++) {
            final boolean active = index == selected;
            final ImageView icon = icons[index];
            icon.setSelected(active);
            icon.setColorFilter(getContext().getColor(active
                    ? R.color.navigation_selected_text : R.color.black), PorterDuff.Mode.SRC_IN);
            icon.setBackground(active ? rounded(getContext().getColor(R.color.navigation_selected), dp(24)) : null);
        }
    }

    private static GradientDrawable rounded(int color, int radius) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
