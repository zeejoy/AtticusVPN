/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.core;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 按 Linux 服务器相同规则，从 2..254 分配首个空闲 client id。 */
public final class AddressAllocator {
    public static final int FIRST_CLIENT_ID = 2;
    public static final int LAST_CLIENT_ID = 254;

    public Assignment allocate(final Collection<Integer> usedClientIds) {
        Objects.requireNonNull(usedClientIds, "usedClientIds");
        final Set<Integer> used = new HashSet<>();
        for (final Integer id : usedClientIds) {
            if (id == null || id < FIRST_CLIENT_ID || id > LAST_CLIENT_ID || !used.add(id)) {
                throw new IllegalArgumentException("invalid or duplicate allocated client id");
            }
        }

        for (int id = FIRST_CLIENT_ID; id <= LAST_CLIENT_ID; id++) {
            if (!used.contains(id)) {
                return forId(id);
            }
        }
        throw new IllegalStateException("client address pool is full");
    }

    public Assignment allocateFromPeers(final Collection<PeerRecord> peers) {
        Objects.requireNonNull(peers, "peers");
        return allocate(peers.stream().map(PeerRecord::clientId).collect(Collectors.toList()));
    }

    public static Assignment forId(final int id) {
        if (id < FIRST_CLIENT_ID || id > LAST_CLIENT_ID) {
            throw new IllegalArgumentException("client id must be between 2 and 254");
        }
        return new Assignment(
                id,
                ServerSettings.CLIENT_IPV4_PREFIX + '.' + id + "/32",
                ServerSettings.CLIENT_IPV6_PREFIX + Integer.toHexString(id) + "/128");
    }

    /** 客户端 id 以及由它唯一推导出的双栈隧道地址。 */
    public record Assignment(int clientId, String ipv4Cidr, String ipv6Cidr) {
        public Assignment {
            if (clientId < FIRST_CLIENT_ID || clientId > LAST_CLIENT_ID) {
                throw new IllegalArgumentException("invalid client id");
            }
            Objects.requireNonNull(ipv4Cidr, "ipv4Cidr");
            Objects.requireNonNull(ipv6Cidr, "ipv6Cidr");
        }
    }
}
