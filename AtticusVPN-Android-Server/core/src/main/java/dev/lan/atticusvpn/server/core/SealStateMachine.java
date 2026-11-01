/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.core;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * 实现“先提交 SEALED 标记，再删除导出材料”的可恢复状态机。
 *
 * <p>持久化删除失败时 peer 仍保持 SEALED，下一次调用会幂等重试清理，绝不会
 * 因为部分删除而重新变成可导出。seal 不撤销公钥、PSK 或隧道地址。</p>
 */
public final class SealStateMachine {
    private final Clock clock;

    public SealStateMachine() {
        this(Clock.systemUTC());
    }

    public SealStateMachine(final Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized Result seal(final PeerRecord peer, final Persistence persistence) {
        Objects.requireNonNull(peer, "peer");
        Objects.requireNonNull(persistence, "persistence");

        if (peer.state() == PeerState.SEALED) {
            // 上次可能在提交标记后、删除密文前中断；每次都重试清理。
            persistence.deleteExportMaterial(peer.name());
            return Result.ALREADY_SEALED;
        }

        final Instant sealedAt = clock.instant();
        // 这是安全不变量：只有持久 SEALED 标记成功后，才进入不可逆删除阶段。
        persistence.markSealed(peer.name(), sealedAt);
        peer.markSealed(sealedAt);
        persistence.deleteExportMaterial(peer.name());
        return Result.SEALED;
    }

    public enum Result {
        SEALED,
        ALREADY_SEALED
    }

    /** Android storage adapter 负责让 markSealed 成为一次原子持久提交。 */
    public interface Persistence {
        void markSealed(String peerName, Instant sealedAt);

        void deleteExportMaterial(String peerName);
    }
}
