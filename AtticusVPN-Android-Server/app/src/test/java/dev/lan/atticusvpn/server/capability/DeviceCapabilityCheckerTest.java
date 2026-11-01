/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.capability;

import org.junit.Test;
import java.net.InetAddress;
import static org.junit.Assert.*;

/** 数值地址不触发 DNS；测试只检查地址分类，不探测开发机外部网络。 */
public final class DeviceCapabilityCheckerTest {
    @Test public void localAndDocumentationAddressesAreNotPublicCandidates() throws Exception {
        for (String address : new String[]{"::1", "::", "fe80::1", "fd00::1", "ff02::1", "2001:db8::1", "127.0.0.1"})
            assertFalse(address, DeviceCapabilityChecker.isGlobalIpv6Candidate(InetAddress.getByName(address)));
        assertTrue(DeviceCapabilityChecker.isGlobalIpv6Candidate(InetAddress.getByName("2406::1")));
    }

    @Test public void metadataRemovesControlCharactersAndBoundsLength() {
        assertEquals("Unknown", DeviceCapabilityChecker.safeMetadata(null));
        assertEquals("line?next", DeviceCapabilityChecker.safeMetadata("line\nnext"));
        assertEquals("x?", DeviceCapabilityChecker.safeMetadata("x\ud83d\ude00"));
        assertEquals(160, DeviceCapabilityChecker.safeMetadata(new String(new char[200]).replace('\0', 'x')).length());
    }
}
