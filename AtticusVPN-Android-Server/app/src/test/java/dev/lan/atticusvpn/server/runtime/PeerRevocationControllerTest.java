/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.runtime;

import dev.lan.atticusvpn.server.storage.SecretCleanupScheduler;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public final class PeerRevocationControllerTest {
    private final List<String> order = new ArrayList<>();
    private boolean syncFails;
    private boolean stopFails;
    private final PeerRevocationController.Runtime runtime = new PeerRevocationController.Runtime() {
        @Override public void synchronizeAuthorization() throws Exception {
            order.add("sync");
            if (syncFails) throw new Exception("PRIVATE_PROVIDER_DETAILS");
        }
        @Override public void stopService() throws Exception {
            order.add("stop");
            if (stopFails) throw new Exception("STOP_UNCONFIRMED");
        }
    };

    @Test public void acknowledgesRuntimeBeforeIndependentCleanup() throws Exception {
        PeerRevocationController c = new PeerRevocationController(id -> order.add("commit"),
                runtime, () -> order.add("cleanup-request"));
        assertEquals(PeerRevocationController.Result.APPLIED, c.revoke("test-peer"));
        assertEquals(List.of("commit", "sync", "cleanup-request"), order);
    }

    @Test public void cleanupFailureCannotSkipRuntimeOrRestoreAuthorization() throws Exception {
        List<Runnable> queued = new ArrayList<>();
        boolean[] revoked = {false};
        SecretCleanupScheduler cleanup = new SecretCleanupScheduler(
                () -> { order.add("cleanup-failed"); throw new Exception("SECRET_PROVIDER_TEXT"); },
                (action, delay) -> queued.add(action), code -> assertFalse(code.contains("SECRET")));
        PeerRevocationController c = new PeerRevocationController(id -> {
            revoked[0] = true; order.add("commit");
        }, runtime, cleanup::request);
        assertEquals(PeerRevocationController.Result.APPLIED, c.revoke("test-peer"));
        queued.remove(0).run();
        assertTrue(revoked[0]);
        assertEquals(List.of("commit", "sync", "cleanup-failed"), order);
        assertEquals(1, queued.size()); // Safe retry, no authorization callback.
    }

    @Test public void reloadFailureStopsBeforeCleanupAndNeverReportsApplied() throws Exception {
        syncFails = true;
        PeerRevocationController c = new PeerRevocationController(id -> order.add("commit"),
                runtime, () -> order.add("cleanup-request"));
        assertEquals(PeerRevocationController.Result.SERVICE_STOPPED, c.revoke("test-peer"));
        assertEquals(List.of("commit", "sync", "stop", "cleanup-request"), order);
    }

    @Test public void persistenceFailureDoesNotAcknowledgeRevocation() {
        PeerRevocationController c = new PeerRevocationController(id -> {
            order.add("persist-failed"); throw new Exception("disk");
        }, runtime, () -> order.add("cleanup-request"));
        assertThrows(Exception.class, () -> c.revoke("test-peer"));
        assertEquals(List.of("persist-failed"), order);
    }

    @Test public void unconfirmedStopIsNotReportedAsSafe() {
        syncFails = true; stopFails = true;
        PeerRevocationController c = new PeerRevocationController(id -> order.add("commit"),
                runtime, () -> order.add("cleanup-request"));
        assertThrows(Exception.class, () -> c.revoke("test-peer"));
        assertEquals(List.of("commit", "sync", "stop", "cleanup-request"), order);
    }
}
