/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 验证导出的文本是标准 WireGuard 配置，且秘密缓冲区在关闭后会被清零。 */
public final class ClientConfigRendererTest {
    private final ClientConfigRenderer renderer = new ClientConfigRenderer();

    @Test
    public void rendersExactCrossClientFullConfiguration() {
        final ServerSettings settings =
                new ServerSettings("vpn.example.test", 51_820, 1_380, true, false);
        try (PeerRecord peer = CoreTestFixtures.activePeer("phone", 2, TunnelMode.FULL);
             SecretBytes rendered = renderer.render(
                     peer,
                     settings,
                     CoreTestFixtures.SERVER_PUBLIC_KEY)) {
            final byte[] bytes = rendered.copy();
            try {
                final String text = new String(bytes, StandardCharsets.UTF_8);
                assertEquals(
                        "# AtticusVPN client: phone\n"
                                + "# Author: TianzeL\n"
                                + "[Interface]\n"
                                + "PrivateKey = " + CoreTestFixtures.key(2) + "\n"
                                + "Address = 10.77.0.2/32, fd77:6174:7469:6375::2/128\n"
                                + "DNS = 10.77.0.1\n"
                                + "MTU = 1380\n"
                                + "\n[Peer]\n"
                                + "PublicKey = " + CoreTestFixtures.SERVER_PUBLIC_KEY + "\n"
                                + "PresharedKey = " + CoreTestFixtures.key(3) + "\n"
                                + "AllowedIPs = 0.0.0.0/0, fd77:6174:7469:6375::/64\n"
                                + "Endpoint = vpn.example.test:51820\n"
                                + "PersistentKeepalive = 25\n",
                        text);
                assertTrue(bytes.length <= ClientConfigRenderer.MAX_CONFIGURATION_BYTES);
                assertFalse(text.startsWith("\uFEFF"));
                assertFalse(text.contains("\r"));
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        }
    }

    @Test
    public void omitsDnsUnlessRuntimeExplicitlyEnablesIt() {
        try (PeerRecord peer = CoreTestFixtures.activePeer("phone", 2, TunnelMode.FULL);
             SecretBytes rendered = renderer.render(
                     peer,
                     ServerSettings.defaults("vpn.example"),
                     CoreTestFixtures.SERVER_PUBLIC_KEY)) {
            final byte[] bytes = rendered.copy();
            try {
                assertFalse(new String(bytes, StandardCharsets.UTF_8).contains("\nDNS ="));
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        }
    }

    @Test
    public void outputContainsOnlyCommonStandardDirectives() {
        try (PeerRecord peer = CoreTestFixtures.activePeer("phone", 2, TunnelMode.FULL);
             SecretBytes rendered = renderer.render(
                     peer,
                     ServerSettings.defaults("vpn.example"),
                     CoreTestFixtures.SERVER_PUBLIC_KEY)) {
            final byte[] bytes = rendered.copy();
            try {
                final String text = new String(bytes, StandardCharsets.UTF_8);
                for (final String forbidden : List.of(
                        "PreUp", "PostUp", "PreDown", "PostDown", "Table", "SaveConfig",
                        "FwMark", "IncludedApplications", "ExcludedApplications")) {
                    assertFalse(forbidden, text.contains(forbidden));
                }
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        }
    }

    @Test
    public void sealedPeerCannotBeRenderedAgain() {
        try (SecretBytes psk = CoreTestFixtures.secretKey(3);
             PeerRecord peer = PeerRecord.sealed(
                     "phone",
                     2,
                     TunnelMode.FULL,
                     List.of(),
                     CoreTestFixtures.key(4),
                     psk,
                     CoreTestFixtures.CREATED,
                     Instant.parse("2026-08-23T01:00:00Z"))) {
            assertThrows(
                    IllegalStateException.class,
                    () -> renderer.render(
                            peer,
                            ServerSettings.defaults("vpn.example"),
                            CoreTestFixtures.SERVER_PUBLIC_KEY));
        }
    }

    @Test
    public void rejectsNonStandardServerKeyWithoutEchoingIt() {
        try (PeerRecord peer = CoreTestFixtures.activePeer("phone", 2, TunnelMode.FULL)) {
            final IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> renderer.render(
                            peer,
                            ServerSettings.defaults("vpn.example"),
                            "not-a-key"));
            assertFalse(exception.getMessage().contains("not-a-key"));
        }
    }
}
