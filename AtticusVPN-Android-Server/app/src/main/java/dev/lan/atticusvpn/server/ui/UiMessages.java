/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

import android.content.Context;
import dev.lan.atticusvpn.server.R;

/** 仅将稳定状态转换为显示资源，不改变检测结果或底层异常处理。 */
final class UiMessages {
    private UiMessages() {}

    static String root(Context context, dev.lan.atticusvpn.server.root.RootState value) {
        switch (value) {
            case ROOT_GRANTED: return context.getString(R.string.root_granted);
            case ROOT_PERMISSION_DENIED: return context.getString(R.string.root_permission_denied);
            case ROOT_NOT_FOUND: return context.getString(R.string.root_not_found);
            case ROOT_ERROR: return context.getString(R.string.root_error);
            case ROOT_UNKNOWN: return context.getString(R.string.root_unknown);
            default: return context.getString(R.string.unknown);
        }
    }

    static String evidence(Context context, dev.lan.atticusvpn.server.capability.KernelCapability.Evidence value) {
        switch (value) {
            case NOT_CHECKED: return context.getString(R.string.evidence_not_checked);
            case READ_CHECK_PASSED: return context.getString(R.string.evidence_read_check_passed);
            case FAILED_OR_UNAVAILABLE: return context.getString(R.string.evidence_failed_or_unavailable);
            default: return context.getString(R.string.unknown);
        }
    }

    static String port(Context context, dev.lan.atticusvpn.server.capability.IpsecCapabilityChecker.PortObservation value) {
        switch (value) {
            case NOT_CHECKED: return context.getString(R.string.port_not_checked);
            case UNKNOWN: return context.getString(R.string.port_unknown);
            case NO_CONFLICT_OBSERVED: return context.getString(R.string.port_no_conflict_observed);
            case BUSY_500: return context.getString(R.string.port_busy_500);
            case BUSY_4500: return context.getString(R.string.port_busy_4500);
            case BUSY_500_4500: return context.getString(R.string.port_busy_500_4500);
            default: return context.getString(R.string.unknown);
        }
    }

    static String forwarding(Context context, dev.lan.atticusvpn.server.capability.IpsecCapabilityChecker.ForwardingObservation value) {
        switch (value) {
            case NOT_CHECKED: return context.getString(R.string.forwarding_not_checked);
            case UNKNOWN: return context.getString(R.string.forwarding_unknown);
            case ENABLED: return context.getString(R.string.forwarding_enabled);
            case DISABLED: return context.getString(R.string.forwarding_disabled);
            default: return context.getString(R.string.unknown);
        }
    }

    static String ikev2(Context context, dev.lan.atticusvpn.server.ikev2.Ikev2State value) {
        switch (value) {
            case IKEV2_AVAILABLE: return context.getString(R.string.ikev2_available);
            case IKEV2_ROOT_REQUIRED: return context.getString(R.string.ikev2_root_required);
            case IKEV2_ROOT_DENIED: return context.getString(R.string.ikev2_root_denied);
            case IKEV2_KERNEL_UNSUPPORTED: return context.getString(R.string.ikev2_kernel_unsupported);
            case IKEV2_XFRM_UNAVAILABLE: return context.getString(R.string.ikev2_xfrm_unavailable);
            case IKEV2_DATAPLANE_UNAVAILABLE: return context.getString(R.string.ikev2_dataplane_unavailable);
            case IKEV2_PORT_CONFLICT: return context.getString(R.string.ikev2_port_conflict);
            case IKEV2_ERROR: return context.getString(R.string.ikev2_error);
            default: return context.getString(R.string.unknown);
        }
    }

    static String networkCheck(Context context, dev.lan.atticusvpn.server.runtime.NetworkCheckResult value) {
        switch (value) {
            case IDLE: return context.getString(R.string.check_idle);
            case CHECKING: return context.getString(R.string.check_checking);
            case PASSED: return context.getString(R.string.check_passed);
            case PASSED_DNS_DISABLED: return context.getString(R.string.check_passed_dns_disabled);
            case PASSED_DNS_WARNING: return context.getString(R.string.check_dns_failed);
            case HTTPS_FAILED: return context.getString(R.string.check_https_failed);
            case TIMEOUT: return context.getString(R.string.check_timeout);
            case UNAVAILABLE: return context.getString(R.string.check_unavailable);
            case CANCELLED: return context.getString(R.string.check_cancelled);
            default: return context.getString(R.string.unknown);
        }
    }

    static String waiting(Context context, String code) {
        switch (code) {
            case "NETWORK_NOT_VALIDATED": return context.getString(R.string.waiting_unvalidated);
            case "GLOBAL_IPV6_UNAVAILABLE": return context.getString(R.string.waiting_no_ipv6);
            case "DEFAULT_NETWORK_IS_VPN": return context.getString(R.string.waiting_other_vpn);
            default: return context.getString(R.string.waiting_attributes);
        }
    }

    // 兼容已有只读快照。只能匹配内部固定文案，不翻译或回显任意异常文本。
    static String observation(Context context, String value) {
        switch (value) {
            case "未知（读取失败；未改变 SELinux）": return context.getString(R.string.selinux_failed);
            case "未知（只读信息不可用）": return context.getString(R.string.selinux_unavailable);
            case "未观察到本机 IPv6": return context.getString(R.string.ipv6_none);
            case "观察到全局 IPv6 地址候选；公网入站与 IKEv2 可达性未验证": return context.getString(R.string.ipv6_candidate);
            case "仅观察到非全局 IPv6；未证明公网可达": return context.getString(R.string.ipv6_non_global);
            case "IPv6 接口读取失败": return context.getString(R.string.ipv6_failed);
            case "Enforcing": case "Permissive": case "Disabled": return value;
            default: return context.getString(R.string.unknown);
        }
    }
}
