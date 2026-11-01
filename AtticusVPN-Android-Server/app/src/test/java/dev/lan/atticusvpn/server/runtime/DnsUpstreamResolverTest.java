/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.runtime;

import static org.junit.Assert.assertEquals;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

import dev.lan.atticusvpn.server.model.ServerState;
import dev.lan.atticusvpn.server.network.NetworkSnapshot;

/** 验证自动 DNS 顺序与 link-local scope 不会被破坏。 */
public final class DnsUpstreamResolverTest {
    @Test
    public void preservesScopeAndPrefersNonLinkLocal() throws Exception {
        final byte[] linkLocal = new byte[16];
        linkLocal[0] = (byte) 0xfe;
        linkLocal[1] = (byte) 0x80;
        linkLocal[15] = 1;
        final Inet6Address scoped = Inet6Address.getByAddress(null, linkLocal, 7);
        final NetworkSnapshot snapshot = new NetworkSnapshot(
                42L,
                NetworkSnapshot.NetworkType.WIFI,
                Collections.emptyList(),
                null,
                Arrays.asList(scoped, InetAddress.getByName("1.1.1.1")),
                true,
                false);
        final ServerState state = new ServerState();
        state.dnsMode = "AUTOMATIC";

        assertEquals("1.1.1.1," + scoped.getHostAddress(),
                DnsUpstreamResolver.resolve(state, snapshot));
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsUnscopedLinkLocalOnly() throws Exception {
        final NetworkSnapshot snapshot = new NetworkSnapshot(
                42L,
                NetworkSnapshot.NetworkType.WIFI,
                Collections.emptyList(),
                null,
                Collections.singletonList(InetAddress.getByName("fe80::1")),
                true,
                false);
        DnsUpstreamResolver.resolve(new ServerState(), snapshot);
    }

    @Test
    public void disabledDnsUsesExplicitNativeSentinel() throws Exception {
        final NetworkSnapshot snapshot = new NetworkSnapshot(
                42L,
                NetworkSnapshot.NetworkType.WIFI,
                Collections.emptyList(),
                null,
                Collections.emptyList(),
                true,
                false);
        final ServerState state = new ServerState();
        state.dnsEnabled = false;
        assertEquals("!disabled", DnsUpstreamResolver.resolve(state, snapshot));
    }
}
