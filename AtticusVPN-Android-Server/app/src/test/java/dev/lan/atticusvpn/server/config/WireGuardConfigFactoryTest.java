/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.wireguard.crypto.Key;
import com.wireguard.crypto.KeyPair;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.Test;

import dev.lan.atticusvpn.server.core.SecretBytes;
import dev.lan.atticusvpn.server.model.ServerState;
import dev.lan.atticusvpn.server.model.StoredPeer;

/** 固定 Android Server v1 与现有客户端共同接受的标准配置边界。 */
public final class WireGuardConfigFactoryTest {
    @Test
    public void freshStateUsesNewDefaultForListenerAndClientEndpoint() throws Exception {
        final Fixture fixture = fixture();
        assertEquals(51_820, fixture.state.listenPort);
        assertTrue(new WireGuardConfigFactory().renderServerUapi(fixture.state)
                .contains("listen_port=51820\n"));
        try (SecretBytes configuration = new WireGuardConfigFactory().renderClientConfiguration(
                fixture.state, fixture.enabled, fixture.clientPrivate, "2001:db8::1")) {
            final byte[] raw = configuration.copy();
            try {
                assertTrue(new String(raw, StandardCharsets.UTF_8)
                        .contains("Endpoint = [2001:db8::1]:51820\n"));
            } finally { Arrays.fill(raw, (byte) 0); }
        }
    }

    /** 升级后保留原显式端口，改端口后服务端和导出 Endpoint 使用同一个值。 */
    @Test
    public void explicitExistingAndCustomPortsArePreserved() throws Exception {
        final Fixture fixture = fixture();
        for (final int port : new int[]{51820, 25565, 1, 65535}) {
            fixture.state.listenPort = port;
            assertTrue(new WireGuardConfigFactory().renderServerUapi(fixture.state)
                    .contains("listen_port=" + port + "\n"));
            try (SecretBytes configuration = new WireGuardConfigFactory().renderClientConfiguration(
                    fixture.state, fixture.enabled, fixture.clientPrivate, "2001:db8::1")) {
                final byte[] raw = configuration.copy();
                try {
                    assertTrue(new String(raw, StandardCharsets.UTF_8)
                            .contains("Endpoint = [2001:db8::1]:" + port + "\n"));
                } finally { Arrays.fill(raw, (byte) 0); }
            }
        }
    }

    @Test
    public void clientConfigUsesIpv4DnsAndNeverExportsIpv6DefaultRoute() throws Exception {
        final Fixture fixture = fixture();
        try (SecretBytes configuration = new WireGuardConfigFactory().renderClientConfiguration(
                fixture.state, fixture.enabled, fixture.clientPrivate, "2001:db8::1")) {
            final byte[] raw = configuration.copy();
            try {
                final String text = new String(raw, StandardCharsets.UTF_8);
                assertTrue(text.contains("DNS = 10.77.0.1\n"));
                assertTrue(text.contains(
                        "AllowedIPs = 0.0.0.0/0, fd77:6174:7469:6375::/64\n"));
                assertTrue(text.contains("Endpoint = [2001:db8::1]:51820\n"));
                assertFalse(text.contains("::/0"));
                assertFalse(text.contains("fd77:6174:7469:6375::1"));
            } finally {
                Arrays.fill(raw, (byte) 0);
            }
        }
    }

    @Test
    public void serverUapiContainsEnabledPeersOnlyAndUsesHexKeys() throws Exception {
        final Fixture fixture = fixture();
        final String uapi = new WireGuardConfigFactory().renderServerUapi(fixture.state);
        assertTrue(uapi.startsWith("private_key="
                + Key.fromBase64(fixture.state.serverPrivateKey).toHex()));
        assertTrue(uapi.contains("public_key="
                + Key.fromBase64(fixture.enabled.publicKey()).toHex()));
        assertFalse(uapi.contains(Key.fromBase64(fixture.disabled.publicKey()).toHex()));
        assertTrue(uapi.contains("allowed_ip=10.77.0.2/32"));
        assertTrue(uapi.contains("allowed_ip=fd77:6174:7469:6375::2/128"));
    }

    private static Fixture fixture() throws Exception {
        final ServerState state = new ServerState();
        final KeyPair server = new KeyPair();
        state.serverPrivateKey = server.getPrivateKey().toBase64();
        state.serverPublicKey = server.getPublicKey().toBase64();
        final KeyPair enabledKeys = new KeyPair();
        final KeyPair disabledKeys = new KeyPair();
        final byte[] pskBytes = new byte[32];
        Arrays.fill(pskBytes, (byte) 7);
        final String psk = Key.fromBytes(pskBytes).toBase64();
        Arrays.fill(pskBytes, (byte) 0);
        final StoredPeer enabled = new StoredPeer(
                "11111111111111111111111111111111", "phone", 2,
                enabledKeys.getPublicKey().toBase64(), psk, true, false, 1L, 0L);
        final StoredPeer disabled = new StoredPeer(
                "22222222222222222222222222222222", "tablet", 3,
                disabledKeys.getPublicKey().toBase64(), psk, false, false, 1L, 0L);
        state.addPeer(enabled);
        state.addPeer(disabled);
        return new Fixture(
                state, enabled, disabled, enabledKeys.getPrivateKey().toBase64());
    }

    private record Fixture(
            ServerState state,
            StoredPeer enabled,
            StoredPeer disabled,
            String clientPrivate) {
    }
}
