/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.ddns;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * 通过 Cloudflare API 更新指定 DNS 记录的 AAAA 内容。
 *
 * <p>请求始终把 {@code proxied} 设为 {@code false}：Cloudflare HTTP 代理不能代理标准
 * WireGuard UDP Endpoint。API token 只放在 Authorization 头中，不进入 URL 或结果消息。</p>
 */
public final class CloudflareDdnsProvider implements DdnsProvider {
    private final DdnsHttpTransport transport;
    private final String zoneId;
    private final String recordId;
    private final String apiToken;

    /** 使用标准 URLConnection 传输创建 Provider。 */
    public CloudflareDdnsProvider(String zoneId, String recordId, String apiToken) {
        this(new UrlConnectionDdnsHttpTransport(), zoneId, recordId, apiToken);
    }

    /** 使用可替换传输创建 Provider，便于离线测试。 */
    public CloudflareDdnsProvider(
            DdnsHttpTransport transport,
            String zoneId,
            String recordId,
            String apiToken) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.zoneId = requireIdentifier(zoneId, "Cloudflare zone ID");
        this.recordId = requireIdentifier(recordId, "Cloudflare record ID");
        this.apiToken = DdnsProviderSupport.requireSecret(apiToken, "Cloudflare API token");
    }

    /** 返回固定 Provider 标识。 */
    @Override
    public String getId() {
        return "cloudflare";
    }

    /**
     * 使用 Cloudflare 的 PUT 覆盖接口更新现有 AAAA 记录。
     *
     * <p>标准 {@code HttpURLConnection} 不接受 PATCH，而 Cloudflare 对同一
     * 记录路径提供 PUT 覆盖方法，因此使用 PUT 可在 API 24+ 不引入额外 HTTP 库。</p>
     */
    @Override
    public DdnsUpdateResult update(DdnsUpdateRequest request) {
        Objects.requireNonNull(request, "request");
        String urlText = "https://api.cloudflare.com/client/v4/zones/"
                + DdnsProviderSupport.queryEncode(zoneId)
                + "/dns_records/" + DdnsProviderSupport.queryEncode(recordId);
        String body = "{\"type\":\"AAAA\",\"name\":\""
                + DdnsProviderSupport.jsonEscape(request.getHostname())
                + "\",\"content\":\""
                + DdnsProviderSupport.jsonEscape(request.getAddressText())
                + "\",\"ttl\":120,\"proxied\":false}";
        Map<String, String> headers = DdnsProviderSupport.jsonHeaders("Bearer " + apiToken);
        DdnsHttpTransport.Request httpRequest = new DdnsHttpTransport.Request(
                DdnsProviderSupport.httpsUrl(urlText),
                "PUT",
                headers,
                body.getBytes(StandardCharsets.UTF_8),
                DdnsProviderSupport.DEFAULT_CONNECT_TIMEOUT_MILLIS,
                DdnsProviderSupport.DEFAULT_READ_TIMEOUT_MILLIS,
                "cloudflare-update");
        try {
            DdnsHttpTransport.Response response = transport.execute(httpRequest);
            if (!DdnsProviderSupport.isSuccessfulHttp(response.getStatusCode())) {
                return DdnsProviderSupport.httpFailure(getId(), response.getStatusCode());
            }
            String normalized = DdnsProviderSupport.normalizedBody(response)
                    .replace(" ", "").replace("\n", "").replace("\r", "").replace("\t", "");
            if (normalized.contains("\"success\":true")) {
                return new DdnsUpdateResult(
                        getId(), DdnsUpdateResult.Status.UPDATED,
                        response.getStatusCode(), "Cloudflare 已接受 AAAA 更新（仅 DNS）；等待解析确认。");
            }
            return new DdnsUpdateResult(
                    getId(), DdnsUpdateResult.Status.PERMANENT_ERROR,
                    response.getStatusCode(), "Cloudflare 未确认更新；请检查记录和 API token 权限。");
        } catch (IOException error) {
            return DdnsProviderSupport.networkFailure(getId(), error);
        }
    }

    /** Cloudflare 资源 ID 只接受 URL 路径安全字符。 */
    private static String requireIdentifier(String value, String displayName) {
        String candidate = DdnsProviderSupport.requireValue(value, displayName, 128);
        if (!candidate.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException(displayName + " 无效。");
        }
        return candidate;
    }
}
