/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.ddns;

import java.io.IOException;
import java.util.Collections;
import java.util.Objects;

/**
 * DuckDNS IPv6 更新实现。
 *
 * <p>DuckDNS 协议要求 token 位于查询参数中。实际请求因此会包含 token，但请求对象的
 * {@code toString()}、结果和日志都不会输出 URL；网络层也不跟随可能泄露查询串的重定向。</p>
 */
public final class DuckDnsDdnsProvider implements DdnsProvider {
    private static final String SUFFIX = ".duckdns.org";

    private final DdnsHttpTransport transport;
    private final String token;

    /** 使用标准 URLConnection 传输创建 Provider。 */
    public DuckDnsDdnsProvider(String token) {
        this(new UrlConnectionDdnsHttpTransport(), token);
    }

    /**
     * 使用可替换传输创建 Provider。
     *
     * @param transport HTTPS 传输
     * @param token DuckDNS token
     */
    public DuckDnsDdnsProvider(DdnsHttpTransport transport, String token) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.token = DdnsProviderSupport.requireSecret(token, "DuckDNS token");
    }

    /** 返回固定 Provider 标识。 */
    @Override
    public String getId() {
        return "duckdns";
    }

    /** 提交 DuckDNS 的 IPv6 更新，并只解析固定的 OK/KO 响应。 */
    @Override
    public DdnsUpdateResult update(DdnsUpdateRequest request) {
        Objects.requireNonNull(request, "request");
        final String subdomain;
        try {
            subdomain = extractSubdomain(request.getHostname());
        } catch (IllegalArgumentException error) {
            return DdnsUpdateResult.local(
                    getId(),
                    DdnsUpdateResult.Status.PERMANENT_ERROR,
                    "DuckDNS 主机名必须是单个 *.duckdns.org 域名。");
        }

        String urlText = "https://www.duckdns.org/update?domains="
                + DdnsProviderSupport.queryEncode(subdomain)
                + "&token=" + DdnsProviderSupport.queryEncode(token)
                + "&ipv6=" + DdnsProviderSupport.queryEncode(request.getAddressText())
                + "&verbose=true";
        DdnsHttpTransport.Request httpRequest = new DdnsHttpTransport.Request(
                DdnsProviderSupport.httpsUrl(urlText),
                "GET",
                Collections.<String, String>emptyMap(),
                null,
                DdnsProviderSupport.DEFAULT_CONNECT_TIMEOUT_MILLIS,
                DdnsProviderSupport.DEFAULT_READ_TIMEOUT_MILLIS,
                "duckdns-update");
        try {
            DdnsHttpTransport.Response response = transport.execute(httpRequest);
            if (!DdnsProviderSupport.isSuccessfulHttp(response.getStatusCode())) {
                return DdnsProviderSupport.httpFailure(getId(), response.getStatusCode());
            }
            String body = DdnsProviderSupport.normalizedBody(response);
            if (body.startsWith("ok")) {
                return new DdnsUpdateResult(
                        getId(), DdnsUpdateResult.Status.UPDATED,
                        response.getStatusCode(), "DuckDNS 已接受 AAAA 更新；等待解析确认。");
            }
            return new DdnsUpdateResult(
                    getId(), DdnsUpdateResult.Status.PERMANENT_ERROR,
                    response.getStatusCode(), "DuckDNS 拒绝更新；请检查域名和 token。");
        } catch (IOException error) {
            return DdnsProviderSupport.networkFailure(getId(), error);
        }
    }

    /** 只接受单标签 DuckDNS 主机名，避免把任意域名误提交为 DuckDNS 子域。 */
    private static String extractSubdomain(String hostname) {
        if (!hostname.endsWith(SUFFIX)) {
            throw new IllegalArgumentException("not DuckDNS");
        }
        String value = hostname.substring(0, hostname.length() - SUFFIX.length());
        if (value.isEmpty() || value.indexOf('.') >= 0) {
            throw new IllegalArgumentException("not a single DuckDNS subdomain");
        }
        return value;
    }
}
