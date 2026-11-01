/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.network;

import org.junit.Test;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** GlobalIpv6Selector 的纯 JVM 分类和选择测试。 */
public final class GlobalIpv6SelectorTest {

    /** 确认 GUA、ULA、link-local 和 loopback 不会混淆。 */
    @Test
    public void classifyDistinguishesPublicAndLocalScopes() throws Exception {
        assertEquals(GlobalIpv6Selector.AddressScope.GLOBAL_UNICAST,
                GlobalIpv6Selector.classify(ipv6("2001:db8::1")));
        assertEquals(GlobalIpv6Selector.AddressScope.UNIQUE_LOCAL,
                GlobalIpv6Selector.classify(ipv6("fd12:3456::1")));
        assertEquals(GlobalIpv6Selector.AddressScope.LINK_LOCAL,
                GlobalIpv6Selector.classify(ipv6("fe80::1")));
        assertEquals(GlobalIpv6Selector.AddressScope.LOOPBACK,
                GlobalIpv6Selector.classify(ipv6("::1")));
    }

    /** 同时存在稳定和临时 GUA 时优先稳定地址。 */
    @Test
    public void stableGlobalAddressBeatsTemporaryAddress() throws Exception {
        GlobalIpv6Selector.Candidate temporary = candidate("2001:db8::1", 64, true,
                false, false, false);
        GlobalIpv6Selector.Candidate stable = candidate("2001:db8::2", 64, false,
                false, false, false);

        Optional<GlobalIpv6Selector.Candidate> selected =
                new GlobalIpv6Selector().selectEndpoint(Arrays.asList(temporary, stable));

        assertTrue(selected.isPresent());
        assertEquals(stable.getAddress(), selected.get().getAddress());
    }

    /** 只有健康临时 GUA 时允许其作为后备 Endpoint。 */
    @Test
    public void temporaryGlobalAddressIsFallback() throws Exception {
        GlobalIpv6Selector.Candidate temporary = candidate("2001:db8::3", 64, true,
                false, false, false);
        Optional<GlobalIpv6Selector.Candidate> selected =
                new GlobalIpv6Selector().selectEndpoint(Collections.singletonList(temporary));
        assertTrue(selected.isPresent());
        assertEquals(temporary.getAddress(), selected.get().getAddress());
    }

    /** deprecated、tentative、DAD 失败和 ULA 均不得发布到 DDNS。 */
    @Test
    public void unhealthyAndPrivateAddressesAreRejected() throws Exception {
        GlobalIpv6Selector selector = new GlobalIpv6Selector();
        assertFalse(selector.selectEndpoint(Arrays.asList(
                candidate("2001:db8::1", 64, false, true, false, false),
                candidate("2001:db8::2", 64, false, false, true, false),
                candidate("2001:db8::3", 64, false, false, false, true),
                candidate("fd00::1", 64, false, false, false, false)
        )).isPresent());
    }

    /** 输入顺序变化时仍按完整无符号地址得到确定结果。 */
    @Test
    public void selectionIsDeterministic() throws Exception {
        GlobalIpv6Selector.Candidate high = candidate("2001:db8::20", 64, false,
                false, false, false);
        GlobalIpv6Selector.Candidate low = candidate("2001:db8::10", 64, false,
                false, false, false);
        GlobalIpv6Selector selector = new GlobalIpv6Selector();
        assertEquals(low.getAddress(), selector.selectEndpoint(Arrays.asList(high, low))
                .get().getAddress());
        assertEquals(low.getAddress(), selector.selectEndpoint(Arrays.asList(low, high))
                .get().getAddress());
    }

    /** 米 8 的 0x44/0x45 状态应按 Android 乐观 DAD 语义处理，而非一律拒绝。 */
    @Test
    public void optimisticTentativeIsEligibleFallback() throws Exception {
        GlobalIpv6Selector.Candidate stable = new GlobalIpv6Selector.Candidate(
                ipv6("2409:1234::1"), 64, false, false, true, false, true);
        GlobalIpv6Selector.Candidate temporary = new GlobalIpv6Selector.Candidate(
                ipv6("2409:1234::2"), 64, true, false, true, false, true);
        assertTrue(stable.isEndpointEligible());
        assertTrue(temporary.isEndpointEligible());
        assertEquals(stable.getAddress(), new GlobalIpv6Selector()
                .selectEndpoint(Arrays.asList(temporary, stable)).get().getAddress());
    }

    @Test
    public void completedDadPreferredEvenOverOptimisticStable() throws Exception {
        GlobalIpv6Selector.Candidate optimistic = new GlobalIpv6Selector.Candidate(
                ipv6("2409:1234::1"), 64, false, false, true, false, true);
        GlobalIpv6Selector.Candidate completed = candidate(
                "2409:1234::2", 64, true, false, false, false);
        assertEquals(completed.getAddress(), new GlobalIpv6Selector()
                .selectEndpoint(Arrays.asList(optimistic, completed)).get().getAddress());
    }

    @Test
    public void optimisticNeverOverridesFailedDeprecatedOrPrivateAddresses() throws Exception {
        assertFalse(new GlobalIpv6Selector.Candidate(
                ipv6("2409:1234::1"), 64, false, false, true, true, true).isEndpointEligible());
        assertFalse(new GlobalIpv6Selector.Candidate(
                ipv6("2409:1234::1"), 64, false, true, true, false, true).isEndpointEligible());
        assertFalse(new GlobalIpv6Selector.Candidate(
                ipv6("fd77::1"), 64, false, false, true, false, true).isEndpointEligible());
        assertFalse(new GlobalIpv6Selector.Candidate(
                ipv6("fe80::1"), 64, false, false, true, false, true).isEndpointEligible());
        assertFalse(candidate("2409:1234::1", 64, false, false, true, false).isEndpointEligible());
    }

    /** 创建带状态标志的候选地址。 */
    private static GlobalIpv6Selector.Candidate candidate(
            String value,
            int prefix,
            boolean temporary,
            boolean deprecated,
            boolean tentative,
            boolean dadFailed) throws Exception {
        return new GlobalIpv6Selector.Candidate(
                ipv6(value), prefix, temporary, deprecated, tentative, dadFailed);
    }

    /** 将测试常量解析为 IPv6 地址。 */
    private static Inet6Address ipv6(String value) throws Exception {
        return (Inet6Address) InetAddress.getByName(value);
    }
}
