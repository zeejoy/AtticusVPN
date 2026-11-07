/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.lan.atticusvpn.server.R;

/** User-initiated standard Android permission flow; never starts a VPN or su. */
public final class BackgroundAccessActivity extends SettingsPageActivity {
    private static final int NOTIFICATIONS=13001, BATTERY=13002;
    private TextView status, outcome;
    private Button enable;
    private boolean pending;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);pending=saved!=null&&saved.getBoolean("permissionPending",false);
        LinearLayout page=createPage(R.string.background_title);
        addText(page,getString(R.string.background_explanation),15);
        status=addText(page,"",15);
        enable=addButton(page,R.string.background_one_click);
        enable.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage(R.string.background_consent)
                .setPositiveButton(R.string.background_continue,(dialog,which)->begin())
                .setNegativeButton(android.R.string.cancel,null).show());
        addButton(page,R.string.background_notification_settings).setOnClickListener(v -> {
            Intent intent=Build.VERSION.SDK_INT>=26
                    ?new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName())
                    :detailsIntent();
            openSettings(intent);
        });
        addButton(page,R.string.background_app_settings).setOnClickListener(v -> openSettings(detailsIntent()));
        addText(page,getString(R.string.background_oem_help),14);
        outcome=addText(page,"",14);
    }

    @Override protected void onResume() {super.onResume();refresh();}
    private Intent detailsIntent(){return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()));}
    private void openSettings(Intent intent) {
        try {startActivity(intent);}catch(RuntimeException ignored){outcome.setText(R.string.background_settings_unavailable);}
    }
    private boolean batteryExempt() {
        PowerManager power=getSystemService(PowerManager.class);
        return power!=null&&power.isIgnoringBatteryOptimizations(getPackageName());
    }
    private void refresh() {
        NotificationManager notifications=getSystemService(NotificationManager.class);
        ActivityManager manager=getSystemService(ActivityManager.class);
        boolean restricted=Build.VERSION.SDK_INT>=28&&manager!=null&&manager.isBackgroundRestricted();
        status.setText(getString(R.string.background_status,
                getString(notifications!=null&&notifications.areNotificationsEnabled()?R.string.background_allowed:R.string.background_needs_setup),
                getString(batteryExempt()?R.string.background_allowed:R.string.background_needs_setup),
                getString(restricted?R.string.background_restricted:R.string.background_not_restricted)));
        enable.setEnabled(!pending);
    }
    private void begin() {
        if(pending)return;pending=true;refresh();
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATIONS);
        } else requestBattery();
    }
    // Continuous user-started VPN ingress is the core function: Doze suspends
    // its sockets/wake locks, and FCM/WorkManager cannot proxy WireGuard/IPsec.
    // Direct exemption is only requested after the explicit explanation above.
    // https://developer.android.com/training/monitoring-device-state/doze-standby
    @android.annotation.SuppressLint("BatteryLife")
    private void requestBattery() {
        if(batteryExempt()){finishFlow();return;}
        try {
            startActivityForResult(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:"+getPackageName())),BATTERY);
        }catch(RuntimeException ignored){
            try {startActivityForResult(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),BATTERY);}
            catch(RuntimeException unavailable){finishFlow();outcome.setText(R.string.background_settings_unavailable);}
        }
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==NOTIFICATIONS&&pending)requestBattery();
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==BATTERY)finishFlow();
    }
    private void finishFlow(){pending=false;refresh();outcome.setText(R.string.background_finished);}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putBoolean("permissionPending",pending);}
}
