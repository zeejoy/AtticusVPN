/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.core;

import org.junit.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

/** 验证 Peer 元数据不会通过字符串输出泄密，并固定 Seal 后的保留字段。 */
public final class PeerRecordTest {
    @Test
    public void safeStringDoesNotContainAnyKey() {
        try (PeerRecord peer = CoreTestFixtures.activePeer("phone", 2, TunnelMode.FULL)) {
            final String text = peer.toString();
            assertFalse(text.contains(CoreTestFixtures.key(2)));
            assertFalse(text.contains(CoreTestFixtures.key(3)));
            assertFalse(text.contains(CoreTestFixtures.key(4)));
        }
    }

    @Test
    public void sealedFactoryRetainsServerRequiredMetadataWithoutPrivateKey() {
        final Instant sealedAt = Instant.parse("2026-08-23T03:00:00Z");
        try (SecretBytes psk = CoreTestFixtures.secretKey(3);
             PeerRecord peer = PeerRecord.sealed(
                     "phone",
                     10,
                     TunnelMode.LAN,
                     List.of("192.168.1.0/24"),
                     CoreTestFixtures.key(4),
                     psk,
                     CoreTestFixtures.CREATED,
                     sealedAt)) {
            assertEquals(PeerState.SEALED, peer.state());
            assertEquals("10.77.0.10/32", peer.clientIpv4Cidr());
            assertEquals("fd77:6174:7469:6375::a/128", peer.clientIpv6Cidr());
            assertEquals(CoreTestFixtures.key(4), peer.clientPublicKey());
            assertEquals(sealedAt, peer.sealedAt());
            assertThrows(IllegalStateException.class, peer::copyClientPrivateKey);
            try (SecretBytes retainedPsk = peer.copyPresharedKey()) {
                assertEquals(44, retainedPsk.length());
            }
        }
    }

    @Test
    public void fullFactoryRejectsLanOnlyRoutes() {
        try (SecretBytes privateKey = CoreTestFixtures.secretKey(1);
             SecretBytes psk = CoreTestFixtures.secretKey(2)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> PeerRecord.active(
                            "phone",
                            2,
                            TunnelMode.FULL,
                            List.of("192.168.1.0/24"),
                            CoreTestFixtures.key(3),
                            psk,
                            privateKey,
                            CoreTestFixtures.CREATED));
        }
    }
}
