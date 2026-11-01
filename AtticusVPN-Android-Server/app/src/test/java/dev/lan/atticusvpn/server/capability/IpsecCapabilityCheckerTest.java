/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.capability;

import dev.lan.atticusvpn.server.root.RootCommandExecutor;
import dev.lan.atticusvpn.server.root.RootManager;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** 这里只验证证据到状态的映射；替身不出现在生产路径，不证明真实 IPsec 可运行。 */
public final class IpsecCapabilityCheckerTest {
    @Test public void unapprovedRootNeverExecutesCapabilities() {
        AtomicInteger calls = new AtomicInteger();
        RootManager root = new RootManager(command -> {
            calls.incrementAndGet(); return RootCommandExecutor.Result.success("0");
        }, () -> true);
        IpsecCapabilityChecker.Result result = new IpsecCapabilityChecker(root).check();
        assertEquals(0, calls.get());
        assertEquals(KernelCapability.Evidence.NOT_CHECKED, result.kernel().xfrmStat());
        assertFalse(result.readChecksPassed()); assertFalse(result.dataPlaneVerified());
    }

    @Test public void successfulReadsNeverClaimWorkingDataPlane() {
        RootManager root = authorized(command -> {
            if (command == RootCommandExecutor.Command.UDP_PORTS) return RootCommandExecutor.Result.success("NO_CONFLICT_OBSERVED");
            if (command == RootCommandExecutor.Command.IPV4_FORWARDING
                    || command == RootCommandExecutor.Command.IPV6_FORWARDING) return RootCommandExecutor.Result.success("1");
            return RootCommandExecutor.Result.success("READ_CHECK_PASSED");
        });
        IpsecCapabilityChecker.Result result = new IpsecCapabilityChecker(root).check();
        assertTrue(result.readChecksPassed()); assertFalse(result.dataPlaneVerified());
        assertFalse(result.kernel().dataPlaneVerified());
        assertEquals(IpsecCapabilityChecker.PortObservation.NO_CONFLICT_OBSERVED, result.ports());
        assertEquals(IpsecCapabilityChecker.ForwardingObservation.ENABLED, result.ipv6Forwarding());
        assertTrue(result.summary().contains("尚未验证"));
    }

    @Test public void commandFailureRemainsUnknownNotPermanentlyUnsupported() {
        RootManager root = authorized(command -> RootCommandExecutor.Result.failure(RootCommandExecutor.Status.FAILED));
        IpsecCapabilityChecker.Result result = new IpsecCapabilityChecker(root).check();
        assertFalse(result.readChecksPassed());
        assertEquals(KernelCapability.Evidence.FAILED_OR_UNAVAILABLE, result.kernel().xfrmState());
        assertEquals(IpsecCapabilityChecker.PortObservation.UNKNOWN, result.ports());
        assertTrue(result.summary().contains("不能据此"));
    }

    @Test public void conflictsDoNotChangeOrStopOtherServices() {
        RootManager root = authorized(command -> command == RootCommandExecutor.Command.UDP_PORTS
                ? RootCommandExecutor.Result.success("BUSY_4500") : RootCommandExecutor.Result.success("READ_CHECK_PASSED"));
        IpsecCapabilityChecker.Result result = new IpsecCapabilityChecker(root).check();
        assertTrue(result.ports().conflictObserved());
        assertTrue(result.summary().contains("端口占用"));
        assertFalse(result.dataPlaneVerified());
    }

    @Test public void interruptedChecksDoNotExecuteFurtherCommands() {
        AtomicInteger calls = new AtomicInteger();
        RootManager root = authorized(command -> { calls.incrementAndGet(); return RootCommandExecutor.Result.success("READ_CHECK_PASSED"); });
        try {
            Thread.currentThread().interrupt();
            assertFalse(new IpsecCapabilityChecker(root).check().readChecksPassed());
            assertEquals(0, calls.get());
        } finally { Thread.interrupted(); }
    }

    private static RootManager authorized(RootManager.CommandRunner capabilities) {
        RootManager root = new RootManager(command -> command == RootCommandExecutor.Command.UID
                ? RootCommandExecutor.Result.success("0") : capabilities.execute(command), () -> true);
        root.requestAuthorization();
        return root;
    }
}
