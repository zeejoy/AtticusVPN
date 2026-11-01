/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.runtime;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import dev.lan.atticusvpn.server.config.AppSettingsPolicy;
import dev.lan.atticusvpn.server.model.ServerState;
import dev.lan.atticusvpn.server.network.NetworkSnapshot;

/** 把 Android LinkProperties 或管理员数字地址转换为 native-engine 的 DNS CSV。 */
public final class DnsUpstreamResolver {
    private DnsUpstreamResolver() {
    }

    public static String resolve(final ServerState state, final NetworkSnapshot snapshot) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(snapshot, "snapshot");
        if (!state.dnsEnabled) {
            // 与 native-engine 的显式关闭约定；不能用空串与“系统没有 DNS”混淆。
            return "!disabled";
        }
        if (AppSettingsPolicy.DNS_CUSTOM.equals(
                AppSettingsPolicy.normalizeDnsMode(state.dnsMode))) {
            final String custom = AppSettingsPolicy.normalizeCustomDns(state.customDns);
            if (custom.isEmpty()) {
                throw new IllegalStateException("CUSTOM_DNS_REQUIRED");
            }
            return custom;
        }

        final LinkedHashSet<String> preferred = new LinkedHashSet<>();
        final LinkedHashSet<String> scopedLinkLocal = new LinkedHashSet<>();
        for (final InetAddress address : snapshot.getDnsServers()) {
            if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()) {
                continue;
            }
            // Link-local IPv6 DNS 离开 scope/zone 通常不可路由。保留 Android 提供的
            // %wlan0/%数字，并把全局 IPv6/IPv4 放在前面，减少切网期间的超时。
            final String text = address.getHostAddress();
            if (address.isLinkLocalAddress()) {
                if (text.indexOf('%') >= 0) {
                    scopedLinkLocal.add(text);
                }
            } else {
                preferred.add(text);
            }
        }
        final List<String> ordered = new ArrayList<>(preferred);
        ordered.addAll(scopedLinkLocal);
        if (ordered.isEmpty()) {
            throw new IllegalStateException("UPSTREAM_DNS_UNAVAILABLE");
        }
        return String.join(",", ordered.size() > 8 ? ordered.subList(0, 8) : ordered);
    }
}
