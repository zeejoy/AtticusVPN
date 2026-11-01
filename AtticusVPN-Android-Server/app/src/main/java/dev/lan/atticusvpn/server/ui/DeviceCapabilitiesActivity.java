/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.lan.atticusvpn.server.AtticusServerApplication;
import dev.lan.atticusvpn.server.R;
import dev.lan.atticusvpn.server.capability.DeviceCapabilitySnapshot;
import dev.lan.atticusvpn.server.ikev2.Ikev2State;
import dev.lan.atticusvpn.server.root.RootState;
import dev.lan.atticusvpn.server.runtime.DeviceCheckController;

/**
 * 原主页设备能力面板迁移到设置的二级页面，继续订阅进程内唯一的只读检查队列。
 * 首次进入仅查基本信息；Root 检查仍需按钮与确认，不因进入、返回或语言重建自动授权。
 */
public final class DeviceCapabilitiesActivity extends SettingsPageActivity
        implements DeviceCheckController.Listener {
    private TextView deviceView;
    private Button rootCheckButton;
    private boolean activityDestroyed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = createPage(R.string.device_section);
        deviceView = addText(content, getString(R.string.reading_device), 13);
        rootCheckButton = addButton(content, R.string.check_root);
        rootCheckButton.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle(R.string.root_dialog_title)
                .setMessage(R.string.root_dialog_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.start_read_check,
                        (dialog, which) -> deviceChecks().requestRootAndInspect())
                .show());
        addText(content, getString(R.string.ikev2_preview), 13);
        deviceChecks().addListener(this);
        deviceChecks().inspectOnceWithoutPrompt();
    }

    @Override protected void onDestroy() {
        activityDestroyed = true;
        deviceChecks().removeListener(this);
        super.onDestroy();
    }

    private DeviceCheckController deviceChecks() {
        return ((AtticusServerApplication) getApplication()).deviceChecks();
    }

    @Override public void onDeviceCheck(DeviceCheckController.State state) {
        if (activityDestroyed || isFinishing()) return;
        rootCheckButton.setEnabled(!state.busy());
        if (state.busy()) {
            deviceView.setText(R.string.device_check_busy);
            return;
        }
        if (state.failed() || state.snapshot() == null) {
            deviceView.setText(R.string.device_check_failed);
            return;
        }
        DeviceCapabilitySnapshot snapshot = state.snapshot();
        rootCheckButton.setText(snapshot.rootState() == RootState.ROOT_PERMISSION_DENIED
                ? R.string.request_root : R.string.check_root);
        deviceView.setText(getString(R.string.device_check_result,
                UiMessages.root(this, snapshot.rootState()), getString(R.string.root_provider_unknown),
                snapshot.androidVersion(), snapshot.kernelVersion(), snapshot.abis(),
                UiMessages.observation(this, snapshot.selinux()),
                UiMessages.observation(this, snapshot.ipv6Status()),
                UiMessages.evidence(this, snapshot.ipsec().kernel().xfrmStat()),
                UiMessages.evidence(this, snapshot.ipsec().kernel().xfrmState()),
                UiMessages.evidence(this, snapshot.ipsec().kernel().xfrmPolicy()),
                UiMessages.port(this, snapshot.ipsec().ports()),
                UiMessages.forwarding(this, snapshot.ipsec().ipv4Forwarding()),
                UiMessages.forwarding(this, snapshot.ipsec().ipv6Forwarding()),
                UiMessages.ikev2(this, Ikev2State.fromReadOnlySnapshot(snapshot))));
    }
}
