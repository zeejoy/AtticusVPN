/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import dev.lan.atticusvpn.server.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public final class BackgroundAccessActivityTest {
    private Button button(View view,String text) {
        if(view instanceof Button b&&text.contentEquals(b.getText()))return b;
        if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){Button b=button(group.getChildAt(i),text);if(b!=null)return b;}
        return null;
    }
    private void begin(BackgroundAccessActivity activity) {
        button(activity.findViewById(android.R.id.content),activity.getString(R.string.background_one_click)).performClick();
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(android.os.Looper.getMainLooper()).idle();
    }
    @Test public void openingPageDoesNotRequestPermissionsOrStartService() {
        try(var controller=Robolectric.buildActivity(BackgroundAccessActivity.class).setup()) {
            var activity=controller.get();
            assertNull(shadowOf(activity).getLastRequestedPermission());
            assertNull(shadowOf(activity).getNextStartedActivity());
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
        }
    }
    @Test public void oneClickRequestsNotificationsThenBatteryEvenIfNotificationDenied() {
        Application app=RuntimeEnvironment.getApplication();shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        try(var controller=Robolectric.buildActivity(BackgroundAccessActivity.class).setup()) {
            var activity=controller.get();begin(activity);
            var request=shadowOf(activity).getLastRequestedPermission();assertNotNull(request);
            assertArrayEquals(new String[]{Manifest.permission.POST_NOTIFICATIONS},request.requestedPermissions);
            Intent permissionIntent=shadowOf(activity).getNextStartedActivity();assertNotNull(permissionIntent);
            assertEquals("android.content.pm.action.REQUEST_PERMISSIONS",permissionIntent.getAction());
            activity.onRequestPermissionsResult(request.requestCode,request.requestedPermissions,new int[]{PackageManager.PERMISSION_DENIED});
            var next=shadowOf(activity).getNextStartedActivityForResult();assertNotNull(next);
            assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,next.intent.getAction());
            assertEquals("package:"+activity.getPackageName(),next.intent.getDataString());
            activity.onActivityResult(next.requestCode,0,null);
            assertTrue(button(activity.findViewById(android.R.id.content),activity.getString(R.string.background_one_click)).isEnabled());
            assertNull(shadowOf(app).getNextStartedService());
        }
    }
    @Test public void alreadyGrantedAccessDoesNotOpenAnotherSystemDialog() {
        Application app=RuntimeEnvironment.getApplication();shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        shadowOf(app.getSystemService(PowerManager.class)).setIgnoringBatteryOptimizations(app.getPackageName(),true);
        try(var controller=Robolectric.buildActivity(BackgroundAccessActivity.class).setup()) {
            var activity=controller.get();begin(activity);
            assertNull(shadowOf(activity).getLastRequestedPermission());assertNull(shadowOf(activity).getNextStartedActivity());
            assertTrue(button(activity.findViewById(android.R.id.content),activity.getString(R.string.background_one_click)).isEnabled());
            assertNull(shadowOf(app).getNextStartedService());
        }
    }
    @Test @Config(sdk=24) public void olderAndroidUsesBatteryFlowWithoutNotificationRuntimePermission() {
        try(var controller=Robolectric.buildActivity(BackgroundAccessActivity.class).setup()) {
            var activity=controller.get();begin(activity);
            assertNull(shadowOf(activity).getLastRequestedPermission());
            Intent next=shadowOf(activity).getNextStartedActivity();assertNotNull(next);
            assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,next.getAction());
        }
    }
}
