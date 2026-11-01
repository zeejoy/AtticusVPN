/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.export;

import com.wireguard.config.Config;
import com.wireguard.crypto.KeyPair;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import org.junit.Test;
import dev.lan.atticusvpn.server.config.WireGuardConfigFactory;
import dev.lan.atticusvpn.server.core.ClientExportOptions;
import dev.lan.atticusvpn.server.core.SecretBytes;
import dev.lan.atticusvpn.server.model.ServerState;
import dev.lan.atticusvpn.server.model.StoredPeer;
import static org.junit.Assert.*;

/** 导出失败不能截断配置、替换密钥或注入配置字段。 */
public final class ExportBoundaryTest {
    @Test public void oversizedQrLeavesFullConfigurationUsable() throws Exception {
        KeyPair keys = new KeyPair();
        ServerState state = new ServerState();
        state.serverPublicKey = new KeyPair().getPublicKey().toBase64();
        StoredPeer peer = peer(keys, false);
        StringBuilder routes = new StringBuilder();
        for (int i = 1; i <= 220; i++) {
            if (i > 1) routes.append(", ");
            routes.append("192.0.2.").append(i).append("/32");
        }
        try (SecretBytes config = new WireGuardConfigFactory().renderClientConfiguration(state,
                peer, keys.getPrivateKey().toBase64(), "vpn.example.com", new ClientExportOptions(
                        ClientExportOptions.AddressMode.DUAL_STACK, "", routes.toString()))) {
            byte[] original = config.copy();
            try {
                assertThrows(QrCodeGenerator.TooLongException.class, () -> new QrCodeGenerator().encode(config));
                assertArrayEquals(original, config.copy());
                assertEquals(220, Config.parse(new ByteArrayInputStream(original)).getPeers().get(0).getAllowedIps().size());
                assertTrue(new String(original, StandardCharsets.UTF_8).endsWith("PersistentKeepalive = 25\n"));
            } finally { Arrays.fill(original, (byte) 0); }
        }
    }

    @Test public void missingOrMismatchedPrivateKeyCannotProduceAConfig() {
        KeyPair keys = new KeyPair();
        ServerState state = new ServerState();
        state.serverPublicKey = keys.getPublicKey().toBase64();
        WireGuardConfigFactory factory = new WireGuardConfigFactory();
        assertThrows(Exception.class, () -> factory.renderClientConfiguration(state, peer(keys, false), null, "vpn.example.com"));
        assertThrows(Exception.class, () -> factory.renderClientConfiguration(state, peer(keys, false), "", "vpn.example.com"));
        assertThrows(IllegalArgumentException.class, () -> factory.renderClientConfiguration(state, peer(keys, false),
                new KeyPair().getPrivateKey().toBase64(), "vpn.example.com"));
        assertThrows(IllegalStateException.class, () -> factory.renderClientConfiguration(state, peer(keys, true),
                keys.getPrivateKey().toBase64(), "vpn.example.com"));
    }

    @Test public void invalidServerFieldsAreRejected() {
        KeyPair keys = new KeyPair();
        ServerState state = new ServerState();
        WireGuardConfigFactory factory = new WireGuardConfigFactory();
        assertThrows(Exception.class, () -> factory.renderClientConfiguration(state, peer(keys, false),
                keys.getPrivateKey().toBase64(), "vpn.example.com"));
        state.serverPublicKey = keys.getPublicKey().toBase64();
        assertThrows(Exception.class, () -> factory.renderClientConfiguration(state, peer(keys, false),
                keys.getPrivateKey().toBase64(), ""));
        for (int port : new int[]{0, -1, 65536}) {
            state.listenPort = port;
            assertThrows(Exception.class, () -> factory.renderClientConfiguration(state, peer(keys, false),
                    keys.getPrivateKey().toBase64(), "vpn.example.com"));
        }
    }

    @Test public void namesAreSanitizedAndRepeatedExportsAreUnique() {
        assertEquals("______x_", ExportFileNames.safePeerName("../../x:"));
        HashSet<String> names = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String name = ExportFileNames.png("phone");
            assertTrue(name.matches("AtticusVPN-phone-[0-9]{8}-[0-9]{6}-[a-f0-9-]{36}\\.png"));
            assertTrue(names.add(name));
        }
    }

    private static StoredPeer peer(KeyPair keys, boolean sealed) {
        return new StoredPeer("11111111111111111111111111111111", "phone", 2,
                keys.getPublicKey().toBase64(), keys.getPrivateKey().toBase64(), true, sealed, 1L, sealed ? 2L : 0L);
    }
}
