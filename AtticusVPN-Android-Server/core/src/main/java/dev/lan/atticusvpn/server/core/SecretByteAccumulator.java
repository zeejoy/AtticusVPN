/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.core;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** 只供配置渲染使用、扩容时会清零旧缓冲区的敏感字节构建器。 */
final class SecretByteAccumulator implements AutoCloseable {
    private byte[] buffer = new byte[512];
    private int size;
    private boolean closed;

    void appendAscii(final String text) {
        if (closed) {
            throw new IllegalStateException("accumulator is closed");
        }
        final byte[] encoded = text.getBytes(StandardCharsets.US_ASCII);
        try {
            append(encoded);
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    void appendSecret(final SecretBytes secret) {
        final byte[] copy = secret.copy();
        try {
            append(copy);
        } finally {
            Arrays.fill(copy, (byte) 0);
        }
    }

    int size() {
        return size;
    }

    SecretBytes finish() {
        if (closed) {
            throw new IllegalStateException("accumulator is closed");
        }
        final byte[] result = Arrays.copyOf(buffer, size);
        close();
        return SecretBytes.takeOwnership(result);
    }

    @Override
    public void close() {
        if (!closed) {
            Arrays.fill(buffer, (byte) 0);
            buffer = new byte[0];
            size = 0;
            closed = true;
        }
    }

    private void append(final byte[] bytes) {
        ensureCapacity(size + bytes.length);
        System.arraycopy(bytes, 0, buffer, size, bytes.length);
        size += bytes.length;
    }

    private void ensureCapacity(final int required) {
        if (required <= buffer.length) {
            return;
        }
        int capacity = buffer.length;
        while (capacity < required) {
            capacity = Math.multiplyExact(capacity, 2);
        }
        final byte[] replacement = Arrays.copyOf(buffer, capacity);
        Arrays.fill(buffer, (byte) 0);
        buffer = replacement;
    }
}
