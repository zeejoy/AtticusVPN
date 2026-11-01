/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.ddns;

import org.junit.Test;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.lan.atticusvpn.server.network.GlobalIpv6Selector;
import dev.lan.atticusvpn.server.network.NetworkSnapshot;

/** 使用纯 Java 地址、Provider 和执行器验证事件调度，不依赖 Android 设备。 */
public final class DdnsUpdateManagerTest {

    /** 首个 GUA 事件会在后台触发一次 Provider 更新，重复地址不会再次触发。 */
    @Test
    public void eventUpdateRunsOnceForSameAddress() throws Exception {
        final Inet6Address address = (Inet6Address) InetAddress.getByName("2001:db8::50");
        CountDownLatch resultLatch = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        DdnsProvider provider = new DdnsProvider() {
            /** 返回安全测试标识。 */
            @Override
            public String getId() {
                return "test";
            }

            /** 记录调用并返回成功。 */
            @Override
            public DdnsUpdateResult update(DdnsUpdateRequest request) {
                calls.incrementAndGet();
                return DdnsUpdateResult.local(
                        getId(), DdnsUpdateResult.Status.UPDATED, "updated");
            }
        };
        ScheduledExecutorService executor = new ScheduledThreadPoolExecutor(1);
        DdnsUpdateManager manager = new DdnsUpdateManager(
                null,
                provider,
                "vpn.example.com",
                SafeDdnsLogger.noOp(),
                new DdnsAaaaVerifier(new AaaaRecordResolver() {
                    /** 立即返回目标 AAAA，使本测试不依赖真实 DNS。 */
                    @Override
                    public java.util.List<Inet6Address> resolve(String hostname) {
                        return java.util.Collections.singletonList(address);
                    }
                }),
                executor,
                new DdnsUpdateManager.MonotonicClock() {
                    /** 返回固定单调时间，足以覆盖首次事件。 */
                    @Override
                    public long nowMillis() {
                        return 1_000L;
                    }
                },
                0L,
                10_000L,
                DdnsUpdateManager.MIN_CONSISTENCY_INTERVAL_MILLIS);
        try {
            manager.start(new DdnsUpdateManager.Listener() {
                /** 释放等待中的测试线程。 */
                @Override
                public void onDdnsResult(DdnsUpdateResult result) {
                    resultLatch.countDown();
                }
            });
            manager.observeEndpoint(address);
            assertTrue(resultLatch.await(3, TimeUnit.SECONDS));
            manager.observeEndpoint(address);
            Thread.sleep(100L);
            assertEquals(1, calls.get());
        } finally {
            manager.close();
        }
    }

    /** RETRYABLE_ERROR 只按 1/2/4 型有限序列重试，不会形成无限循环。 */
    @Test
    public void retryableProviderUsesFiniteBackoff() throws Exception {
        final Inet6Address address = (Inet6Address) InetAddress.getByName("2001:db8::60");
        final CountDownLatch fourResults = new CountDownLatch(4);
        final AtomicInteger calls = new AtomicInteger();
        final ScheduledExecutorService executor = new ScheduledThreadPoolExecutor(1);
        final DdnsUpdateManager manager = newManager(
                address,
                request -> {
                    calls.incrementAndGet();
                    return DdnsUpdateResult.local(
                            "test", DdnsUpdateResult.Status.RETRYABLE_ERROR, "retry");
                },
                executor,
                new AtomicLong(1_000L));
        manager.setProviderRetryDelaysForTest(10L, 20L, 40L);
        try {
            manager.start(result -> fourResults.countDown());
            manager.observeEndpoint(address);
            assertTrue(fourResults.await(2, TimeUnit.SECONDS));
            assertEquals(4, calls.get());
        } finally {
            manager.close();
        }
    }

    /** close 会取消尚未运行的 Provider 重试。 */
    @Test
    public void closeCancelsPendingProviderRetry() throws Exception {
        final Inet6Address address = (Inet6Address) InetAddress.getByName("2001:db8::61");
        final CountDownLatch firstResult = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        final ScheduledExecutorService executor = new ScheduledThreadPoolExecutor(1);
        final DdnsUpdateManager manager = newManager(
                address,
                request -> {
                    calls.incrementAndGet();
                    return DdnsUpdateResult.local(
                            "test", DdnsUpdateResult.Status.RETRYABLE_ERROR, "retry");
                },
                executor,
                new AtomicLong(1_000L));
        manager.setProviderRetryDelaysForTest(5_000L);
        manager.start(result -> firstResult.countDown());
        manager.observeEndpoint(address);
        assertTrue(firstResult.await(2, TimeUnit.SECONDS));
        manager.close();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
    }

    /** 地址变化会取消旧地址的重试，后续请求只绑定最新地址。 */
    @Test
    public void addressChangeCancelsOldAddressRetry() throws Exception {
        final Inet6Address first = (Inet6Address) InetAddress.getByName("2001:db8::62");
        final Inet6Address second = (Inet6Address) InetAddress.getByName("2001:db8::63");
        final CountDownLatch expectedCalls = new CountDownLatch(3);
        final CountDownLatch firstCall = new CountDownLatch(1);
        final java.util.List<String> addresses = java.util.Collections.synchronizedList(
                new java.util.ArrayList<>());
        final AtomicLong clock = new AtomicLong(1_000L);
        final ScheduledExecutorService executor = new ScheduledThreadPoolExecutor(1);
        final DdnsUpdateManager manager = newManager(
                second,
                request -> {
                    addresses.add(request.getAddressText());
                    if (request.getAddressText().equals(first.getHostAddress())) {
                        firstCall.countDown();
                    }
                    expectedCalls.countDown();
                    return DdnsUpdateResult.local(
                            "test", DdnsUpdateResult.Status.RETRYABLE_ERROR, "retry");
                },
                executor,
                clock);
        manager.setProviderRetryDelaysForTest(80L);
        try {
            manager.start(result -> { });
            manager.observeEndpoint(first);
            assertTrue(firstCall.await(2, TimeUnit.SECONDS));
            clock.set(20_000L);
            manager.observeEndpoint(second);
            assertTrue(expectedCalls.await(2, TimeUnit.SECONDS));
            assertEquals(1, java.util.Collections.frequency(
                    addresses, first.getHostAddress()));
            assertEquals(2, java.util.Collections.frequency(
                    addresses, second.getHostAddress()));
        } finally {
            manager.close();
        }
    }

    /** 新网络已生效后，迟到的旧 lost 不得清除新地址或取消其 DDNS 事件。 */
    @Test
    public void staleNetworkLossDoesNotClearNewEndpoint() throws Exception {
        final Inet6Address address = (Inet6Address) InetAddress.getByName("2001:db8::71");
        final GlobalIpv6Selector.Candidate candidate = new GlobalIpv6Selector.Candidate(
                address, 64, false, false, false, false);
        final CountDownLatch update = new CountDownLatch(1);
        final ScheduledExecutorService executor = new ScheduledThreadPoolExecutor(1);
        final DdnsUpdateManager manager = new DdnsUpdateManager(
                null,
                new DdnsProvider() {
                    /** 返回固定测试标识。 */
                    @Override
                    public String getId() {
                        return "test";
                    }

                    /** 证明新网络的去抖任务没有被旧 lost 取消。 */
                    @Override
                    public DdnsUpdateResult update(final DdnsUpdateRequest request) {
                        update.countDown();
                        return DdnsUpdateResult.local(
                                "test", DdnsUpdateResult.Status.UPDATED, "updated");
                    }
                },
                "vpn.example.com",
                SafeDdnsLogger.noOp(),
                new DdnsAaaaVerifier(hostname -> Collections.singletonList(address)),
                executor,
                () -> 1_000L,
                200L,
                10_000L,
                DdnsUpdateManager.MIN_CONSISTENCY_INTERVAL_MILLIS);
        try {
            manager.start(result -> { });
            manager.onNetworkSnapshot(new NetworkSnapshot(
                    2L,
                    NetworkSnapshot.NetworkType.WIFI,
                    Collections.singletonList(candidate),
                    candidate,
                    Collections.emptyList(),
                    true,
                    false));
            manager.onNetworkLost(1L);
            assertTrue(update.await(2, TimeUnit.SECONDS));
        } finally {
            manager.close();
        }
    }

    /** 建立可注入极短退避的纯 Java manager，避免测试访问真实网络或等待分钟级时间。 */
    private static DdnsUpdateManager newManager(
            final Inet6Address expectedAddress,
            final java.util.function.Function<DdnsUpdateRequest, DdnsUpdateResult> update,
            final ScheduledExecutorService executor,
            final AtomicLong clock) {
        final DdnsProvider provider = new DdnsProvider() {
            @Override
            public String getId() {
                return "test";
            }

            @Override
            public DdnsUpdateResult update(final DdnsUpdateRequest request) {
                return update.apply(request);
            }
        };
        return new DdnsUpdateManager(
                null,
                provider,
                "vpn.example.com",
                SafeDdnsLogger.noOp(),
                new DdnsAaaaVerifier(hostname ->
                        java.util.Collections.singletonList(expectedAddress)),
                executor,
                clock::get,
                0L,
                10_000L,
                DdnsUpdateManager.MIN_CONSISTENCY_INTERVAL_MILLIS);
    }
}
