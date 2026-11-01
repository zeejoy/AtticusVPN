/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.runtime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import dev.lan.atticusvpn.server.network.NetworkSnapshot;

/** 固定“默认 VPN 不可作为自托管服务器承载网络”的边界。 */
public final class RuntimeNetworkPolicyTest {
    @Test
    public void rejectsVpnButAllowsPhysicalNetworkTypes() {
        assertTrue(RuntimeNetworkPolicy.isRejectedDefaultType(NetworkSnapshot.NetworkType.VPN));
        assertFalse(RuntimeNetworkPolicy.isRejectedDefaultType(NetworkSnapshot.NetworkType.WIFI));
        assertFalse(RuntimeNetworkPolicy.isRejectedDefaultType(NetworkSnapshot.NetworkType.CELLULAR));
        assertFalse(RuntimeNetworkPolicy.isRejectedDefaultType(NetworkSnapshot.NetworkType.ETHERNET));
    }

    @Test
    public void distinguishesValidationFromMissingIpv6() {
        assertEquals(RuntimeNetworkPolicy.NETWORK_NOT_VALIDATED,
                RuntimeNetworkPolicy.waitingCode(false, true));
        assertEquals(RuntimeNetworkPolicy.NETWORK_NOT_VALIDATED,
                RuntimeNetworkPolicy.waitingCode(false, false));
        assertEquals(RuntimeNetworkPolicy.GLOBAL_IPV6_UNAVAILABLE,
                RuntimeNetworkPolicy.waitingCode(true, false));
        assertEquals("", RuntimeNetworkPolicy.waitingCode(true, true));
    }

    @Test
    public void hintsExplainDistinctRecoveryActions() {
        assertTrue(RuntimeNetworkPolicy.waitingHint("NETWORK_NOT_VALIDATED").contains("登录页"));
        assertTrue(RuntimeNetworkPolicy.waitingHint("GLOBAL_IPV6_UNAVAILABLE").contains("IPv6"));
        assertTrue(RuntimeNetworkPolicy.waitingHint("DEFAULT_NETWORK_IS_VPN").contains("其他 VPN"));
        assertTrue(RuntimeNetworkPolicy.waitingHint("NETWORK_UNAVAILABLE").contains("联网"));
    }
}
