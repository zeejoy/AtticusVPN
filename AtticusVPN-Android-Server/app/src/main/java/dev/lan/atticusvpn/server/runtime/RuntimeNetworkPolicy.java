/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.runtime;

import java.util.Objects;

import dev.lan.atticusvpn.server.network.NetworkSnapshot;

/** 运行时默认网络安全策略；服务端绝不把 listener 或出站 socket 绑定进另一个 VPN。 */
public final class RuntimeNetworkPolicy {
    public static final String DEFAULT_VPN_REJECTED = "DEFAULT_NETWORK_IS_VPN";
    public static final String NETWORK_NOT_VALIDATED = "NETWORK_NOT_VALIDATED";
    public static final String GLOBAL_IPV6_UNAVAILABLE = "GLOBAL_IPV6_UNAVAILABLE";

    private RuntimeNetworkPolicy() {
    }

    public static boolean isRejectedDefaultType(final NetworkSnapshot.NetworkType type) {
        return Objects.requireNonNull(type, "type") == NetworkSnapshot.NetworkType.VPN;
    }

    /** 系统互联网验证与全局 IPv6 是不同条件，不能把两类故障都报成“没有 IPv6”。 */
    public static String waitingCode(final boolean validated, final boolean hasGlobalIpv6) {
        if (!validated) return NETWORK_NOT_VALIDATED;
        return hasGlobalIpv6 ? "" : GLOBAL_IPV6_UNAVAILABLE;
    }

    public static String waitingHint(final String code) {
        if (NETWORK_NOT_VALIDATED.equals(code)) {
            return "系统尚未验证互联网连接；请检查 Wi-Fi 登录页或移动网络后重试。";
        }
        if (GLOBAL_IPV6_UNAVAILABLE.equals(code)) {
            return "当前网络没有可用的全局 IPv6；请切换支持 IPv6 的网络。";
        }
        if (DEFAULT_VPN_REJECTED.equals(code)) {
            return "服务端默认网络是其他 VPN；请先恢复直接的 Wi-Fi 或移动网络。";
        }
        return "等待网络属性；请检查手机联网状态。";
    }
}
