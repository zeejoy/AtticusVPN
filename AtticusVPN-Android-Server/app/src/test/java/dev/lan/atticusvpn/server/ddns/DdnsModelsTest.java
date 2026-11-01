/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.ddns;

import org.junit.Test;

import java.net.Inet6Address;
import java.net.InetAddress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** DDNS 请求和结果模型的验证测试。 */
public final class DdnsModelsTest {

    /** 主机名会规范化为 ASCII 小写，结果中不含凭据字段。 */
    @Test
    public void requestNormalizesHostname() throws Exception {
        DdnsUpdateRequest request = new DdnsUpdateRequest(
                "VPN.Example.COM.", ipv6("2001:db8::1"), DdnsUpdateRequest.Trigger.EVENT);
        assertEquals("vpn.example.com", request.getHostname());
        assertTrue(request.toString().contains("trigger=EVENT"));
    }

    /** ULA 不能构造成公共 DDNS 更新请求。 */
    @Test(expected = IllegalArgumentException.class)
    public void requestRejectsUla() throws Exception {
        new DdnsUpdateRequest(
                "vpn.example.com", ipv6("fd00::1"), DdnsUpdateRequest.Trigger.EVENT);
    }

    /** 结果消息会再次隐藏 token 和 URL 查询参数。 */
    @Test
    public void resultSanitizesMessage() {
        DdnsUpdateResult result = new DdnsUpdateResult(
                "custom",
                DdnsUpdateResult.Status.RETRYABLE_ERROR,
                500,
                "token=secret https://example.com/?password=oops");
        assertFalse(result.getMessage().contains("secret"));
        assertFalse(result.getMessage().contains("oops"));
    }

    /** 成功状态与可重试错误的布尔语义明确。 */
    @Test
    public void successClassificationIsStable() {
        assertTrue(DdnsUpdateResult.local("disabled", DdnsUpdateResult.Status.DISABLED, "off")
                .isSuccess());
        assertFalse(DdnsUpdateResult.local("custom",
                DdnsUpdateResult.Status.RETRYABLE_ERROR, "later").isSuccess());
        assertFalse(DdnsUpdateResult.local("custom",
                DdnsUpdateResult.Status.PENDING_VERIFICATION, "pending").isSuccess());
        assertTrue(DdnsUpdateResult.local("custom",
                DdnsUpdateResult.Status.VERIFIED, "verified").isDnsVerified());
    }

    /** 将测试常量解析为 IPv6 地址。 */
    private static Inet6Address ipv6(String value) throws Exception {
        return (Inet6Address) InetAddress.getByName(value);
    }
}
