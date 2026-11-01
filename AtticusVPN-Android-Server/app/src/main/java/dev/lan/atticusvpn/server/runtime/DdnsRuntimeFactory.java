/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.runtime;

import android.content.Context;

import java.util.Collections;

import dev.lan.atticusvpn.server.config.AppSettingsPolicy;
import dev.lan.atticusvpn.server.ddns.CloudflareDdnsProvider;
import dev.lan.atticusvpn.server.ddns.CustomApiDdnsProvider;
import dev.lan.atticusvpn.server.ddns.DdnsProvider;
import dev.lan.atticusvpn.server.ddns.DdnsUpdateManager;
import dev.lan.atticusvpn.server.ddns.DuckDnsDdnsProvider;
import dev.lan.atticusvpn.server.ddns.DynuDdnsProvider;
import dev.lan.atticusvpn.server.ddns.FreeDnsDdnsProvider;
import dev.lan.atticusvpn.server.ddns.NoIpDdnsProvider;
import dev.lan.atticusvpn.server.ddns.SafeDdnsLogger;
import dev.lan.atticusvpn.server.model.ServerState;
import dev.lan.atticusvpn.server.network.AndroidNetworkMonitor;
import dev.lan.atticusvpn.server.network.GlobalIpv6Selector;

/** 根据加密设置创建 DDNS 生命周期；disabled 返回 null 且绝不访问网络。 */
public final class DdnsRuntimeFactory {
    private DdnsRuntimeFactory() {
    }

    public static DdnsUpdateManager create(final Context context, final ServerState state) {
        final String providerId = AppSettingsPolicy.normalizeProvider(state.ddnsProvider);
        if ("disabled".equals(providerId)) {
            return null;
        }
        final String hostname = AppSettingsPolicy.normalizeDdnsHostname(
                state.ddnsHostname, providerId);
        final DdnsProvider provider;
        switch (providerId) {
            case "duckdns":
                provider = new DuckDnsDdnsProvider(state.ddnsSecret);
                break;
            case "cloudflare":
                provider = new CloudflareDdnsProvider(
                        state.ddnsZone, state.ddnsRecord, state.ddnsSecret);
                break;
            case "noip":
                provider = new NoIpDdnsProvider(state.ddnsAccount, state.ddnsSecret);
                break;
            case "dynu":
                provider = new DynuDdnsProvider(state.ddnsAccount, state.ddnsSecret);
                break;
            case "freedns":
                provider = new FreeDnsDdnsProvider(state.ddnsSecret);
                break;
            case "custom":
                provider = new CustomApiDdnsProvider(
                        state.ddnsCustomUrl,
                        "GET",
                        Collections.emptyMap(),
                        "",
                        state.ddnsSecret,
                        "");
                break;
            default:
                throw new IllegalArgumentException("INVALID_DDNS_PROVIDER");
        }
        return new DdnsUpdateManager(
                new AndroidNetworkMonitor(context, new GlobalIpv6Selector()),
                provider,
                hostname,
                SafeDdnsLogger.noOp());
    }
}
