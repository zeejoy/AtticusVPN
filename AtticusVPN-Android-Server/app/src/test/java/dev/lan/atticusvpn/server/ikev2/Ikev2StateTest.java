/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ikev2;

import dev.lan.atticusvpn.server.capability.DeviceCapabilitySnapshot;
import dev.lan.atticusvpn.server.capability.IpsecCapabilityChecker;
import dev.lan.atticusvpn.server.root.RootState;
import org.junit.Test;
import static org.junit.Assert.*;

/** 未集成真实引擎时，任何 Root 状态都不能被误报为可启动 IKEv2。 */
public final class Ikev2StateTest {
    private static Ikev2State state(RootState root) {
        return Ikev2State.fromReadOnlySnapshot(new DeviceCapabilitySnapshot(root,
                "test", "test", "test", "unknown", "unknown", IpsecCapabilityChecker.notChecked()));
    }
    @Test public void rootAloneDoesNotMakeIkeAvailable() {
        for (RootState root : RootState.values()) assertNotEquals(Ikev2State.IKEV2_AVAILABLE, state(root));
        assertEquals(Ikev2State.IKEV2_DATAPLANE_UNAVAILABLE, state(RootState.ROOT_GRANTED));
    }
    @Test public void distinguishesDeniedMissingAndError() {
        assertEquals(Ikev2State.IKEV2_ROOT_DENIED, state(RootState.ROOT_PERMISSION_DENIED));
        assertEquals(Ikev2State.IKEV2_ROOT_REQUIRED, state(RootState.ROOT_NOT_FOUND));
        assertEquals(Ikev2State.IKEV2_ERROR, state(RootState.ROOT_ERROR));
    }
}
