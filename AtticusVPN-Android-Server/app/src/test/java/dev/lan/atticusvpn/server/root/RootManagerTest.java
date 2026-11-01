/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.root;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** 测试替身只用于授权状态分支，测试绝不在开发机器启动 su。 */
public final class RootManagerTest {
    @Test public void discoveryNeverExecutesSuOrClaimsGrant() {
        AtomicInteger calls = new AtomicInteger();
        RootManager manager = new RootManager(command -> {
            calls.incrementAndGet(); return RootCommandExecutor.Result.success("0");
        }, () -> true);
        assertEquals(RootState.ROOT_UNKNOWN, manager.discoverWithoutPrompt());
        assertEquals(0, calls.get());
        assertEquals(RootState.ROOT_GRANTED, manager.requestAuthorization());
        assertEquals(1, calls.get());
        assertEquals(RootState.ROOT_UNKNOWN, manager.discoverWithoutPrompt());
    }

    @Test public void missingSuDoesNotCallRunnerDuringDiscovery() {
        RootManager manager = new RootManager(command -> { throw new AssertionError("must not execute"); }, () -> false);
        assertEquals(RootState.ROOT_NOT_FOUND, manager.discoverWithoutPrompt());
        assertFalse(manager.executeReadOnly(RootCommandExecutor.Command.XFRM_STATE).succeeded());
    }

    @Test public void onlyExactZeroGrantsRoot() {
        for (String uid : new String[]{"2000", "10000", "", "uid=0", "0\nwarning", "00", " 0 "}) {
            RootManager manager = new RootManager(command -> RootCommandExecutor.Result.success(uid), () -> true);
            assertEquals(uid, RootState.ROOT_ERROR, manager.requestAuthorization());
        }
        RootManager manager = new RootManager(command -> RootCommandExecutor.Result.success("0"), () -> true);
        assertEquals(RootState.ROOT_GRANTED, manager.requestAuthorization());
    }

    @Test public void denialAndOtherFailuresRemainDistinctAndCanRetry() {
        AtomicReference<RootCommandExecutor.Result> result = new AtomicReference<>(
                RootCommandExecutor.Result.failure(RootCommandExecutor.Status.DENIED));
        RootManager manager = new RootManager(command -> result.get(), () -> true);
        assertEquals(RootState.ROOT_PERMISSION_DENIED, manager.requestAuthorization());
        result.set(RootCommandExecutor.Result.failure(RootCommandExecutor.Status.TIMEOUT));
        assertEquals(RootState.ROOT_ERROR, manager.requestAuthorization());
        result.set(RootCommandExecutor.Result.success("0"));
        assertEquals(RootState.ROOT_GRANTED, manager.requestAuthorization());
        result.set(RootCommandExecutor.Result.failure(RootCommandExecutor.Status.NOT_FOUND));
        assertEquals(RootState.ROOT_NOT_FOUND, manager.requestAuthorization());
    }

    @Test public void executorFailureDoesNotThrowIntoUi() {
        RootManager manager = new RootManager(command -> { throw new IllegalStateException("internal details"); }, () -> true);
        assertEquals(RootState.ROOT_ERROR, manager.requestAuthorization());
    }

    @Test public void independentManagerNeverInheritsPriorAuthorization() {
        RootManager first = new RootManager(command -> RootCommandExecutor.Result.success("0"), () -> true);
        first.requestAuthorization();
        RootManager second = new RootManager(command -> RootCommandExecutor.Result.success("0"), () -> true);
        assertEquals(RootState.ROOT_UNKNOWN, second.state());
    }

    @Test public void revocationStopsFurtherSuCallsWithoutClearingConfiguration() {
        AtomicInteger calls = new AtomicInteger();
        RootManager manager = new RootManager(command -> {
            calls.incrementAndGet();
            return command == RootCommandExecutor.Command.UID ? RootCommandExecutor.Result.success("0")
                    : RootCommandExecutor.Result.failure(RootCommandExecutor.Status.DENIED);
        }, () -> true);
        manager.requestAuthorization();
        manager.executeReadOnly(RootCommandExecutor.Command.XFRM_STATE);
        assertEquals(RootState.ROOT_PERMISSION_DENIED, manager.state());
        manager.executeReadOnly(RootCommandExecutor.Command.XFRM_POLICY);
        assertEquals(2, calls.get());
    }

    @Test public void commandFailureWithVerifiedIdentityDoesNotClaimRevocation() {
        RootManager manager = new RootManager(command -> command == RootCommandExecutor.Command.UID
                ? RootCommandExecutor.Result.success("0") : RootCommandExecutor.Result.failure(RootCommandExecutor.Status.FAILED), () -> true);
        manager.requestAuthorization();
        manager.executeReadOnly(RootCommandExecutor.Command.XFRM_STATE);
        assertEquals(RootState.ROOT_GRANTED, manager.state());
    }
}
