/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.core;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/** WireGuard 32-byte key 的标准 Base64 形状检查；不实现或替代 WireGuard 密码学。 */
final class WireGuardKeyFormat {
    static final int BASE64_LENGTH = 44;

    private WireGuardKeyFormat() {
    }

    static String requirePublicKey(final String value) {
        Objects.requireNonNull(value, "value");
        final byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        try {
            requireKeyBytes(bytes);
            return value;
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    static void requireSecretKey(final SecretBytes value) {
        Objects.requireNonNull(value, "value");
        final byte[] bytes = value.copy();
        try {
            requireKeyBytes(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static void requireKeyBytes(final byte[] bytes) {
        if (bytes.length != BASE64_LENGTH || bytes[BASE64_LENGTH - 1] != '=') {
            throw new IllegalArgumentException("invalid WireGuard key material");
        }
        for (int index = 0; index < BASE64_LENGTH - 1; index++) {
            final int value = Byte.toUnsignedInt(bytes[index]);
            final boolean allowed = value >= 'A' && value <= 'Z'
                    || value >= 'a' && value <= 'z'
                    || value >= '0' && value <= '9'
                    || value == '+'
                    || value == '/';
            if (!allowed) {
                throw new IllegalArgumentException("invalid WireGuard key material");
            }
        }

        byte[] decoded = null;
        try {
            decoded = Base64.getDecoder().decode(bytes);
            if (decoded.length != 32) {
                throw new IllegalArgumentException("invalid WireGuard key material");
            }
        } catch (final IllegalArgumentException ignored) {
            throw new IllegalArgumentException("invalid WireGuard key material");
        } finally {
            if (decoded != null) {
                Arrays.fill(decoded, (byte) 0);
            }
        }
    }
}
