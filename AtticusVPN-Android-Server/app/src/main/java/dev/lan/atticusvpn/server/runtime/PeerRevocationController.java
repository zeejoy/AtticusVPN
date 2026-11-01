/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.runtime;

/** Durable revocation, runtime acknowledgement, and deferred cleanup are separate steps. */
public final class PeerRevocationController {
    public interface Authorization { void revoke(String id) throws Exception; }
    public interface Runtime {
        /** Returns only after the current authorization is applied, or no engine is running. */
        void synchronizeAuthorization() throws Exception;
        /** Must close the data plane; an implementation must not silently ignore stop failure. */
        void stopService() throws Exception;
    }
    public interface Cleanup { void request(); }
    public enum Result { APPLIED, SERVICE_STOPPED }

    private final Authorization authorization;
    private final Runtime runtime;
    private final Cleanup cleanup;

    public PeerRevocationController(Authorization authorization, Runtime runtime, Cleanup cleanup) {
        this.authorization = authorization;
        this.runtime = runtime;
        this.cleanup = cleanup;
    }

    public Result revoke(String id) throws Exception {
        authorization.revoke(id); // A persistence failure must not be reported as a revocation.
        try {
            try {
                runtime.synchronizeAuthorization();
                return Result.APPLIED;
            } catch (Exception failure) {
                runtime.stopService();
                return Result.SERVICE_STOPPED;
            }
        } finally {
            // Cleanup is never on the authorization/runtime success path.
            cleanup.request();
        }
    }
}
