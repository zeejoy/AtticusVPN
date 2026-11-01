/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

import com.wireguard.crypto.Key;

import java.util.Arrays;

import org.junit.Test;

/** 验证 repository 写失败所依赖的模型回滚原语不会改变授权顺序或 Seal 状态。 */
public final class PersistenceRollbackModelTest {
    @Test
    public void restoresSealAndDeletedPeerPosition() throws Exception {
        final byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) 9);
        final String key = Key.fromBytes(bytes).toBase64();
        Arrays.fill(bytes, (byte) 0);
        final StoredPeer peer = new StoredPeer(
                "11111111111111111111111111111111", "phone", 2,
                key, key, true, false, 1L, 0L);
        final ServerState state = new ServerState();
        state.addPeer(peer);

        peer.markSealed(10L);
        peer.restoreMutableStateAfterPersistenceFailure(true, false, 0L);
        assertFalse(peer.sealed());

        state.removePeer(peer);
        state.restorePeerAfterPersistenceFailure(0, peer);
        assertSame(peer, state.peers().get(0));
    }
}
