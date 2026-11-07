/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Build;
import android.view.View;

/** Explicit edge-to-edge safe area on modern Android; legacy decor keeps its own insets. */
final class HomeWindowInsets {
    private HomeWindowInsets() { }
    static void install(Activity activity,View root,int side,int top,int bottom) {
        if(Build.VERSION.SDK_INT>=30)Modern.install(activity,root,side,top,bottom);
    }
    @androidx.annotation.RequiresApi(30)
    private static final class Modern {
        @SuppressWarnings("deprecation")
        static void install(Activity activity,View root,int side,int top,int bottom) {
            android.view.Window window=activity.getWindow();
            window.setDecorFitsSystemWindows(false);
            window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
            window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
            window.setNavigationBarContrastEnforced(false);
            android.view.WindowInsetsController controller=window.getInsetsController();
            if(controller!=null) {
                int mask=android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        |android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                boolean dark=(root.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
                controller.setSystemBarsAppearance(dark?0:mask,mask);
            }
            root.setOnApplyWindowInsetsListener((view,insets)->{
                android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());
                android.graphics.Insets keyboard=insets.getInsets(android.view.WindowInsets.Type.ime());
                view.setPadding(side+bars.left,top+bars.top,side+bars.right,bottom+Math.max(bars.bottom,keyboard.bottom));
                return insets;
            });
            root.requestApplyInsets();
        }
    }
}
