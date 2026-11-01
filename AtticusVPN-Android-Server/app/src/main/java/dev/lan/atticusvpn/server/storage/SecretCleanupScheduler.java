/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.storage;

/** Coalesced, bounded cleanup retries. Only fixed events, never exception text or Peer IDs. */
public final class SecretCleanupScheduler {
    public interface Task { void run() throws Exception; }
    public interface Scheduler { void schedule(Runnable action, long delaySeconds); }
    public interface Events { void record(String fixedCode); }
    private static final long[] DELAYS = {1, 5, 30, 60};
    private final Task task;
    private final Scheduler scheduler;
    private final Events events;
    private long requests;
    private boolean pending;
    private int failures;

    public SecretCleanupScheduler(Task task, Scheduler scheduler, Events events) {
        this.task = task;
        this.scheduler = scheduler;
        this.events = events;
    }

    public synchronized void request() {
        requests++;
        if (!pending) {
            pending = true;
            failures = 0;
            enqueue(0);
        }
    }

    private void enqueue(long delay) {
        try { scheduler.schedule(this::attempt, delay); }
        catch (RuntimeException ignored) {
            pending = false;
            record("KEY_CLEANUP_DEFERRED_UNTIL_RESTART");
        }
    }

    private void record(String code) {
        try { events.record(code); }
        catch (RuntimeException ignored) { /* Diagnostics cannot interfere with revocation. */ }
    }

    private void attempt() {
        final long generation;
        synchronized (this) { generation = requests; }
        boolean success = false;
        try { task.run(); success = true; }
        catch (Exception ignored) { /* Provider messages may contain sensitive information. */ }
        synchronized (this) {
            if (requests != generation) {
                failures = 0; // A request during cleanup must not be lost.
                enqueue(0);
            } else if (success) {
                pending = false;
            } else if (failures < DELAYS.length) {
                record("KEY_CLEANUP_RETRY_SCHEDULED");
                enqueue(DELAYS[failures++]);
            } else {
                pending = false;
                record("KEY_CLEANUP_DEFERRED_UNTIL_RESTART");
            }
        }
    }
}
