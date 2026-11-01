/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.ddns;

import org.junit.Test;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Provider 响应之后的独立 AAAA 解析确认测试。 */
public final class DdnsAaaaVerifierTest {

    /** 只有解析结果真实包含目标 IPv6 时才标记 VERIFIED。 */
    @Test
    public void matchingAaaaIsVerified() throws Exception {
        final Inet6Address target = ipv6("2001:db8::10");
        DdnsAaaaVerifier verifier = new DdnsAaaaVerifier(new AaaaRecordResolver() {
            /** 返回与目标相同的 AAAA。 */
            @Override
            public List<Inet6Address> resolve(String hostname) {
                return Collections.singletonList(target);
            }
        });

        DdnsUpdateResult result = verifier.verify(request(target), accepted());

        assertEquals(DdnsUpdateResult.Status.VERIFIED, result.getStatus());
        assertTrue(result.isDnsVerified());
    }

    /** DNS 仍返回旧地址时必须报告待确认，不能误报已确认。 */
    @Test
    public void staleAaaaIsPendingVerification() throws Exception {
        final Inet6Address stale = ipv6("2001:db8::20");
        DdnsAaaaVerifier verifier = new DdnsAaaaVerifier(new AaaaRecordResolver() {
            /** 模拟传播期间仍可见的旧 AAAA。 */
            @Override
            public List<Inet6Address> resolve(String hostname) {
                return Collections.singletonList(stale);
            }
        });

        DdnsUpdateResult result = verifier.verify(request(ipv6("2001:db8::10")), accepted());

        assertEquals(DdnsUpdateResult.Status.PENDING_VERIFICATION, result.getStatus());
        assertFalse(result.isDnsVerified());
    }

    /** 目标和旧地址并存时仍有客户端命中旧值，因此保持待确认状态。 */
    @Test
    public void mixedAaaaSetIsStillPending() throws Exception {
        final Inet6Address target = ipv6("2001:db8::10");
        final Inet6Address stale = ipv6("2001:db8::20");
        DdnsAaaaVerifier verifier = new DdnsAaaaVerifier(new AaaaRecordResolver() {
            /** 返回传播过程中混合的新旧 AAAA。 */
            @Override
            public List<Inet6Address> resolve(String hostname) {
                return Arrays.asList(target, stale);
            }
        });

        DdnsUpdateResult result = verifier.verify(request(target), accepted());

        assertEquals(DdnsUpdateResult.Status.PENDING_VERIFICATION, result.getStatus());
        assertFalse(result.isDnsVerified());
    }

    /** DNS 查询失败时返回可重试状态，异常中的内部文本不会暴露。 */
    @Test
    public void resolverFailureIsRetryableAndSanitized() throws Exception {
        DdnsAaaaVerifier verifier = new DdnsAaaaVerifier(new AaaaRecordResolver() {
            /** 模拟包含内部地址的解析异常。 */
            @Override
            public List<Inet6Address> resolve(String hostname) throws IOException {
                throw new IOException("resolver https://internal/?token=secret-value");
            }
        });

        DdnsUpdateResult result = verifier.verify(
                request(ipv6("2001:db8::10")), accepted());

        assertEquals(DdnsUpdateResult.Status.RETRYABLE_ERROR, result.getStatus());
        assertFalse(result.toString().contains("secret-value"));
        assertFalse(result.toString().contains("internal"));
    }

    /** Provider 未接受更新时原样返回错误，且不进行无意义的 DNS 查询。 */
    @Test
    public void providerErrorSkipsResolver() throws Exception {
        AtomicBoolean called = new AtomicBoolean(false);
        DdnsAaaaVerifier verifier = new DdnsAaaaVerifier(new AaaaRecordResolver() {
            /** 若被错误调用则记录。 */
            @Override
            public List<Inet6Address> resolve(String hostname) {
                called.set(true);
                return Collections.emptyList();
            }
        });
        DdnsUpdateResult providerError = DdnsUpdateResult.local(
                "test", DdnsUpdateResult.Status.PERMANENT_ERROR, "denied");

        DdnsUpdateResult result = verifier.verify(
                request(ipv6("2001:db8::10")), providerError);

        assertSame(providerError, result);
        assertFalse(called.get());
    }

    /** 创建服务商已接受的固定结果。 */
    private static DdnsUpdateResult accepted() {
        return new DdnsUpdateResult(
                "test", DdnsUpdateResult.Status.UPDATED, 200, "accepted");
    }

    /** 创建使用给定目标地址的请求。 */
    private static DdnsUpdateRequest request(Inet6Address address) {
        return new DdnsUpdateRequest(
                "vpn.example.com", address, DdnsUpdateRequest.Trigger.EVENT);
    }

    /** 将测试常量解析为 IPv6 地址。 */
    private static Inet6Address ipv6(String value) throws Exception {
        return (Inet6Address) InetAddress.getByName(value);
    }
}
