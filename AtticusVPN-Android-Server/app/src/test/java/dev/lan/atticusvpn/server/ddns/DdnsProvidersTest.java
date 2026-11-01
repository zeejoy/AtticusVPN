/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.ddns;

import org.junit.Test;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 各 Provider 的请求形状、固定响应解析和凭据边界测试。 */
public final class DdnsProvidersTest {

    /** DuckDNS 按协议发送 token，但请求诊断文本绝不包含 URL 或 token。 */
    @Test
    public void duckDnsKeepsTokenOutOfDiagnostics() {
        CapturingTransport transport = new CapturingTransport(200, "OK\n");
        String secret = "duck-secret";
        DdnsUpdateResult result = new DuckDnsDdnsProvider(transport, secret)
                .update(request("node.duckdns.org"));
        assertEquals(DdnsUpdateResult.Status.UPDATED, result.getStatus());
        assertTrue(transport.request.getUrl().toString().contains("token=duck-secret"));
        assertFalse(transport.request.toString().contains(secret));
        assertFalse(result.toString().contains(secret));
    }

    /** Cloudflare token 只在 Authorization 头，URL 与正文均不含 token。 */
    @Test
    public void cloudflareUsesBearerHeaderAndDnsOnlyRecord() {
        CapturingTransport transport = new CapturingTransport(200, "{\"success\": true}");
        String secret = "cf-secret";
        DdnsUpdateResult result = new CloudflareDdnsProvider(
                transport, "zone123", "record456", secret).update(request("vpn.example.com"));
        assertEquals(DdnsUpdateResult.Status.UPDATED, result.getStatus());
        assertEquals("PUT", transport.request.getMethod());
        assertEquals("Bearer " + secret, transport.request.getHeaders().get("Authorization"));
        assertFalse(transport.request.getUrl().toString().contains(secret));
        String body = new String(transport.request.getBody(), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"type\":\"AAAA\""));
        assertTrue(body.contains("\"proxied\":false"));
        assertFalse(body.contains(secret));
        assertFalse(transport.request.toString().contains(secret));
    }

    /** No-IP 的 good、nochg、badauth 映射为明确状态。 */
    @Test
    public void noIpParsesDocumentedResponses() {
        assertEquals(DdnsUpdateResult.Status.UPDATED,
                noIpResult("good 2001:db8::1").getStatus());
        assertEquals(DdnsUpdateResult.Status.UNCHANGED,
                noIpResult("nochg 2001:db8::1").getStatus());
        assertEquals(DdnsUpdateResult.Status.PERMANENT_ERROR,
                noIpResult("badauth").getStatus());
    }

    /** No-IP 请求包含可识别 User-Agent，但不会把密码拼入 User-Agent 或 URL。 */
    @Test
    public void noIpUsesSafeIdentifiableUserAgent() {
        CapturingTransport transport = new CapturingTransport(200, "good 2001:db8::1");
        String password = "noip-secret";
        new NoIpDdnsProvider(transport, "account@example.com", password,
                "maintainer@example.com").update(request("vpn.example.com"));

        String userAgent = transport.request.getHeaders().get("User-Agent");
        assertTrue(userAgent.contains("AtticusVPN-Server"));
        assertTrue(userAgent.contains("maintainer@example.com"));
        assertFalse(userAgent.contains(password));
        assertFalse(transport.request.getUrl().toString().contains(password));
    }

    /** Basic 认证拒绝含冒号的用户名，避免与密码分隔符产生歧义。 */
    @Test(expected = IllegalArgumentException.class)
    public void basicAuthenticationRejectsColonInUsername() {
        CapturingTransport transport = new CapturingTransport(200, "good 2001:db8::1");
        new NoIpDdnsProvider(transport, "user:name", "password")
                .update(request("vpn.example.com"));
    }

    /** Dynu 的临时服务错误可重试，认证类错误不可盲目重试。 */
    @Test
    public void dynuClassifiesResponses() {
        CapturingTransport retry = new CapturingTransport(200, "911");
        DdnsUpdateResult retryResult = new DynuDdnsProvider(retry, "user", "pass")
                .update(request("vpn.example.com"));
        CapturingTransport denied = new CapturingTransport(200, "badauth");
        DdnsUpdateResult deniedResult = new DynuDdnsProvider(denied, "user", "pass")
                .update(request("vpn.example.com"));
        assertEquals(DdnsUpdateResult.Status.RETRYABLE_ERROR, retryResult.getStatus());
        assertEquals(DdnsUpdateResult.Status.PERMANENT_ERROR, deniedResult.getStatus());
        assertTrue(retry.request.getUrl().toString().contains("myip=no"));
    }

    /** FreeDNS update key 可进入协议 URL，但不会进入对象诊断文本。 */
    @Test
    public void freeDnsKeepsUpdateKeyOutOfDiagnostics() {
        CapturingTransport transport = new CapturingTransport(200, "Updated vpn.example.com");
        String key = "free-key-123";
        DdnsUpdateResult result = new FreeDnsDdnsProvider(transport, key)
                .update(request("vpn.example.com"));
        assertEquals(DdnsUpdateResult.Status.UPDATED, result.getStatus());
        assertTrue(transport.request.getUrl().toString().contains(key));
        assertFalse(transport.request.toString().contains(key));
        assertFalse(result.toString().contains(key));
    }

    /** 自定义 API 对 URL 占位符编码，同时允许 token 仅位于认证头。 */
    @Test
    public void customApiRendersTemplatesWithoutDiagnosticLeak() {
        CapturingTransport transport = new CapturingTransport(204, "accepted");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer {token}");
        String secret = "custom-secret";
        CustomApiDdnsProvider provider = new CustomApiDdnsProvider(
                transport,
                "https://ddns.example.net/update?host={hostname}&address={ipv6}",
                "POST",
                headers,
                "host={hostname}&address={ipv6}",
                secret,
                "accepted");
        DdnsUpdateResult result = provider.update(request("vpn.example.com"));
        assertEquals(DdnsUpdateResult.Status.UPDATED, result.getStatus());
        assertEquals("Bearer " + secret,
                transport.request.getHeaders().get("Authorization"));
        assertFalse(transport.request.getUrl().toString().contains(secret));
        assertFalse(transport.request.toString().contains(secret));
        assertTrue(transport.request.getUrl().toString().contains("%3A"));
    }

    /** 自定义 API 可以把 IPv6 仅放在 JSON 正文，普通 JSON 花括号不会被误判。 */
    @Test
    public void customApiAllowsIpv6InJsonBody() {
        CapturingTransport transport = new CapturingTransport(200, "{\"success\":true}");
        Map<String, String> headers = Collections.singletonMap(
                "Content-Type", "application/json; charset=utf-8");
        CustomApiDdnsProvider provider = new CustomApiDdnsProvider(
                transport,
                "https://ddns.example.net/v1/records",
                "POST",
                headers,
                "{\"host\":\"{hostname}\",\"address\":\"{ipv6}\"}",
                "",
                "{\"success\":true}");

        DdnsUpdateResult result = provider.update(request("vpn.example.com"));

        assertEquals(DdnsUpdateResult.Status.UPDATED, result.getStatus());
        String body = new String(transport.request.getBody(), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"address\":\"2001:db8"));
    }

    /** Android 标准 HttpURLConnection 不能执行 PATCH，因此在配置阶段明确拒绝。 */
    @Test(expected = IllegalArgumentException.class)
    public void customApiRejectsUnsupportedPatchMethod() {
        new CustomApiDdnsProvider(
                new CapturingTransport(200, "ok"),
                "https://ddns.example.net/update?address={ipv6}",
                "PATCH",
                Collections.emptyMap(),
                "",
                "",
                "");
    }

    /** I/O 异常正文即使包含 URL 和 token，也不会进入返回结果。 */
    @Test
    public void networkExceptionMessageIsNeverExposed() {
        DdnsHttpTransport failing = new DdnsHttpTransport() {
            /** 模拟底层库在异常中包含敏感 URL。 */
            @Override
            public Response execute(Request request) throws IOException {
                throw new IOException("https://example/?token=leaked-secret");
            }
        };
        DdnsUpdateResult result = new DuckDnsDdnsProvider(failing, "leaked-secret")
                .update(request("node.duckdns.org"));
        assertEquals(DdnsUpdateResult.Status.RETRYABLE_ERROR, result.getStatus());
        assertFalse(result.toString().contains("leaked-secret"));
        assertFalse(result.toString().contains("example"));
    }

    /** Disabled Provider 不访问传输也返回明确状态。 */
    @Test
    public void disabledProviderIsExplicit() {
        DdnsUpdateResult result = new DisabledDdnsProvider().update(request("vpn.example.com"));
        assertEquals(DdnsUpdateResult.Status.DISABLED, result.getStatus());
        assertTrue(result.isSuccess());
    }

    /** 使用固定响应调用 No-IP。 */
    private static DdnsUpdateResult noIpResult(String body) {
        CapturingTransport transport = new CapturingTransport(200, body);
        return new NoIpDdnsProvider(transport, "user", "password")
                .update(request("vpn.example.com"));
    }

    /** 创建标准测试更新请求。 */
    private static DdnsUpdateRequest request(String hostname) {
        try {
            return new DdnsUpdateRequest(
                    hostname,
                    (Inet6Address) InetAddress.getByName("2001:db8::1234"),
                    DdnsUpdateRequest.Trigger.EVENT);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    /** 捕获请求并返回固定响应的内存传输。 */
    private static final class CapturingTransport implements DdnsHttpTransport {
        private final Response response;
        private Request request;

        /** 创建固定响应传输。 */
        private CapturingTransport(int status, String body) {
            this.response = new Response(status, body);
        }

        /** 保存请求供断言检查。 */
        @Override
        public Response execute(Request request) {
            this.request = request;
            return response;
        }
    }
}
