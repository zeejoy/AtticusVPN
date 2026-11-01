/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.root;

import org.junit.Test;
import static org.junit.Assert.*;

/** 离线解析测试不运行系统命令，不读取本机网络表或秘密。 */
public final class RootCommandExecutorTest {
    @Test public void acceptsOnlyScalarUidAndDropsStderr() {
        RootCommandExecutor.Result result = RootCommandExecutor.interpret(RootCommandExecutor.Command.UID, 0, "0\n", "private detail");
        assertEquals("0", result.value());
        for (String bad : new String[]{"uid=0(root)", "0\ntrailing", "", "-1", "0;anything"})
            assertEquals(RootCommandExecutor.Status.OUTPUT_INVALID,
                    RootCommandExecutor.interpret(RootCommandExecutor.Command.UID, 0, bad, "").status());
    }

    @Test public void nonzeroUidProcessExitNeverReportsSuccess() {
        assertEquals(RootCommandExecutor.Status.FAILED,
                RootCommandExecutor.interpret(RootCommandExecutor.Command.UID, 1, "0", "unknown error").status());
        assertEquals(RootCommandExecutor.Status.DENIED,
                RootCommandExecutor.interpret(RootCommandExecutor.Command.UID, 1, "", "Permission denied").status());
        // 某个 ip 子命令权限失败不能被曲解成用户明确拒绝 Root 授权。
        assertEquals(RootCommandExecutor.Status.FAILED,
                RootCommandExecutor.interpret(RootCommandExecutor.Command.XFRM_STATE, 1,
                        "ATTICUS_UID_ZERO_VERIFIED\n", "Permission denied").status());
    }

    @Test public void xfrmContentsAreNotExposedInResultEvenForInjectedOutput() {
        RootCommandExecutor.Result result = RootCommandExecutor.interpret(RootCommandExecutor.Command.XFRM_STATE,
                0, "ATTICUS_UID_ZERO_VERIFIED\nauth-trunc hmac(sha256) TEST_SECRET_DO_NOT_LOG", "TEST_SECRET");
        assertEquals("READ_CHECK_PASSED", result.value());
        assertFalse(result.message().contains("TEST_SECRET"));
    }

    @Test public void portParserReportsOnlyTargetConflicts() {
        String header4 = "sl local_address rem_address st\n";
        String header6 = "sl local_address remote_address st\n";
        assertEquals("NO_CONFLICT_OBSERVED", RootCommandExecutor.parseUdpPorts(header4 + header6).value());
        String ipv4 = " 1: 00000000:01F4 00000000:0000 07\n";
        String ipv6 = " 2: 00000000000000000000000000000000:1194 00000000000000000000000000000000:0000 07\n";
        assertEquals("BUSY_500_4500", RootCommandExecutor.parseUdpPorts(header4 + ipv4 + header6 + ipv6).value());
        assertEquals("BUSY_500", RootCommandExecutor.parseUdpPorts(header4 + ipv4 + header6).value());
        assertEquals("BUSY_4500", RootCommandExecutor.parseUdpPorts(header4 + header6 + ipv6).value());
    }

    @Test public void missingOrMalformedProcFileIsUnknownNotFree() {
        for (String malformed : new String[]{"", "permission denied", "sl local_address rem_address st\n",
                "sl local_address rem_address st\nsl local_address remote_address st\n 1: not_an_endpoint other"})
            assertEquals(RootCommandExecutor.Status.OUTPUT_INVALID, RootCommandExecutor.parseUdpPorts(malformed).status());
    }

    @Test public void selinuxAndForwardingUseClosedAllowedValues() {
        assertEquals("Enforcing", RootCommandExecutor.interpret(RootCommandExecutor.Command.SELINUX, 0, "ATTICUS_UID_ZERO_VERIFIED\nEnforcing\n", "").value());
        assertEquals(RootCommandExecutor.Status.OUTPUT_INVALID,
                RootCommandExecutor.interpret(RootCommandExecutor.Command.SELINUX, 0, "ATTICUS_UID_ZERO_VERIFIED\nunknown stuff", "").status());
        assertEquals(RootCommandExecutor.Status.OUTPUT_INVALID,
                RootCommandExecutor.interpret(RootCommandExecutor.Command.IPV4_FORWARDING, 0, "ATTICUS_UID_ZERO_VERIFIED\n2", "").status());
    }

    @Test public void eachCommandNeedsFreshUidEvidence() {
        assertEquals(RootCommandExecutor.Status.DENIED,
                RootCommandExecutor.interpret(RootCommandExecutor.Command.XFRM_STATE, 1, "", "Permission denied").status());
        assertEquals(RootCommandExecutor.Status.IDENTITY_UNVERIFIED,
                RootCommandExecutor.interpret(RootCommandExecutor.Command.XFRM_STATE, 0, "READ_CHECK_PASSED", "").status());
        assertEquals(RootCommandExecutor.Status.IDENTITY_UNVERIFIED,
                RootCommandExecutor.interpret(RootCommandExecutor.Command.XFRM_STATE, 77, "", "").status());
    }
}
