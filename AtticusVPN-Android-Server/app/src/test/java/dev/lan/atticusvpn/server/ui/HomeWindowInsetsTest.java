/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;
import android.app.Activity;
import android.app.Application;
import android.graphics.Insets;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public final class HomeWindowInsetsTest {
    @Test public void barsCutoutAndKeyboardApplyOnceAndReturnToOriginalSafeArea() {
        var controller=Robolectric.buildActivity(Activity.class).setup();
        Activity activity=controller.get();FrameLayout root=new FrameLayout(activity);
        activity.setContentView(root);HomeWindowInsets.install(activity,root,18,22,16);
        WindowInsets bars=new WindowInsets.Builder().setInsets(WindowInsets.Type.systemBars(),Insets.of(0,24,0,30))
                .setInsets(WindowInsets.Type.displayCutout(),Insets.of(12,32,8,0)).build();
        root.dispatchApplyWindowInsets(bars);assertEquals(30,root.getPaddingLeft());assertEquals(54,root.getPaddingTop());assertEquals(26,root.getPaddingRight());assertEquals(46,root.getPaddingBottom());
        WindowInsets keyboard=new WindowInsets.Builder(bars).setInsets(WindowInsets.Type.ime(),Insets.of(0,0,0,240)).build();
        root.dispatchApplyWindowInsets(keyboard);root.dispatchApplyWindowInsets(keyboard);assertEquals(256,root.getPaddingBottom());
        root.dispatchApplyWindowInsets(bars);assertEquals(46,root.getPaddingBottom());controller.pause().stop().destroy();
    }
    @Test @Config(sdk=24) @SuppressWarnings("deprecation") public void legacyDecorKeepsItsExistingPadding() {
        var controller=Robolectric.buildActivity(Activity.class).setup();Activity activity=controller.get();
        FrameLayout root=new FrameLayout(activity);root.setPadding(18,22,18,16);activity.setContentView(root);
        HomeWindowInsets.install(activity,root,18,22,16);
        assertEquals(22,root.getPaddingTop());assertEquals(16,root.getPaddingBottom());controller.pause().stop().destroy();
    }
}
