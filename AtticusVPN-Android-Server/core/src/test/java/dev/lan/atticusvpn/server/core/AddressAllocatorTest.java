/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.core;

import org.junit.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/** 验证多 Peer 地址分配不会复用地址，并保持 0.1.0 的 IPv4/ULA 规划。 */
public final class AddressAllocatorTest {
    private final AddressAllocator allocator = new AddressAllocator();

    @Test
    public void allocatesFirstAddressAndCompatibleCidrs() {
        final AddressAllocator.Assignment assignment = allocator.allocate(List.of());
        assertEquals(2, assignment.clientId());
        assertEquals("10.77.0.2/32", assignment.ipv4Cidr());
        assertEquals("fd77:6174:7469:6375::2/128", assignment.ipv6Cidr());
    }

    @Test
    public void skipsUsedIdAndUsesHexForIpv6() {
        final AddressAllocator.Assignment assignment = allocator.allocate(
                IntStream.rangeClosed(2, 9).boxed().toList());
        assertEquals(10, assignment.clientId());
        assertEquals("10.77.0.10/32", assignment.ipv4Cidr());
        assertEquals("fd77:6174:7469:6375::a/128", assignment.ipv6Cidr());
    }

    @Test
    public void rejectsDuplicateOrOutOfRangeState() {
        assertThrows(IllegalArgumentException.class, () -> allocator.allocate(List.of(2, 2)));
        assertThrows(IllegalArgumentException.class, () -> allocator.allocate(List.of(1)));
    }

    @Test
    public void reportsExhaustedPool() {
        assertThrows(
                IllegalStateException.class,
                () -> allocator.allocate(
                        IntStream.rangeClosed(2, 254).boxed().toList()));
    }
}
