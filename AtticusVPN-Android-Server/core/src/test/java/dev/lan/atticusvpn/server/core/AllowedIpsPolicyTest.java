/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.core;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/** 验证第一阶段只接管 IPv4 默认路由，避免误把未完成的 IPv6 转发写成 {@code ::/0}。 */
public final class AllowedIpsPolicyTest {
    private final AllowedIpsPolicy policy = new AllowedIpsPolicy();

    @Test
    public void defaultFullModeRoutesIpv4AndOnlyTunnelIpv6() {
        try (PeerRecord peer = CoreTestFixtures.activePeer("phone", 2, TunnelMode.FULL)) {
            assertEquals(
                    "0.0.0.0/0, fd77:6174:7469:6375::/64",
                    policy.render(peer, ServerSettings.defaults("vpn.example")));
        }
    }

    @Test
    public void explicitIpv6FullModeAddsIpv6Default() {
        final ServerSettings settings =
                new ServerSettings("vpn.example", 51_820, 1_380, false, true);
        try (PeerRecord peer = CoreTestFixtures.activePeer("phone", 2, TunnelMode.FULL)) {
            assertEquals("0.0.0.0/0, ::/0", policy.render(peer, settings));
        }
    }

    @Test
    public void lanModeIncludesVpnNetworksAndDeduplicatedRoutes() {
        try (SecretBytes privateKey = CoreTestFixtures.secretKey(1);
             SecretBytes psk = CoreTestFixtures.secretKey(2);
             PeerRecord peer = PeerRecord.active(
                     "phone",
                     2,
                     TunnelMode.LAN,
                     List.of("192.168.50.0/24", "192.168.50.0/24", "fd12:3456::/64"),
                     CoreTestFixtures.key(3),
                     psk,
                     privateKey,
                     CoreTestFixtures.CREATED)) {
            assertEquals(
                    "10.77.0.0/24, fd77:6174:7469:6375::/64, "
                            + "192.168.50.0/24, fd12:3456::/64",
                    policy.render(peer, ServerSettings.defaults("vpn.example")));
        }
    }

    @Test
    public void lanRoutesRejectDefaultsAndMalformedCidrs() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AllowedIpsPolicy.normalizeLanRoutes(List.of("0.0.0.0/0")));
        assertThrows(
                IllegalArgumentException.class,
                () -> AllowedIpsPolicy.normalizeLanRoutes(List.of("0:0:0:0:0:0:0:0/0")));
        assertThrows(
                IllegalArgumentException.class,
                () -> AllowedIpsPolicy.normalizeLanRoutes(List.of("192.168.1.999/24")));
        assertThrows(
                IllegalArgumentException.class,
                () -> AllowedIpsPolicy.normalizeLanRoutes(List.of("127.1/8")));
        assertThrows(
                IllegalArgumentException.class,
                () -> AllowedIpsPolicy.normalizeLanRoutes(List.of("example.org/24")));
    }
}
