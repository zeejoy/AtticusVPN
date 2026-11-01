/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/** 验证域名、IPv4 与带方括号 IPv6 Endpoint 的标准格式和输入拒绝边界。 */
public final class EndpointFormatterTest {
    @Test
    public void formatsHostnameAndIpv4WithoutBrackets() {
        assertEquals(
                "vpn.example.test:51820",
                EndpointFormatter.format("vpn.example.test", 51_820));
        assertEquals("203.0.113.5:123", EndpointFormatter.format("203.0.113.5", 123));
    }

    @Test
    public void bracketsRawIpv6Literal() {
        assertEquals("[2001:db8::10]:51820", EndpointFormatter.format("2001:db8::10", 51_820));
    }

    @Test
    public void rejectsUrlWhitespaceBracketsAndBadPort() {
        assertThrows(
                IllegalArgumentException.class,
                () -> EndpointFormatter.format("https://vpn.example", 51_820));
        assertThrows(
                IllegalArgumentException.class,
                () -> EndpointFormatter.format(" vpn.example", 51_820));
        assertThrows(
                IllegalArgumentException.class,
                () -> EndpointFormatter.format("[2001:db8::1]", 51_820));
        assertThrows(
                IllegalArgumentException.class,
                () -> EndpointFormatter.format("vpn.example", 0));
    }

    @Test
    public void rejectsMalformedNumericAddresses() {
        assertThrows(
                IllegalArgumentException.class,
                () -> EndpointFormatter.format("999.0.0.1", 51_820));
        assertThrows(
                IllegalArgumentException.class,
                () -> EndpointFormatter.format("2001:::bad", 51_820));
    }
}
