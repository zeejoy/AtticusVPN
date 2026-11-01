/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.core;

import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/** 为纯 JVM 测试生成固定的非真实密钥，避免任何生产凭据进入测试源码。 */
final class CoreTestFixtures {
    static final Instant CREATED = Instant.parse("2026-08-23T00:00:00Z");
    static final String SERVER_PUBLIC_KEY = key(9);

    private CoreTestFixtures() {
    }

    static String key(final int value) {
        final byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        try {
            return Base64.getEncoder().encodeToString(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    static SecretBytes secretKey(final int value) {
        return SecretBytes.fromAscii(key(value));
    }

    static PeerRecord activePeer(final String name, final int id, final TunnelMode mode) {
        try (SecretBytes privateKey = secretKey(id);
             SecretBytes presharedKey = secretKey(id + 1)) {
            return PeerRecord.active(
                    name,
                    id,
                    mode,
                    mode == TunnelMode.LAN ? List.of("192.168.50.0/24") : List.of(),
                    key(id + 2),
                    presharedKey,
                    privateKey,
                    CREATED);
        }
    }
}
