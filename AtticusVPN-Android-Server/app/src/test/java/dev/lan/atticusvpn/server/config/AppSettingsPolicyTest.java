/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.config;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import dev.lan.atticusvpn.server.model.ServerState;

/** 设置校验不联网、结果确定。 */
public final class AppSettingsPolicyTest {
    @Test
    public void normalizesDdnsAndEndpointPriority() {
        final ServerState state = new ServerState();
        state.endpointHost = "vpn.example.com";
        state.ddnsHostname = "fallback.example.com";
        assertEquals("vpn.example.com",
                AppSettingsPolicy.resolveEndpointHost(state, "2001:db8::1"));
        assertEquals("home.example.com",
                AppSettingsPolicy.normalizeDdnsHostname("HOME.Example.COM.", "cloudflare"));
        assertEquals("myhome.duckdns.org",
                AppSettingsPolicy.normalizeDdnsHostname("myhome", "duckdns"));
    }

    @Test
    public void normalizesAndDeduplicatesNumericDns() {
        assertEquals("1.1.1.1,2001:4860:4860:0:0:0:0:8888",
                AppSettingsPolicy.normalizeCustomDns(
                        "1.1.1.1, 1.1.1.1, 2001:4860:4860::8888"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void customDnsRejectsHostnameWithoutResolvingIt() {
        AppSettingsPolicy.normalizeCustomDns("resolver.example.com");
    }

    @Test(expected = IllegalStateException.class)
    public void endpointRequiresConfiguredValueOrCurrentGua() {
        AppSettingsPolicy.resolveEndpointHost(new ServerState(), "");
    }

    @Test
    public void disabledDdnsNeverUsesStaleHostname() {
        final ServerState state = new ServerState();
        state.ddnsProvider = "disabled";
        state.ddnsHostname = "stale.example.com";
        assertEquals("2001:db8::9",
                AppSettingsPolicy.resolveEndpointHost(state, "2001:db8::9"));
    }
}
