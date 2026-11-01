/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.model;

import com.wireguard.crypto.Key;
import com.wireguard.crypto.KeyFormatException;

import java.util.Objects;

import dev.lan.atticusvpn.server.core.AddressAllocator;
import dev.lan.atticusvpn.server.core.CrossClientNamePolicy;

/**
 * 可持久化的服务端 Peer 元数据。
 *
 * <p>这里故意没有 client private key；未 Seal 的客户端私钥位于每 Peer 独立
 * Keystore alias 保护的 {@code PeerExportVault}。PSK 是服务端继续认证 Peer 的必需
 * 材料，即使 Seal 后也必须保留。{@link #toString()} 永远不会输出公钥或 PSK。</p>
 */
public final class StoredPeer {
    private final String id;
    private final String name;
    private final int clientId;
    private final String publicKey;
    private final String presharedKey;
    private final long createdAtUnix;
    private boolean enabled;
    private boolean sealed;
    private long sealedAtUnix;

    public StoredPeer(
            final String id,
            final String name,
            final int clientId,
            final String publicKey,
            final String presharedKey,
            final boolean enabled,
            final boolean sealed,
            final long createdAtUnix,
            final long sealedAtUnix) {
        this.id = requireIdentifier(id);
        this.name = CrossClientNamePolicy.requireValid(name);
        AddressAllocator.forId(clientId);
        this.clientId = clientId;
        this.publicKey = requireKey(publicKey);
        this.presharedKey = requireKey(presharedKey);
        this.enabled = enabled;
        this.sealed = sealed;
        this.createdAtUnix = createdAtUnix;
        this.sealedAtUnix = sealed ? Math.max(1L, sealedAtUnix) : 0L;
    }

    private static String requireIdentifier(final String value) {
        Objects.requireNonNull(value, "id");
        if (!value.matches("^[a-f0-9]{32}$")) {
            throw new IllegalArgumentException("invalid peer id");
        }
        return value;
    }

    private static String requireKey(final String value) {
        try {
            Key.fromBase64(Objects.requireNonNull(value, "key"));
            return value;
        } catch (final KeyFormatException exception) {
            throw new IllegalArgumentException("invalid WireGuard key");
        }
    }

    public String id() { return id; }
    public String name() { return name; }
    public int clientId() { return clientId; }
    public String publicKey() { return publicKey; }
    public String presharedKey() { return presharedKey; }
    public boolean enabled() { return enabled; }
    public boolean sealed() { return sealed; }
    public long createdAtUnix() { return createdAtUnix; }
    public long sealedAtUnix() { return sealedAtUnix; }

    public void setEnabled(final boolean value) { enabled = value; }

    public void markSealed(final long unixSeconds) {
        sealed = true;
        sealedAtUnix = Math.max(1L, unixSeconds);
    }

    /**
     * 仅供 repository 在原子持久化失败时恢复内存镜像。
     *
     * <p>正常业务绝不能用它解除 Seal；调用前提是主状态写入未提交，因此磁盘和 vault
     * 仍处于旧状态。把回滚集中于此可避免“内存已 Seal、重启后又可导出”的歧义。</p>
     */
    public void restoreMutableStateAfterPersistenceFailure(
            final boolean previousEnabled,
            final boolean previousSealed,
            final long previousSealedAtUnix) {
        enabled = previousEnabled;
        sealed = previousSealed;
        sealedAtUnix = previousSealed ? Math.max(1L, previousSealedAtUnix) : 0L;
    }

    public String ipv4Cidr() { return AddressAllocator.forId(clientId).ipv4Cidr(); }
    public String ipv6Cidr() { return AddressAllocator.forId(clientId).ipv6Cidr(); }

    @Override
    public String toString() {
        return "StoredPeer[name=" + name + ", clientId=" + clientId
                + ", enabled=" + enabled + ", sealed=" + sealed + ']';
    }
}
