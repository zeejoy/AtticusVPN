/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.core;

import org.junit.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

/** 验证 Seal 先提交不可逆标记、再删除导出材料的崩溃安全顺序。 */
public final class SealStateMachineTest {
    private static final Instant SEALED_AT = Instant.parse("2026-08-23T05:06:07Z");

    @Test
    public void commitsMarkerBeforeDeletingExportMaterial() {
        final List<String> events = new ArrayList<>();
        final SealStateMachine machine = machine();
        try (PeerRecord peer = CoreTestFixtures.activePeer("phone", 2, TunnelMode.FULL)) {
            final SealStateMachine.Result result = machine.seal(peer, new SealStateMachine.Persistence() {
                @Override
                public void markSealed(final String peerName, final Instant sealedAt) {
                    events.add("mark:" + peerName + ':' + sealedAt);
                }

                @Override
                public void deleteExportMaterial(final String peerName) {
                    events.add("delete:" + peerName);
                }
            });

            assertEquals(SealStateMachine.Result.SEALED, result);
            assertEquals(
                    List.of("mark:phone:" + SEALED_AT, "delete:phone"),
                    events);
            assertEquals(PeerState.SEALED, peer.state());
            assertEquals(SEALED_AT, peer.sealedAt());
            assertFalse(peer.isExportable());
            assertThrows(IllegalStateException.class, peer::copyClientPrivateKey);
            try (SecretBytes retainedPsk = peer.copyPresharedKey()) {
                assertNotNull(retainedPsk);
            }
        }
    }

    @Test
    public void failedMarkerLeavesPeerActiveAndDoesNotDelete() {
        final List<String> events = new ArrayList<>();
        try (PeerRecord peer = CoreTestFixtures.activePeer("phone", 2, TunnelMode.FULL)) {
            assertThrows(
                    IllegalStateException.class,
                    () -> machine().seal(peer, new SealStateMachine.Persistence() {
                        @Override
                        public void markSealed(final String peerName, final Instant sealedAt) {
                            events.add("mark");
                            throw new IllegalStateException("fixed storage failure");
                        }

                        @Override
                        public void deleteExportMaterial(final String peerName) {
                            events.add("delete");
                        }
                    }));
            assertEquals(List.of("mark"), events);
            assertEquals(PeerState.ACTIVE, peer.state());
        }
    }

    @Test
    public void failedDeleteLeavesPeerSealedAndNextCallRetriesOnlyDelete() {
        final List<String> events = new ArrayList<>();
        final boolean[] fail = {true};
        final SealStateMachine.Persistence persistence = new SealStateMachine.Persistence() {
            @Override
            public void markSealed(final String peerName, final Instant sealedAt) {
                events.add("mark");
            }

            @Override
            public void deleteExportMaterial(final String peerName) {
                events.add("delete");
                if (fail[0]) {
                    fail[0] = false;
                    throw new IllegalStateException("fixed delete failure");
                }
            }
        };

        try (PeerRecord peer = CoreTestFixtures.activePeer("phone", 2, TunnelMode.FULL)) {
            assertThrows(IllegalStateException.class, () -> machine().seal(peer, persistence));
            assertEquals(PeerState.SEALED, peer.state());
            assertFalse(peer.isExportable());
            assertEquals(
                    SealStateMachine.Result.ALREADY_SEALED,
                    machine().seal(peer, persistence));
            assertEquals(List.of("mark", "delete", "delete"), events);
        }
    }

    private static SealStateMachine machine() {
        return new SealStateMachine(Clock.fixed(SEALED_AT, ZoneOffset.UTC));
    }
}
