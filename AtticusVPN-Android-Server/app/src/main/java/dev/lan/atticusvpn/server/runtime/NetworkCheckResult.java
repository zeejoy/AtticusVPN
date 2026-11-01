/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.runtime;

/** 服务器出口检查的固定提示。不根据服务器自身出口推断远端客户端是否能上网。 */
public enum NetworkCheckResult {
    IDLE("尚未检测；启动服务器后自动检测"),
    CHECKING("正在检测手机出口（IPv4 HTTPS / 上游 DNS）…"),
    PASSED("手机出口检测通过（IPv4 HTTPS / 上游 DNS）。客户端上网请看客户端检测结果。"),
    PASSED_DNS_DISABLED("手机 IPv4 HTTPS 出口可用；内置 DNS 已关闭。客户端需配置可用 DNS。"),
    PASSED_DNS_WARNING("手机出口检测通过（四个官网中至少一个 IPv4 HTTPS 可用）；上游 DNS 未确认，请检查 DNS 设置。"),
    HTTPS_FAILED("未确认手机 IPv4 HTTPS 出口。请检查移动数据 / Wi-Fi，或稍后重测；也可能仅检测站点不可达。"),
    TIMEOUT("检测超时。请检查手机网络后重测；此结果不能确定客户端故障原因。"),
    UNAVAILABLE("服务器尚未运行或物理网络不可用。请先启动服务器并确认公网 IPv6。"),
    CANCELLED("检测已取消");

    private final String message;

    NetworkCheckResult(final String message) { this.message = message; }

    public String message() { return message; }

    /** 网站可访问即确认手机出口，独立 DNS 警告不能使此结果变为网络失败。 */
    public boolean isConnectivityConfirmed() {
        return this == PASSED || this == PASSED_DNS_DISABLED || this == PASSED_DNS_WARNING;
    }

    /** HTTPS 与 DNS 独立判定；关闭 DNS 不会误报它已通过。 */
    public static NetworkCheckResult classify(final boolean https, final boolean dns,
                                              final boolean dnsEnabled) {
        if (!https) return HTTPS_FAILED;
        if (!dnsEnabled) return PASSED_DNS_DISABLED;
        return dns ? PASSED : PASSED_DNS_WARNING;
    }
}
