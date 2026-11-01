/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.core;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 一个受权 WireGuard peer 的服务端状态。
 *
 * <p>PSK 在 seal 后仍是服务端运行配置所必需，因此会保留；客户端私钥只在
 * ACTIVE 阶段存在。任何字符串表示都不会包含公钥、PSK 或私钥。</p>
 */
public final class PeerRecord implements AutoCloseable {
    private final String name;
    private final int clientId;
    private final String clientIpv4Cidr;
    private final String clientIpv6Cidr;
    private final TunnelMode mode;
    private final List<String> lanRoutes;
    private final String clientPublicKey;
    private final SecretBytes presharedKey;
    private final Instant createdAt;

    private SecretBytes clientPrivateKey;
    private PeerState state;
    private Instant sealedAt;
    private boolean closed;

    private PeerRecord(
            final String name,
            final int clientId,
            final TunnelMode mode,
            final List<String> lanRoutes,
            final String clientPublicKey,
            final SecretBytes presharedKey,
            final SecretBytes clientPrivateKey,
            final PeerState state,
            final Instant createdAt,
            final Instant sealedAt) {
        this.name = CrossClientNamePolicy.requireValid(name);
        final AddressAllocator.Assignment assignment = AddressAllocator.forId(clientId);
        this.clientId = clientId;
        this.clientIpv4Cidr = assignment.ipv4Cidr();
        this.clientIpv6Cidr = assignment.ipv6Cidr();
        this.mode = Objects.requireNonNull(mode, "mode");
        final List<String> checkedRoutes = AllowedIpsPolicy.normalizeLanRoutes(lanRoutes);
        if (mode == TunnelMode.FULL && !checkedRoutes.isEmpty()) {
            throw new IllegalArgumentException("FULL peers cannot contain LAN-only routes");
        }
        this.lanRoutes = checkedRoutes;
        this.clientPublicKey = WireGuardKeyFormat.requirePublicKey(clientPublicKey);
        WireGuardKeyFormat.requireSecretKey(presharedKey);
        this.presharedKey = presharedKey.duplicate();
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.state = Objects.requireNonNull(state, "state");

        if (state == PeerState.ACTIVE) {
            Objects.requireNonNull(clientPrivateKey, "clientPrivateKey");
            WireGuardKeyFormat.requireSecretKey(clientPrivateKey);
            if (sealedAt != null) {
                throw new IllegalArgumentException("ACTIVE peer cannot have sealedAt");
            }
            this.clientPrivateKey = clientPrivateKey.duplicate();
            this.sealedAt = null;
        } else {
            if (clientPrivateKey != null || sealedAt == null) {
                throw new IllegalArgumentException("SEALED peer must omit private key and include sealedAt");
            }
            this.clientPrivateKey = null;
            this.sealedAt = sealedAt;
        }
    }

    public static PeerRecord active(
            final String name,
            final int clientId,
            final TunnelMode mode,
            final List<String> lanRoutes,
            final String clientPublicKey,
            final SecretBytes presharedKey,
            final SecretBytes clientPrivateKey,
            final Instant createdAt) {
        return new PeerRecord(
                name,
                clientId,
                mode,
                lanRoutes,
                clientPublicKey,
                presharedKey,
                clientPrivateKey,
                PeerState.ACTIVE,
                createdAt,
                null);
    }

    public static PeerRecord sealed(
            final String name,
            final int clientId,
            final TunnelMode mode,
            final List<String> lanRoutes,
            final String clientPublicKey,
            final SecretBytes presharedKey,
            final Instant createdAt,
            final Instant sealedAt) {
        return new PeerRecord(
                name,
                clientId,
                mode,
                lanRoutes,
                clientPublicKey,
                presharedKey,
                null,
                PeerState.SEALED,
                createdAt,
                sealedAt);
    }

    public String name() {
        return name;
    }

    public int clientId() {
        return clientId;
    }

    public String clientIpv4Cidr() {
        return clientIpv4Cidr;
    }

    public String clientIpv6Cidr() {
        return clientIpv6Cidr;
    }

    public TunnelMode mode() {
        return mode;
    }

    public List<String> lanRoutes() {
        return lanRoutes;
    }

    public String clientPublicKey() {
        return clientPublicKey;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public synchronized PeerState state() {
        return state;
    }

    public synchronized Instant sealedAt() {
        return sealedAt;
    }

    public synchronized boolean isExportable() {
        return !closed && state == PeerState.ACTIVE && clientPrivateKey != null;
    }

    public synchronized SecretBytes copyClientPrivateKey() {
        requireOpen();
        if (state != PeerState.ACTIVE || clientPrivateKey == null) {
            throw new IllegalStateException("sealed peer has no exportable private key");
        }
        return clientPrivateKey.duplicate();
    }

    public synchronized SecretBytes copyPresharedKey() {
        requireOpen();
        return presharedKey.duplicate();
    }

    synchronized void markSealed(final Instant at) {
        requireOpen();
        if (state == PeerState.SEALED) {
            return;
        }
        sealedAt = Objects.requireNonNull(at, "at");
        state = PeerState.SEALED;
        if (clientPrivateKey != null) {
            clientPrivateKey.close();
            clientPrivateKey = null;
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        if (clientPrivateKey != null) {
            clientPrivateKey.close();
            clientPrivateKey = null;
        }
        presharedKey.close();
        closed = true;
    }

    @Override
    public synchronized String toString() {
        return "PeerRecord[name=" + name
                + ", clientId=" + clientId
                + ", state=" + state
                + ", mode=" + mode + ']';
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("peer record has been closed");
        }
    }
}
