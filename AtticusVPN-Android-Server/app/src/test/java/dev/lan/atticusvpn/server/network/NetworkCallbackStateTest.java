/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.network;

import org.junit.Test;

import static org.junit.Assert.*;

/** 固定只合并同一网络回调、不依赖同步查询的回归边界。 */
public final class NetworkCallbackStateTest {
    @Test public void startsEmptyAndIgnoresEventsBeforeAvailable() {
        NetworkCallbackState<String, String, String> state = new NetworkCallbackState<>();
        assertFalse(state.capabilities("wifi", "valid"));
        assertFalse(state.links("wifi", "ipv6"));
        assertNull(state.capabilities());
        assertNull(state.links());
    }

    @Test public void mergesCallbackArgumentsInEitherOrder() {
        NetworkCallbackState<String, String, String> state = new NetworkCallbackState<>();
        state.available("wifi");
        assertTrue(state.links("wifi", "ipv6"));
        assertNull(state.capabilities());
        assertTrue(state.capabilities("wifi", "valid"));
        assertEquals("ipv6", state.links());
        assertEquals("valid", state.capabilities());
        assertTrue(state.capabilities("wifi", "unvalidated"));
        assertEquals("unvalidated", state.capabilities());
        assertEquals("ipv6", state.links());
    }

    @Test public void switchNeverCombinesOldLinksWithNewCapabilities() {
        NetworkCallbackState<String, String, String> state = new NetworkCallbackState<>();
        state.available("wifi");
        state.capabilities("wifi", "valid");
        state.links("wifi", "wifi-ipv6");
        state.available("cellular");
        assertNull(state.links());
        assertNull(state.capabilities());
        assertFalse(state.links("wifi", "late"));
        assertFalse(state.capabilities("wifi", "late"));
        assertFalse(state.lost("wifi"));
        assertTrue(state.isCurrent("cellular"));
        state.capabilities("cellular", "valid");
        state.links("cellular", "cellular-ipv6");
        assertEquals("cellular-ipv6", state.links());
    }

    @Test public void lossAndStopDiscardAllCachedState() {
        NetworkCallbackState<String, String, String> state = new NetworkCallbackState<>();
        state.available("wifi");
        state.links("wifi", "ipv6");
        assertTrue(state.lost("wifi"));
        assertNull(state.links());
        assertFalse(state.isCurrent("wifi"));
        state.available("cellular");
        state.capabilities("cellular", "valid");
        state.clear();
        assertNull(state.capabilities());
        assertFalse(state.capabilities("cellular", "late"));
    }
}
