/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.ddns;

import dev.lan.atticusvpn.server.network.GlobalIpv6Selector;

import java.net.IDN;
import java.net.Inet6Address;
import java.util.Locale;
import java.util.Objects;

/**
 * 一次 DDNS 更新所需的非敏感数据。
 *
 * <p>认证凭据属于 Provider 配置，不进入本模型，因而模型的 {@link #toString()} 可以安全用于
 * 调试。构造时会规范化主机名，并拒绝 ULA、link-local 等不能作为公共 Endpoint 的地址。</p>
 */
public final class DdnsUpdateRequest {

    /** 更新是由地址事件触发，还是由低频一致性任务触发。 */
    public enum Trigger {
        /** 默认网络或可发布地址发生变化。 */
        EVENT,
        /** 地址未必变化，但定期与服务商状态重新对齐。 */
        CONSISTENCY
    }

    private final String hostname;
    private final Inet6Address address;
    private final Trigger trigger;

    /**
     * 创建并验证更新请求。
     *
     * @param hostname 要更新的完整域名
     * @param address 健康的公网 IPv6 GUA
     * @param trigger 触发原因
     */
    public DdnsUpdateRequest(String hostname, Inet6Address address, Trigger trigger) {
        this.hostname = normalizeHostname(hostname);
        this.address = Objects.requireNonNull(address, "address");
        this.trigger = Objects.requireNonNull(trigger, "trigger");
        if (GlobalIpv6Selector.classify(address)
                != GlobalIpv6Selector.AddressScope.GLOBAL_UNICAST) {
            throw new IllegalArgumentException("DDNS 仅允许发布公网 IPv6 GUA。");
        }
    }

    /** 返回经 IDN ASCII 规范化且为小写的主机名。 */
    public String getHostname() {
        return hostname;
    }

    /** 返回要发布的 IPv6 地址。 */
    public Inet6Address getAddress() {
        return address;
    }

    /** 返回触发原因。 */
    public Trigger getTrigger() {
        return trigger;
    }

    /** 返回适合 HTTP 参数和 JSON 字段的无 zone-id IPv6 文本。 */
    public String getAddressText() {
        String value = address.getHostAddress();
        int zone = value.indexOf('%');
        return zone < 0 ? value : value.substring(0, zone);
    }

    /** 返回不含认证信息的诊断文本。 */
    @Override
    public String toString() {
        return "DdnsUpdateRequest{hostname=" + hostname
                + ", address=" + getAddressText()
                + ", trigger=" + trigger + "}";
    }

    /** 验证并规范化用户输入的域名，避免请求头注入和歧义主机名。 */
    static String normalizeHostname(String hostname) {
        Objects.requireNonNull(hostname, "hostname");
        String candidate = hostname.trim();
        if (candidate.endsWith(".")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        if (candidate.isEmpty() || containsControlCharacter(candidate)) {
            throw new IllegalArgumentException("DDNS 主机名无效。");
        }

        final String ascii;
        try {
            ascii = IDN.toASCII(candidate, IDN.USE_STD3_ASCII_RULES)
                    .toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("DDNS 主机名无效。", error);
        }
        if (ascii.length() > 253 || ascii.indexOf('.') <= 0) {
            throw new IllegalArgumentException("DDNS 主机名必须是有效的完整域名。");
        }
        for (String label : ascii.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63) {
                throw new IllegalArgumentException("DDNS 主机名标签无效。");
            }
        }
        return ascii;
    }

    /** 判断字符串中是否包含 HTTP 不允许的控制字符。 */
    private static boolean containsControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 0x1f || character == 0x7f) {
                return true;
            }
        }
        return false;
    }
}
