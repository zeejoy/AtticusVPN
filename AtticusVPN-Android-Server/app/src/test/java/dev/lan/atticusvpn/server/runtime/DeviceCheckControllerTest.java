/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.runtime;

import dev.lan.atticusvpn.server.capability.DeviceCapabilitySnapshot;
import dev.lan.atticusvpn.server.capability.IpsecCapabilityChecker;
import dev.lan.atticusvpn.server.root.RootState;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.Test;
import static org.junit.Assert.*;

/** 离线验证 UI 重建不重复请求 Root、命令失败不影响 WireGuard 队列。 */
public final class DeviceCheckControllerTest {
    private static DeviceCapabilitySnapshot snapshot() {
        return new DeviceCapabilitySnapshot(RootState.ROOT_UNKNOWN, "test", "test", "test",
                "unknown", "unknown", IpsecCapabilityChecker.notChecked());
    }
    private static final class Queue implements Executor {
        final List<Runnable> tasks = new ArrayList<>();
        public void execute(Runnable runnable) { tasks.add(runnable); }
        void run() { tasks.remove(0).run(); }
    }
    @Test public void lifecycleInspectsOnlyOnceAndNeverRequestsRoot() {
        final Queue worker = new Queue();
        final List<Boolean> requests = new ArrayList<>();
        final DeviceCheckController controller = new DeviceCheckController(request -> {
            requests.add(request); return snapshot();
        }, worker, Runnable::run);
        controller.inspectOnceWithoutPrompt(); controller.inspectOnceWithoutPrompt();
        assertTrue(controller.current().busy()); assertEquals(1, worker.tasks.size());
        worker.run(); controller.inspectOnceWithoutPrompt();
        assertEquals(java.util.Collections.singletonList(false), requests);
        assertFalse(controller.current().busy()); assertTrue(worker.tasks.isEmpty());
    }
    @Test public void onlyExplicitRequestPromptsAndConcurrentClicksAreIgnored() {
        final Queue worker = new Queue();
        final List<Boolean> requests = new ArrayList<>();
        final DeviceCheckController controller = new DeviceCheckController(request -> {
            requests.add(request); return snapshot();
        }, worker, Runnable::run);
        assertTrue(controller.requestRootAndInspect());
        assertFalse(controller.requestRootAndInspect()); worker.run();
        assertEquals(java.util.Collections.singletonList(true), requests);
        assertTrue(controller.requestRootAndInspect());
    }
    @Test public void failuresDoNotAutomaticallyRetryAndManualRetryIsAllowed() {
        final Queue worker = new Queue();
        final DeviceCheckController controller = new DeviceCheckController(request -> {
            throw new IllegalStateException("not displayed");
        }, worker, Runnable::run);
        controller.inspectOnceWithoutPrompt(); worker.run();
        assertTrue(controller.current().failed()); assertNull(controller.current().snapshot());
        controller.inspectOnceWithoutPrompt(); assertTrue(worker.tasks.isEmpty());
        assertTrue(controller.requestRootAndInspect());
    }
    @Test public void removedActivityDoesNotReceiveQueuedCallbacks() {
        final Queue worker = new Queue(), ui = new Queue();
        final int[] calls = {0};
        final DeviceCheckController controller = new DeviceCheckController(request -> snapshot(), worker, ui);
        final DeviceCheckController.Listener listener = state -> calls[0]++;
        controller.addListener(listener); controller.removeListener(listener); ui.run();
        assertEquals(0, calls[0]);
    }
}
