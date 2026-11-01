/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

/** 保留原地址与保守 MTU；0.5.7 新配置默认 UDP 51820，原端口依然可用。 */
public final class ServerSettingsTest {
    @Test
    public void newSettingsUseWireGuardConventionalPort() {
        final ServerSettings settings = ServerSettings.defaults("vpn.example");
        assertEquals("10.77.0.0/24", ServerSettings.VPN_IPV4_NETWORK);
        assertEquals("10.77.0.1/24", ServerSettings.SERVER_IPV4_CIDR);
        assertEquals("fd77:6174:7469:6375::/64", ServerSettings.VPN_IPV6_NETWORK);
        assertEquals("fd77:6174:7469:6375::1/64", ServerSettings.SERVER_IPV6_CIDR);
        assertEquals("vpn.example:51820", settings.endpoint());
        assertEquals(51_820, settings.listenPort());
        assertEquals(1_380, settings.mtu());
        assertFalse(settings.dnsEnabled());
        assertFalse(settings.fullTunnelIpv6());
    }

    @Test
    public void legacyAndCustomPortsRemainExplicitlySupported() {
        for (final int port : new int[]{25_565, 1, 32_768, 65_535}) {
            final ServerSettings settings = new ServerSettings("vpn.example", port, 1_380, false, false);
            assertEquals(port, settings.listenPort());
            assertEquals("vpn.example:" + port, settings.endpoint());
        }
    }

    @Test
    public void rejectsMtuOutsideConservativeRange() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ServerSettings("vpn.example", 51_820, 1_279, false, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ServerSettings("vpn.example", 51_820, 1_421, false, false));
    }
}
