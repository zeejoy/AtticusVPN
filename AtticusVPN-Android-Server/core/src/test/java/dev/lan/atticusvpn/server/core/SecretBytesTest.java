/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 验证秘密字节采用防御性复制，并在生命周期结束时清零内部缓冲。 */
public final class SecretBytesTest {
    @Test
    public void copiesInputAndOutputArrays() {
        final byte[] input = "secret".getBytes(StandardCharsets.US_ASCII);
        try (SecretBytes secret = SecretBytes.copyOf(input)) {
            Arrays.fill(input, (byte) 'x');
            final byte[] first = secret.copy();
            try {
                assertArrayEquals("secret".getBytes(StandardCharsets.US_ASCII), first);
                first[0] = 'X';
                final byte[] second = secret.copy();
                try {
                    assertArrayEquals("secret".getBytes(StandardCharsets.US_ASCII), second);
                } finally {
                    Arrays.fill(second, (byte) 0);
                }
            } finally {
                Arrays.fill(first, (byte) 0);
            }
        } finally {
            Arrays.fill(input, (byte) 0);
        }
    }

    @Test
    public void closeIsIdempotentAndPreventsFurtherCopies() {
        final SecretBytes secret = SecretBytes.fromAscii("secret");
        secret.close();
        secret.close();
        assertTrue(secret.isDestroyed());
        assertThrows(IllegalStateException.class, secret::copy);
    }

    @Test
    public void stringRepresentationNeverContainsSecret() {
        try (SecretBytes secret = SecretBytes.fromAscii("private-value")) {
            assertFalse(secret.toString().contains("private-value"));
        }
    }
}
