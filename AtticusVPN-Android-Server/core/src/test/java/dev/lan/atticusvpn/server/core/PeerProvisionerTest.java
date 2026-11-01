/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.core;

import org.junit.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 验证每台设备获得独立地址与密钥材料，并拒绝重复或非法 Peer。 */
public final class PeerProvisionerTest {
    private static final Instant NOW = Instant.parse("2026-08-23T02:03:04Z");

    @Test
    public void provisionsNextCompatiblePeerWithInjectedOfficialKeyAdapter() {
        final PeerProvisioner provisioner = provisionerWithKeys(10, 11, 12);
        try (PeerRecord existing = CoreTestFixtures.activePeer("phone", 2, TunnelMode.FULL);
             PeerRecord created = provisioner.provision(
                     "laptop",
                     TunnelMode.LAN,
                     List.of(existing),
                     List.of("192.168.50.0/24"))) {
            assertEquals(3, created.clientId());
            assertEquals("10.77.0.3/32", created.clientIpv4Cidr());
            assertEquals("fd77:6174:7469:6375::3/128", created.clientIpv6Cidr());
            assertEquals(CoreTestFixtures.key(11), created.clientPublicKey());
            assertEquals(NOW, created.createdAt());
            assertTrue(created.isExportable());
        }
    }

    @Test
    public void rejectsCaseInsensitiveDuplicateBeforeGeneratingKeys() {
        final boolean[] generated = {false};
        final PeerProvisioner provisioner = new PeerProvisioner(
                () -> {
                    generated[0] = true;
                    return keyMaterial(1, 2, 3);
                });
        try (PeerRecord existing = CoreTestFixtures.activePeer("Phone", 2, TunnelMode.FULL)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> provisioner.provision(
                            "phone", TunnelMode.FULL, List.of(existing), List.of()));
            assertFalse(generated[0]);
        }
    }

    @Test
    public void fullPeerRejectsLanRoutesBeforeGeneratingKeys() {
        final boolean[] generated = {false};
        final PeerProvisioner provisioner = new PeerProvisioner(
                () -> {
                    generated[0] = true;
                    return keyMaterial(1, 2, 3);
                });
        assertThrows(
                IllegalArgumentException.class,
                () -> provisioner.provision(
                        "phone",
                        TunnelMode.FULL,
                        List.of(),
                        List.of("192.168.1.0/24")));
        assertFalse(generated[0]);
    }

    @Test
    public void malformedKeyMaterialIsRejectedWithFixedText() {
        final PeerProvisioner provisioner = new PeerProvisioner(
                () -> new PeerProvisioner.KeyMaterial(
                        SecretBytes.fromAscii("bad-private"),
                        CoreTestFixtures.key(2),
                        CoreTestFixtures.secretKey(3)));
        final IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> provisioner.provision(
                        "phone", TunnelMode.FULL, List.of(), List.of()));
        assertFalse(exception.getMessage().contains("bad-private"));
    }

    private static PeerProvisioner provisionerWithKeys(
            final int privateValue,
            final int publicValue,
            final int pskValue) {
        return new PeerProvisioner(
                () -> keyMaterial(privateValue, publicValue, pskValue),
                new AddressAllocator(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static PeerProvisioner.KeyMaterial keyMaterial(
            final int privateValue,
            final int publicValue,
            final int pskValue) {
        return new PeerProvisioner.KeyMaterial(
                CoreTestFixtures.secretKey(privateValue),
                CoreTestFixtures.key(publicValue),
                CoreTestFixtures.secretKey(pskValue));
    }
}
