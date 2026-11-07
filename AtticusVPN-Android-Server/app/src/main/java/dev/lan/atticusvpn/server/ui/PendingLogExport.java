/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Bounded, RAM-only frozen log export. Elapsed time avoids wall-clock changes. */
final class PendingLogExport {
    static final int MAX_BYTES = 192 * 1024;
    static final long TTL_MILLIS = 60_000L;
    private byte[] bytes;
    private long createdAt;

    synchronized void capture(String text, long elapsedMillis) {
        clear();
        if (text == null || text.length() > MAX_BYTES || elapsedMillis < 0L) {
            throw new IllegalArgumentException("RUNTIME_LOG_EXPORT_LIMIT");
        }
        byte[] value = text.getBytes(StandardCharsets.UTF_8);
        if (value.length > MAX_BYTES) {
            Arrays.fill(value, (byte) 0);
            throw new IllegalArgumentException("RUNTIME_LOG_EXPORT_LIMIT");
        }
        bytes = value;
        createdAt = elapsedMillis;
    }

    /** Ownership transfers to the caller, who must wipe it after writing. */
    synchronized byte[] take(long elapsedMillis) {
        if (bytes == null) return null;
        if (elapsedMillis < createdAt || elapsedMillis - createdAt >= TTL_MILLIS) {
            clear();
            return null;
        }
        byte[] value = bytes;
        bytes = null;
        createdAt = 0L;
        return value;
    }

    synchronized void clear() {
        if (bytes != null) Arrays.fill(bytes, (byte) 0);
        bytes = null;
        createdAt = 0L;
    }
}
