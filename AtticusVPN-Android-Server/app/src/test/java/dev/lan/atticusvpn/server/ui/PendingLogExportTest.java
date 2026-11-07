/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

/** Export boundaries and expiry are checked without Android file-provider mocks. */
public final class PendingLogExportTest {
    @Test public void frozenUtf8SnapshotIsTransferredOnce() {
        PendingLogExport pending = new PendingLogExport();
        String text = "设备 💻 IPv6 [2001:db8::1] / 觀察時間\n";
        pending.capture(text, 100);
        assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), pending.take(101));
        assertNull(pending.take(102));
    }

    @Test public void expirationUsesElapsedTimeIncludingClockRegression() {
        PendingLogExport pending = new PendingLogExport();
        pending.capture("current log", 100);
        assertNotNull(pending.take(100 + PendingLogExport.TTL_MILLIS - 1));
        pending.capture("expired log", 100);
        assertNull(pending.take(100 + PendingLogExport.TTL_MILLIS));
        pending.capture("clock changed", 100);
        assertNull(pending.take(99));
    }

    @Test public void byteLimitRejectsUtf8ExpansionWithoutTruncatingOrKeepingOldValue() {
        PendingLogExport pending = new PendingLogExport();
        String maximum = "x".repeat(PendingLogExport.MAX_BYTES);
        pending.capture(maximum, 10);
        assertEquals(PendingLogExport.MAX_BYTES, pending.take(11).length);
        pending.capture("old snapshot", 20);
        assertThrows(IllegalArgumentException.class, () ->
                pending.capture("汉".repeat(PendingLogExport.MAX_BYTES / 3 + 1), 21));
        assertNull(pending.take(22));
    }

    @Test public void clearAndReplacementCannotExportPreviousSession() {
        PendingLogExport pending = new PendingLogExport();
        pending.capture("first", 100);
        pending.capture("second", 101);
        assertEquals("second", new String(pending.take(102), StandardCharsets.UTF_8));
        pending.capture("third", 103);
        pending.clear();
        assertNull(pending.take(104));
        assertThrows(IllegalArgumentException.class, () -> pending.capture(null, 105));
    }
}
