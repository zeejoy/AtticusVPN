/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.core;

import java.util.LinkedHashSet;
import java.util.Objects;

/** 本次导出的非敏感选项，不写入 Peer 状态、不改变服务端地址分配或转发能力。 */
public final class ClientExportOptions {
    public enum AddressMode { DUAL_STACK, IPV4_ONLY, IPV6_ONLY }
    private final AddressMode addressMode;
    private final String dns;
    private final String allowedIps;

    /** 空白覆盖值表示沿用当前配置；只接受数字 IP/CIDR，禁止换行或配置字段注入。 */
    public ClientExportOptions(AddressMode addressMode, String dns, String allowedIps) {
        this.addressMode = Objects.requireNonNull(addressMode, "addressMode");
        this.dns = normalize(dns, false);
        this.allowedIps = normalize(allowedIps, true);
    }

    public static ClientExportOptions defaults() {
        return new ClientExportOptions(AddressMode.DUAL_STACK, "", "");
    }

    public String addresses(PeerRecord peer) {
        return switch (addressMode) {
            case IPV4_ONLY -> peer.clientIpv4Cidr();
            case IPV6_ONLY -> peer.clientIpv6Cidr();
            case DUAL_STACK -> peer.clientIpv4Cidr() + ", " + peer.clientIpv6Cidr();
        };
    }

    public String dns(ServerSettings settings) {
        if (dns != null) return dns;
        // 内置 DNS 只提供 IPv4；IPv6-only 导出不能宣称存在 IPv6 DNS 服务。
        return settings.dnsEnabled() && addressMode != AddressMode.IPV6_ONLY
                ? ServerSettings.SERVER_IPV4_ADDRESS : "";
    }

    public String allowedIps(PeerRecord peer, ServerSettings settings, AllowedIpsPolicy policy) {
        if (allowedIps != null) return allowedIps;
        final String original = policy.render(peer, settings);
        final StringBuilder selected = new StringBuilder();
        for (String route : original.split(",\\s*")) {
            final boolean ipv6 = route.indexOf(':') >= 0;
            if ((addressMode == AddressMode.IPV4_ONLY && ipv6)
                    || (addressMode == AddressMode.IPV6_ONLY && !ipv6)) continue;
            if (selected.length() > 0) selected.append(", ");
            selected.append(route);
        }
        return selected.toString();
    }

    private static String normalize(String input, boolean cidr) {
        Objects.requireNonNull(input, "input");
        if (input.length() > 16_384 || input.indexOf('\n') >= 0 || input.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("INVALID_EXPORT_OPTIONS");
        }
        if (input.trim().isEmpty()) return null;
        final LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String part : input.split(",", -1)) {
            final String value = part.trim();
            if (value.isEmpty() || (!cidr && value.indexOf('/') >= 0)) {
                throw new IllegalArgumentException("INVALID_EXPORT_OPTIONS");
            }
            AllowedIpsPolicy.parseCidr(cidr ? value
                    : value + (value.indexOf(':') >= 0 ? "/128" : "/32"));
            values.add(value);
        }
        return String.join(", ", values);
    }
}
