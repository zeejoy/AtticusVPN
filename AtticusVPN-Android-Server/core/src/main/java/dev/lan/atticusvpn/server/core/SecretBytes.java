/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.core;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * 明确拥有并可销毁的一段敏感字节。
 *
 * <p>构造和读取都复制数据，避免调用方意外共享内部数组。调用方取得的副本也
 * 必须在使用后自行清零。该类的 {@link #toString()} 永远不显示内容。</p>
 */
public final class SecretBytes implements AutoCloseable {
    private byte[] value;

    private SecretBytes(final byte[] value, final boolean takeOwnership) {
        Objects.requireNonNull(value, "value");
        this.value = takeOwnership ? value : value.clone();
    }

    public static SecretBytes copyOf(final byte[] value) {
        return new SecretBytes(value, false);
    }

    public static SecretBytes fromAscii(final String value) {
        Objects.requireNonNull(value, "value");
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) > 0x7f) {
                throw new IllegalArgumentException("secret must be ASCII");
            }
        }
        return new SecretBytes(value.getBytes(StandardCharsets.US_ASCII), true);
    }

    static SecretBytes takeOwnership(final byte[] value) {
        return new SecretBytes(value, true);
    }

    public synchronized int length() {
        requireAvailable();
        return value.length;
    }

    public synchronized boolean isDestroyed() {
        return value == null;
    }

    public synchronized byte[] copy() {
        requireAvailable();
        return value.clone();
    }

    public synchronized SecretBytes duplicate() {
        requireAvailable();
        return new SecretBytes(value, false);
    }

    @Override
    public synchronized void close() {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
            value = null;
        }
    }

    @Override
    public synchronized String toString() {
        return value == null
                ? "SecretBytes[destroyed]"
                : "SecretBytes[length=" + value.length + "]";
    }

    private void requireAvailable() {
        if (value == null) {
            throw new IllegalStateException("secret has been destroyed");
        }
    }
}
