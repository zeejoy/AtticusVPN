/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.core;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

/** 导出选项覆盖三种地址族，并确保空选项不改变原配置。 */
public final class ClientExportOptionsTest {
    @Test public void defaultsRemainByteForByteCompatible() {
        ClientConfigRenderer renderer = new ClientConfigRenderer();
        ServerSettings settings = new ServerSettings("vpn.example.com", 25565, 1380, true, false);
        try (PeerRecord peer = CoreTestFixtures.activePeer("phone", 2, TunnelMode.FULL);
             SecretBytes original = renderer.render(peer, settings, CoreTestFixtures.SERVER_PUBLIC_KEY);
             SecretBytes selected = renderer.render(peer, settings, CoreTestFixtures.SERVER_PUBLIC_KEY,
                     ClientExportOptions.defaults())) {
            byte[] before = original.copy(); byte[] after = selected.copy();
            try { assertArrayEquals(before, after); }
            finally { Arrays.fill(before, (byte) 0); Arrays.fill(after, (byte) 0); }
        }
    }

    @Test public void ipv4AndIpv6OnlyUseAssignedAddressesAndMatchingRoutes() {
        ServerSettings settings = new ServerSettings("2001:db8::1", 25565, 1380, true, false);
        try (PeerRecord peer = CoreTestFixtures.activePeer("phone", 2, TunnelMode.FULL)) {
            ClientExportOptions v4 = new ClientExportOptions(ClientExportOptions.AddressMode.IPV4_ONLY, "", "");
            ClientExportOptions v6 = new ClientExportOptions(ClientExportOptions.AddressMode.IPV6_ONLY, "", "");
            assertEquals(peer.clientIpv4Cidr(), v4.addresses(peer));
            assertEquals(peer.clientIpv6Cidr(), v6.addresses(peer));
            assertEquals("0.0.0.0/0", v4.allowedIps(peer, settings, new AllowedIpsPolicy()));
            assertEquals(ServerSettings.VPN_IPV6_NETWORK, v6.allowedIps(peer, settings, new AllowedIpsPolicy()));
            assertEquals(ServerSettings.SERVER_IPV4_ADDRESS, v4.dns(settings));
            assertEquals("", v6.dns(settings));
        }
    }

    @Test public void customDnsAndRoutesAreValidatedDeduplicatedAndPreserved() {
        ClientExportOptions options = new ClientExportOptions(ClientExportOptions.AddressMode.DUAL_STACK,
                " 192.0.2.53, 2001:db8::53,192.0.2.53 ", "192.0.2.0/24,2001:db8::/32");
        try (PeerRecord peer = CoreTestFixtures.activePeer("phone", 2, TunnelMode.FULL);
             SecretBytes configuration = new ClientConfigRenderer().render(peer,
                     ServerSettings.defaults("vpn.example.com"), CoreTestFixtures.SERVER_PUBLIC_KEY, options)) {
            byte[] bytes = configuration.copy();
            try {
                String text = new String(bytes, StandardCharsets.UTF_8);
                assertTrue(text.contains("DNS = 192.0.2.53, 2001:db8::53\n"));
                assertTrue(text.contains("AllowedIPs = 192.0.2.0/24, 2001:db8::/32\n"));
            } finally { Arrays.fill(bytes, (byte) 0); }
        }
    }

    @Test public void rejectsDnsNamesInvalidLiteralsAndConfigurationInjection() {
        for (String dns : new String[]{"dns.example.com", "256.0.0.1", "1.1.1.1,", "1.1.1.1/32", "1.1.1.1\nPostUp = x"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ClientExportOptions(ClientExportOptions.AddressMode.DUAL_STACK, dns, ""));
        }
        for (String routes : new String[]{"192.0.2.1", "192.0.2.0/33", "2001:db8::/129", "0.0.0.0/0\rEndpoint = bad", "a".repeat(16385)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ClientExportOptions(ClientExportOptions.AddressMode.DUAL_STACK, "", routes));
        }
    }
}
