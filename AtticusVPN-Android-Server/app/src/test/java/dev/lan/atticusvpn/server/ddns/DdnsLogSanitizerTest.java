/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.ddns;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** DDNS 日志脱敏规则的纯 JVM 测试。 */
public final class DdnsLogSanitizerTest {

    /** HTTPS URL 的整个查询串会被隐藏。 */
    @Test
    public void hidesUrlQueryParameters() {
        String secret = "top-secret-token";
        String output = DdnsLogSanitizer.sanitize(
                "GET https://example.com/update?token=" + secret + "&ipv6=2001:db8::1");
        assertFalse(output.contains(secret));
        assertFalse(output.contains("2001:db8"));
        assertTrue(output.contains("[查询参数已隐藏]"));
    }

    /** Authorization、token 和 password 值均会被替换。 */
    @Test
    public void hidesCommonCredentialFields() {
        String output = DdnsLogSanitizer.sanitize(
                "Authorization: Bearer abc token=def password:ghi api_key=jkl");
        assertFalse(output.contains("abc"));
        assertFalse(output.contains("def"));
        assertFalse(output.contains("ghi"));
        assertFalse(output.contains("jkl"));
    }

    /** 调用方提供的无键名前缀秘密也会被隐藏。 */
    @Test
    public void hidesExplicitSecret() {
        String output = DdnsLogSanitizer.sanitize("unexpected opaque-value", "opaque-value");
        assertFalse(output.contains("opaque-value"));
        assertTrue(output.contains("[已隐藏]"));
    }

    /** 输出会变成单行并受长度限制。 */
    @Test
    public void normalizesLinesAndLimitsLength() {
        StringBuilder input = new StringBuilder("first\r\nsecond ");
        for (int index = 0; index < 2000; index++) {
            input.append('x');
        }
        String output = DdnsLogSanitizer.sanitize(input.toString());
        assertFalse(output.contains("\r"));
        assertFalse(output.contains("\n"));
        assertTrue(output.length() <= 1025);
    }
}
