/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.storage;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public final class SecretCleanupSchedulerTest {
    @Test public void retriesAreBoundedAndOnlyFixedCodesAreRecorded() {
        List<Runnable> queue = new ArrayList<>();
        List<Long> delays = new ArrayList<>();
        List<String> logs = new ArrayList<>();
        int[] attempts = {0};
        SecretCleanupScheduler cleanup = new SecretCleanupScheduler(() -> {
            attempts[0]++; throw new Exception("sensitive-provider-detail");
        }, (action, delay) -> { queue.add(action); delays.add(delay); }, logs::add);
        cleanup.request(); cleanup.request();
        assertEquals(1, queue.size());
        while (!queue.isEmpty()) queue.remove(0).run();
        assertEquals(5, attempts[0]);
        assertEquals(List.of(0L, 1L, 5L, 30L, 60L), delays);
        assertEquals("KEY_CLEANUP_DEFERRED_UNTIL_RESTART", logs.get(logs.size() - 1));
        assertFalse(logs.toString().contains("sensitive-provider-detail"));
        cleanup.request();
        assertEquals(1, queue.size()); // Next request/startup can resume exhausted cleanup.
    }

    @Test public void requestDuringCleanupIsNotLost() {
        List<Runnable> queue = new ArrayList<>();
        SecretCleanupScheduler[] holder = new SecretCleanupScheduler[1];
        int[] runs = {0};
        holder[0] = new SecretCleanupScheduler(() -> {
            if (++runs[0] == 1) holder[0].request();
        }, (action, delay) -> queue.add(action), code -> {});
        holder[0].request();
        queue.remove(0).run();
        assertEquals(1, queue.size());
        queue.remove(0).run();
        assertEquals(2, runs[0]); assertTrue(queue.isEmpty());
    }

    @Test public void schedulingOrLoggingFailureCannotEscapeToRevocation() {
        SecretCleanupScheduler cleanup = new SecretCleanupScheduler(() -> {},
                (action, delay) -> { throw new java.util.concurrent.RejectedExecutionException(); },
                code -> { throw new IllegalStateException(); });
        cleanup.request();
        cleanup.request();
    }

    @Test public void successfulRetryEndsQueue() {
        List<Runnable> queue = new ArrayList<>();
        int[] runs = {0};
        SecretCleanupScheduler cleanup = new SecretCleanupScheduler(() -> {
            if (++runs[0] == 1) throw new Exception();
        }, (action, delay) -> queue.add(action), code -> {});
        cleanup.request(); queue.remove(0).run(); queue.remove(0).run();
        assertEquals(2, runs[0]); assertTrue(queue.isEmpty());
    }
}
