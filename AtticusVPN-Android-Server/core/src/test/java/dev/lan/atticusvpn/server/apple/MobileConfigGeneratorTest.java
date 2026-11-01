/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.apple;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.Test;
import static org.junit.Assert.*;

/** 公开测试证书仅用于验证标准 XML 字段与输入校验，绝不装到设备上。 */
public final class MobileConfigGeneratorTest {
    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");
    private static MobileConfigGenerator.Settings settings() {
        return new MobileConfigGenerator.Settings("测试 & AtticusVPN", "vpn.atticus.test",
                "vpn.atticus.test", "client.apple.atticus.test", List.of("10.78.0.1", "fd78::1"));
    }
    @Test public void generatesCertificateIkeProfileWithoutPasswordOrInventedIpv6Payload() {
        byte[] profile = MobileConfigGenerator.generate(settings(), AppleTestFixtures.ca(),
                AppleTestFixtures.client(), AppleTestFixtures.password(), NOW);
        String xml = new String(profile, StandardCharsets.UTF_8);
        assertTrue(xml.contains("<string>IKEv2</string>"));
        assertTrue(xml.contains("<string>Certificate</string>"));
        assertTrue(xml.contains("<key>PayloadCertificateUUID</key>"));
        assertTrue(xml.contains("测试 &amp; AtticusVPN"));
        assertTrue(xml.contains("<key>IncludeAllNetworks</key>"));
        assertFalse(xml.contains("<key>IPv6</key>"));
        assertFalse(xml.contains("<key>Password</key>"));
        assertFalse(xml.contains(new String(AppleTestFixtures.password())));
        Arrays.fill(profile, (byte) 0);
    }
    @Test public void rejectsWrongPasswordAndWrongIdentity() {
        assertThrows(IllegalArgumentException.class, () -> MobileConfigGenerator.generate(settings(),
                AppleTestFixtures.ca(), AppleTestFixtures.client(), "wrong-password-123".toCharArray(), NOW));
        var wrong = new MobileConfigGenerator.Settings("test", "vpn.atticus.test", "vpn.atticus.test",
                "another.apple.atticus.test", List.of("10.78.0.1"));
        assertThrows(IllegalArgumentException.class, () -> MobileConfigGenerator.generate(wrong,
                AppleTestFixtures.ca(), AppleTestFixtures.client(), AppleTestFixtures.password(), NOW));
    }
    @Test public void rejectsExpiredMaterialDuplicateUuidsAndPlaceholderCertificates() {
        assertThrows(IllegalArgumentException.class, () -> MobileConfigGenerator.generate(settings(),
                AppleTestFixtures.ca(), AppleTestFixtures.client(), AppleTestFixtures.password(),
                Instant.parse("2100-01-01T00:00:00Z")));
        UUID repeated = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> MobileConfigGenerator.generate(settings(),
                AppleTestFixtures.ca(), AppleTestFixtures.client(), AppleTestFixtures.password(), NOW, () -> repeated));
        assertThrows(IllegalArgumentException.class, () -> MobileConfigGenerator.generate(settings(),
                new byte[100], new byte[100], AppleTestFixtures.password(), NOW));
    }
    @Test public void rejectsUrlsNumericIdentityDnsHostnamesAndControls() {
        for (String host : List.of("https://vpn.atticus.test", "127.0.0.1", "vpn.test:500")) {
            assertThrows(IllegalArgumentException.class, () -> new MobileConfigGenerator.Settings(
                    "test", host, "vpn.atticus.test", "client.apple.atticus.test", List.of("10.78.0.1")));
        }
        assertThrows(IllegalArgumentException.class, () -> new MobileConfigGenerator.Settings(
                "test", "vpn.atticus.test", "vpn.atticus.test", "client.apple.atticus.test", List.of("dns.test")));
        assertThrows(IllegalArgumentException.class, () -> new MobileConfigGenerator.Settings(
                "bad\u0001", "vpn.atticus.test", "vpn.atticus.test", "client.apple.atticus.test", List.of("10.78.0.1")));
    }
    @Test public void doesNotAlterCallerSecrets() {
        byte[] ca = AppleTestFixtures.ca(), p12 = AppleTestFixtures.client();
        char[] password = AppleTestFixtures.password();
        byte[] before = p12.clone(); char[] passBefore = password.clone();
        byte[] result = MobileConfigGenerator.generate(settings(), ca, p12, password, NOW);
        assertArrayEquals(before, p12); assertArrayEquals(passBefore, password);
        Arrays.fill(result, (byte) 0);
    }
}
