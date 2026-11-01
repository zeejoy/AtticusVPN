/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.core;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 把名称、地址分配与外部 WireGuard 密钥生成器组合成新的 ACTIVE peer。
 *
 * <p>本类不自行实现密码学。Android adapter 必须用固定版本的官方 WireGuard
 * KeyPair/Key API 提供三个标准 key。</p>
 */
public final class PeerProvisioner {
    private final KeyMaterialGenerator keyMaterialGenerator;
    private final AddressAllocator addressAllocator;
    private final Clock clock;

    public PeerProvisioner(final KeyMaterialGenerator keyMaterialGenerator) {
        this(keyMaterialGenerator, new AddressAllocator(), Clock.systemUTC());
    }

    public PeerProvisioner(
            final KeyMaterialGenerator keyMaterialGenerator,
            final AddressAllocator addressAllocator,
            final Clock clock) {
        this.keyMaterialGenerator = Objects.requireNonNull(
                keyMaterialGenerator,
                "keyMaterialGenerator");
        this.addressAllocator = Objects.requireNonNull(addressAllocator, "addressAllocator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public PeerRecord provision(
            final String name,
            final TunnelMode mode,
            final Collection<PeerRecord> existingPeers,
            final Collection<String> lanRoutes) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(existingPeers, "existingPeers");
        Objects.requireNonNull(lanRoutes, "lanRoutes");
        CrossClientNamePolicy.requireNew(
                name,
                existingPeers.stream().map(PeerRecord::name).collect(Collectors.toList()));

        final List<String> checkedRoutes = AllowedIpsPolicy.normalizeLanRoutes(lanRoutes);
        if (mode == TunnelMode.FULL && !checkedRoutes.isEmpty()) {
            throw new IllegalArgumentException("FULL peers cannot contain LAN-only routes");
        }
        final AddressAllocator.Assignment assignment =
                addressAllocator.allocateFromPeers(existingPeers);

        try (KeyMaterial material = Objects.requireNonNull(
                keyMaterialGenerator.generate(),
                "generated key material")) {
            WireGuardKeyFormat.requirePublicKey(material.publicKey());
            WireGuardKeyFormat.requireSecretKey(material.privateKey);
            WireGuardKeyFormat.requireSecretKey(material.presharedKey);
            final Instant createdAt = clock.instant();
            return PeerRecord.active(
                    name,
                    assignment.clientId(),
                    mode,
                    checkedRoutes,
                    material.publicKey(),
                    material.presharedKey,
                    material.privateKey,
                    createdAt);
        }
    }

    @FunctionalInterface
    public interface KeyMaterialGenerator {
        KeyMaterial generate();
    }

    /** 一次性 WireGuard 客户端 keypair 与独立 PSK；构造器取得两个 SecretBytes 所有权。 */
    public static final class KeyMaterial implements AutoCloseable {
        private final SecretBytes privateKey;
        private final String publicKey;
        private final SecretBytes presharedKey;

        public KeyMaterial(
                final SecretBytes privateKey,
                final String publicKey,
                final SecretBytes presharedKey) {
            this.privateKey = Objects.requireNonNull(privateKey, "privateKey");
            this.publicKey = Objects.requireNonNull(publicKey, "publicKey");
            this.presharedKey = Objects.requireNonNull(presharedKey, "presharedKey");
        }

        public String publicKey() {
            return publicKey;
        }

        @Override
        public void close() {
            privateKey.close();
            presharedKey.close();
        }

        @Override
        public String toString() {
            return "KeyMaterial[redacted]";
        }
    }
}
