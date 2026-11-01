/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.runtime;

import android.net.Network;

import org.junit.Test;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** 注入纯离线传输，用同步栅栏验证四站并行、任一成功、DNS 警告与取消资源归属。 */
public final class ServerNetworkProbeConcurrencyTest {
    @Test public void hasFourOfficialSitesAndOnlyMinimalFixedGetRequest() {
        assertArrayEquals(new String[]{"www.cloudflare.com", "www.microsoft.com", "www.baidu.com",
                "www.bilibili.com"}, ServerNetworkProbe.probeHosts());
        final String[] copy = ServerNetworkProbe.probeHosts();
        copy[0] = "changed.invalid";
        assertEquals("www.cloudflare.com", ServerNetworkProbe.probeHosts()[0]);
        for (final String host : ServerNetworkProbe.probeHosts()) {
            assertEquals("GET / HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n",
                    new String(ServerNetworkProbe.httpRequest(host), StandardCharsets.US_ASCII));
        }
    }

    @Test public void eachOfTheFourSitesCanWinWhileAllOtherSitesAreBlocked() throws Exception {
        for (final String winner : ServerNetworkProbe.probeHosts()) {
            final ExecutorService workers = Executors.newFixedThreadPool(6);
            final CountDownLatch entered = new CountDownLatch(4);
            final CountDownLatch finished = new CountDownLatch(4);
            final CountDownLatch block = new CountDownLatch(1);
            try {
                final ServerNetworkProbe probe = probe(workers, new OfflineTransport() {
                    @Override public boolean https(final Network network, final String host,
                                                   final ServerNetworkProbe.Session session) {
                        entered.countDown();
                        try {
                            if (!await(entered)) return false;
                            if (winner.equals(host)) return true;
                            return await(block);
                        } finally { finished.countDown(); }
                    }
                }, 2000L, 30L);
                assertEquals("Only " + winner + " is accessible", NetworkCheckResult.PASSED_DNS_DISABLED,
                        probe.check(null, "!disabled"));
                assertTrue("Losing jobs must be interrupted after the first success",
                        finished.await(1L, TimeUnit.SECONDS));
            } finally { stop(workers); }
        }
    }

    @Test public void blockedDnsCannotDelayOrNegateAConfirmedSite() throws Exception {
        final ExecutorService workers = Executors.newFixedThreadPool(6);
        final CountDownLatch entered = new CountDownLatch(6);
        final CountDownLatch finished = new CountDownLatch(6);
        final CountDownLatch block = new CountDownLatch(1);
        try {
            final ServerNetworkProbe probe = probe(workers, new OfflineTransport() {
                @Override public boolean https(final Network network, final String host,
                                               final ServerNetworkProbe.Session session) {
                    entered.countDown();
                    try { return await(entered) && "www.baidu.com".equals(host); }
                    finally { finished.countDown(); }
                }
                @Override public boolean dns(final Network network, final String server,
                                             final ServerNetworkProbe.Session session) {
                    entered.countDown();
                    try { return await(block); }
                    finally { finished.countDown(); }
                }
            }, 2000L, 30L);
            final long start = System.nanoTime();
            final NetworkCheckResult result = probe.check(null, "1.1.1.1,8.8.8.8");
            assertEquals(NetworkCheckResult.PASSED_DNS_WARNING, result);
            assertTrue(result.isConnectivityConfirmed());
            assertTrue("DNS must not use the entire check deadline", elapsedMillis(start) < 1000L);
            assertTrue(finished.await(1L, TimeUnit.SECONDS));
        } finally { stop(workers); }
    }

    @Test public void dnsSuccessIsIndependentOfWebsiteSuccess() throws Exception {
        final ExecutorService workers = Executors.newFixedThreadPool(6);
        try {
            final ServerNetworkProbe.Transport passed = new OfflineTransport() {
                @Override public boolean https(final Network network, final String host,
                                               final ServerNetworkProbe.Session session) { return true; }
                @Override public boolean dns(final Network network, final String server,
                                             final ServerNetworkProbe.Session session) { return true; }
            };
            assertEquals(NetworkCheckResult.PASSED, probe(workers, passed, 2000L, 1000L)
                    .check(null, "1.1.1.1"));
            final ServerNetworkProbe.Transport websitesFailed = new OfflineTransport() {
                @Override public boolean dns(final Network network, final String server,
                                             final ServerNetworkProbe.Session session) { return true; }
            };
            final NetworkCheckResult failed = probe(workers, websitesFailed, 2000L, 30L)
                    .check(null, "1.1.1.1");
            assertEquals(NetworkCheckResult.HTTPS_FAILED, failed);
            assertFalse(failed.isConnectivityConfirmed());
        } finally { stop(workers); }
    }

    @Test public void nullDnsIsWarningRatherThanConnectivityFailure() throws Exception {
        final ExecutorService workers = Executors.newFixedThreadPool(6);
        try {
            final ServerNetworkProbe.Transport transport = new OfflineTransport() {
                @Override public boolean https(final Network network, final String host,
                                               final ServerNetworkProbe.Session session) { return true; }
            };
            assertEquals(NetworkCheckResult.PASSED_DNS_WARNING,
                    probe(workers, transport, 2000L, 30L).check(null, null));
        } finally { stop(workers); }
    }

    @Test public void allFastFailuresIncludingExceptionsFinishWithoutWaitingForDeadline() throws Exception {
        final ExecutorService workers = Executors.newFixedThreadPool(6);
        try {
            final ServerNetworkProbe.Transport transport = new OfflineTransport() {
                @Override public boolean https(final Network network, final String host,
                                               final ServerNetworkProbe.Session session) {
                    throw new IllegalStateException("injected failure");
                }
            };
            final long start = System.nanoTime();
            assertEquals(NetworkCheckResult.HTTPS_FAILED,
                    probe(workers, transport, 2000L, 30L).check(null, "!disabled"));
            assertTrue(elapsedMillis(start) < 1000L);
        } finally { stop(workers); }
    }

    @Test public void globalDeadlineCancelsAllBlockedJobs() throws Exception {
        final ExecutorService workers = Executors.newFixedThreadPool(6);
        final CountDownLatch finished = new CountDownLatch(4);
        final CountDownLatch block = new CountDownLatch(1);
        try {
            final ServerNetworkProbe.Transport transport = new OfflineTransport() {
                @Override public boolean https(final Network network, final String host,
                                               final ServerNetworkProbe.Session session) {
                    try { return await(block); }
                    finally { finished.countDown(); }
                }
            };
            final long start = System.nanoTime();
            assertEquals(NetworkCheckResult.HTTPS_FAILED,
                    probe(workers, transport, 150L, 30L).check(null, "!disabled"));
            assertTrue(elapsedMillis(start) < 1000L);
            assertTrue(finished.await(1L, TimeUnit.SECONDS));
        } finally { stop(workers); }
    }

    @Test public void explicitCancellationWakesCallerAndCancelsAllSites() throws Exception {
        final ExecutorService workers = Executors.newFixedThreadPool(6);
        final ExecutorService caller = Executors.newSingleThreadExecutor();
        final CountDownLatch entered = new CountDownLatch(4);
        final CountDownLatch finished = new CountDownLatch(4);
        final CountDownLatch block = new CountDownLatch(1);
        try {
            final ServerNetworkProbe.Transport transport = new OfflineTransport() {
                @Override public boolean https(final Network network, final String host,
                                               final ServerNetworkProbe.Session session) {
                    entered.countDown();
                    try { return await(block); }
                    finally { finished.countDown(); }
                }
            };
            final ServerNetworkProbe probe = probe(workers, transport, 2000L, 30L);
            final Future<NetworkCheckResult> result = caller.submit(() -> probe.check(null, "!disabled"));
            assertTrue(entered.await(1L, TimeUnit.SECONDS));
            probe.cancel();
            assertEquals(NetworkCheckResult.CANCELLED, result.get(1L, TimeUnit.SECONDS));
            assertTrue(finished.await(1L, TimeUnit.SECONDS));
        } finally { stop(caller); stop(workers); }
    }

    @Test public void cancellationBeforeStartAndWorkerRejectionAreSafe() throws Exception {
        final ExecutorService workers = Executors.newFixedThreadPool(6);
        try {
            final ServerNetworkProbe cancelled = probe(workers, new OfflineTransport(), 2000L, 30L);
            cancelled.cancel();
            assertEquals(NetworkCheckResult.CANCELLED, cancelled.check(null, "!disabled"));
            workers.shutdown();
            assertEquals(NetworkCheckResult.UNAVAILABLE,
                    probe(workers, new OfflineTransport(), 2000L, 30L).check(null, "!disabled"));
        } finally { stop(workers); }
    }

    @Test public void sessionClosesEverySocketFutureAndLateRegistration() throws Exception {
        final ServerNetworkProbe.Session session = new ServerNetworkProbe.Session(
                System.nanoTime() + TimeUnit.SECONDS.toNanos(2L));
        final List<Socket> http = new ArrayList<>();
        final List<DatagramSocket> dns = new ArrayList<>();
        try {
            for (int index = 0; index < 8; index++) {
                final Socket socket = new Socket();
                http.add(socket); session.track(socket);
            }
            for (int index = 0; index < 2; index++) {
                final DatagramSocket socket = new DatagramSocket(null);
                dns.add(socket); session.track(socket);
            }
            final FutureTask<Boolean> future = new FutureTask<>(() -> true);
            session.track(future);
            session.close();
            session.close();
            assertTrue(session.isCancelled());
            assertTrue(future.isCancelled());
            for (final Socket socket : http) assertTrue(socket.isClosed());
            for (final DatagramSocket socket : dns) assertTrue(socket.isClosed());
            final Socket lateHttp = new Socket();
            try { session.track(lateHttp); fail("Late HTTP connection must be rejected"); }
            catch (final IOException expected) { assertTrue(lateHttp.isClosed()); }
            final DatagramSocket lateDns = new DatagramSocket(null);
            try { session.track(lateDns); fail("Late DNS connection must be rejected"); }
            catch (final IOException expected) { assertTrue(lateDns.isClosed()); }
            final FutureTask<Boolean> lateJob = new FutureTask<>(() -> true);
            session.track(lateJob);
            assertTrue(lateJob.isCancelled());
            try { session.timeout(800); fail("Closed session must reject further I/O"); }
            catch (final IOException expected) { /* 固定取消，不需要展示底层异常。 */ }
        } finally {
            session.close();
            for (final Socket socket : http) socket.close();
            for (final DatagramSocket socket : dns) socket.close();
        }
    }

    @Test public void exactlyThreeResultsConfirmConnectivity() {
        for (final NetworkCheckResult value : NetworkCheckResult.values()) {
            assertEquals(value.name(), Arrays.asList(NetworkCheckResult.PASSED,
                    NetworkCheckResult.PASSED_DNS_DISABLED, NetworkCheckResult.PASSED_DNS_WARNING)
                    .contains(value), value.isConnectivityConfirmed());
        }
    }

    private static ServerNetworkProbe probe(final ExecutorService workers,
                                            final ServerNetworkProbe.Transport transport,
                                            final long timeout, final long grace) {
        return new ServerNetworkProbe(transport, workers, timeout, grace);
    }

    private static class OfflineTransport implements ServerNetworkProbe.Transport {
        @Override public boolean https(final Network network, final String host,
                                       final ServerNetworkProbe.Session session) { return false; }
        @Override public boolean dns(final Network network, final String server,
                                     final ServerNetworkProbe.Session session) { return false; }
    }

    private static boolean await(final CountDownLatch latch) {
        try { return latch.await(2L, TimeUnit.SECONDS); }
        catch (final InterruptedException ignored) { Thread.currentThread().interrupt(); return false; }
    }

    private static long elapsedMillis(final long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private static void stop(final ExecutorService workers) throws InterruptedException {
        workers.shutdownNow();
        assertTrue("No offline worker may leak after a test", workers.awaitTermination(2L, TimeUnit.SECONDS));
    }
}
