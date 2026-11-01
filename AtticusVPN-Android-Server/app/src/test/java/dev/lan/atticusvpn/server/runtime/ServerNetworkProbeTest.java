/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.runtime;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.Assert.*;

/** 离线构造真实 DNS wire-format，覆盖错事务、截断、NXDOMAIN 和空响应等误报风险。 */
public final class ServerNetworkProbeTest {
    @Test public void selectsIpv4WithoutIpv6Fallback() throws Exception {
        final InetAddress v6 = InetAddress.getByAddress(new byte[16]);
        final InetAddress v4 = InetAddress.getByAddress(new byte[]{(byte)192, 0, 2, 1});
        assertSame(v4, ServerNetworkProbe.firstIpv4(new InetAddress[]{v6, v4}));
        assertNull(ServerNetworkProbe.firstIpv4(new InetAddress[]{v6}));
        assertNull(ServerNetworkProbe.firstIpv4(new InetAddress[0]));
    }

    @Test public void validatesAuthenticatedHttpStatusStrictly() {
        assertTrue(ServerNetworkProbe.successfulStatus("HTTP/1.1 200 OK"));
        assertTrue(ServerNetworkProbe.successfulStatus("HTTP/1.0 204"));
        assertTrue(ServerNetworkProbe.successfulStatus("HTTP/1.1 301 Moved Permanently"));
        assertFalse(ServerNetworkProbe.successfulStatus("HTTP/1.1 403 Forbidden"));
        assertFalse(ServerNetworkProbe.successfulStatus("HTTP/1.1 500 Error"));
        assertFalse(ServerNetworkProbe.successfulStatus("HTTP/1.1 2000"));
        assertFalse(ServerNetworkProbe.successfulStatus(null));
    }

    @Test public void limitsHttpStatusLineAndRequiresLineEnding() throws Exception {
        assertEquals("HTTP/1.1 200 OK", ServerNetworkProbe.readStatusLine(new ByteArrayInputStream(
                "HTTP/1.1 200 OK\r\nignored".getBytes(StandardCharsets.US_ASCII))));
        assertNull(ServerNetworkProbe.readStatusLine(new ByteArrayInputStream(
                "HTTP/1.1 200 OK".getBytes(StandardCharsets.US_ASCII))));
        assertNull(ServerNetworkProbe.readStatusLine(new ByteArrayInputStream(new byte[]{(byte)255, 10})));
        final byte[] oversized = new byte[513];
        Arrays.fill(oversized, (byte)'a');
        oversized[512] = 10;
        assertNull(ServerNetworkProbe.readStatusLine(new ByteArrayInputStream(oversized)));
    }

    @Test public void acceptsMatchingCompleteAAnswer() {
        final byte[] query = ServerNetworkProbe.query(2312);
        assertTrue(ServerNetworkProbe.acceptsDnsAnswer(query, answer(query)));
    }

    @Test public void rejectsWrongTransactionQuestionAndTruncation() {
        final byte[] query = ServerNetworkProbe.query(2312);
        byte[] reply = answer(query);
        reply[0] ^= 1;
        assertFalse(ServerNetworkProbe.acceptsDnsAnswer(query, reply));
        reply = answer(query); reply[13] = 'x';
        assertFalse(ServerNetworkProbe.acceptsDnsAnswer(query, reply));
        reply = answer(query); reply[2] |= 2;
        assertFalse(ServerNetworkProbe.acceptsDnsAnswer(query, reply));
        reply = answer(query); reply[3] |= 3;
        assertFalse(ServerNetworkProbe.acceptsDnsAnswer(query, reply));
        assertFalse(ServerNetworkProbe.acceptsDnsAnswer(query, Arrays.copyOf(answer(query), query.length)));
        assertFalse(ServerNetworkProbe.acceptsDnsAnswer(query, new byte[3]));
    }

    @Test public void rejectsIncompleteRecordAndZeroAnswers() {
        final byte[] query = ServerNetworkProbe.query(42);
        byte[] reply = answer(query); reply[7] = 0;
        assertFalse(ServerNetworkProbe.acceptsDnsAnswer(query, reply));
        reply = answer(query); reply[query.length + 11] = 5;
        assertFalse(ServerNetworkProbe.acceptsDnsAnswer(query, reply));
        assertFalse(ServerNetworkProbe.acceptsDnsAnswer(query,
                Arrays.copyOf(answer(query), query.length + 15)));
    }

    @Test public void classifiesChecksWithoutClaimingDisabledDnsPassed() {
        assertEquals(NetworkCheckResult.PASSED, NetworkCheckResult.classify(true, true, true));
        assertEquals(NetworkCheckResult.PASSED_DNS_DISABLED, NetworkCheckResult.classify(true, false, false));
        assertEquals(NetworkCheckResult.PASSED_DNS_WARNING, NetworkCheckResult.classify(true, false, true));
        assertEquals(NetworkCheckResult.HTTPS_FAILED, NetworkCheckResult.classify(false, true, true));
        assertEquals(NetworkCheckResult.HTTPS_FAILED, NetworkCheckResult.classify(false, false, false));
    }

    @Test public void rejectsBadOwnerPointersAndUnrelatedAnswers() {
        final byte[] query = ServerNetworkProbe.query(42);
        byte[] reply = answer(query); reply[query.length + 1] = (byte) 255;
        assertFalse(ServerNetworkProbe.acceptsDnsAnswer(query, reply));
        reply = answer(query); reply[query.length + 1] = (byte) query.length;
        assertFalse(ServerNetworkProbe.acceptsDnsAnswer(query, reply));
        reply = answer(query); reply[query.length + 1] = 16; // 只解析为 one.one.one，非探测域名
        assertFalse(ServerNetworkProbe.acceptsDnsAnswer(query, reply));
    }

    @Test public void snapshotCopyDoesNotChangeOriginalOrStoppedState() {
        final RuntimeStatus original = RuntimeStatus.stopped();
        final RuntimeStatus checked = original.withNetworkCheck(NetworkCheckResult.PASSED_DNS_WARNING);
        assertEquals(NetworkCheckResult.IDLE, original.networkCheck());
        assertEquals(NetworkCheckResult.PASSED_DNS_WARNING, checked.networkCheck());
        assertEquals(RuntimeStatus.Phase.STOPPED, checked.phase());
    }

    private static byte[] answer(final byte[] query) {
        final byte[] result = Arrays.copyOf(query, query.length + 16);
        result[2] = (byte) 0x81; result[3] = (byte) 0x80; result[7] = 1;
        final byte[] record = {(byte) 0xc0, 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 1, 1, 1, 1};
        System.arraycopy(record, 0, result, query.length, record.length);
        return result;
    }
}
