/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.ddns;

import java.io.IOException;
import java.util.Collections;
import java.util.Objects;

/**
 * FreeDNS (freedns.afraid.org) 动态更新实现。
 *
 * <p>FreeDNS 的更新 key 是 URL 查询串的一部分。传输对象和日志实现均不会输出完整 URL，
 * 且实现不跟随重定向，以免 key 被转发到不同来源。</p>
 */
public final class FreeDnsDdnsProvider implements DdnsProvider {
    private final DdnsHttpTransport transport;
    private final String updateKey;

    /** 使用标准 URLConnection 传输创建 Provider。 */
    public FreeDnsDdnsProvider(String updateKey) {
        this(new UrlConnectionDdnsHttpTransport(), updateKey);
    }

    /** 使用可替换传输创建 Provider。 */
    public FreeDnsDdnsProvider(DdnsHttpTransport transport, String updateKey) {
        this.transport = Objects.requireNonNull(transport, "transport");
        String candidate = DdnsProviderSupport.requireSecret(updateKey, "FreeDNS update key");
        if (!candidate.matches("[A-Za-z0-9._~-]{1,512}")) {
            throw new IllegalArgumentException("FreeDNS update key 格式无效。");
        }
        this.updateKey = candidate;
    }

    /** 返回固定 Provider 标识。 */
    @Override
    public String getId() {
        return "freedns";
    }

    /** 提交 FreeDNS IPv6 更新并将正文仅解析为成功/错误，不回显正文。 */
    @Override
    public DdnsUpdateResult update(DdnsUpdateRequest request) {
        Objects.requireNonNull(request, "request");
        String urlText = "https://freedns.afraid.org/dynamic/update.php?"
                + updateKey + "&address="
                + DdnsProviderSupport.queryEncode(request.getAddressText());
        DdnsHttpTransport.Request httpRequest = new DdnsHttpTransport.Request(
                DdnsProviderSupport.httpsUrl(urlText), "GET",
                Collections.<String, String>emptyMap(), null,
                DdnsProviderSupport.DEFAULT_CONNECT_TIMEOUT_MILLIS,
                DdnsProviderSupport.DEFAULT_READ_TIMEOUT_MILLIS,
                "freedns-update");
        try {
            DdnsHttpTransport.Response response = transport.execute(httpRequest);
            if (!DdnsProviderSupport.isSuccessfulHttp(response.getStatusCode())) {
                return DdnsProviderSupport.httpFailure(getId(), response.getStatusCode());
            }
            String body = DdnsProviderSupport.normalizedBody(response);
            if (body.contains("updated") || body.contains("has not changed")) {
                DdnsUpdateResult.Status status = body.contains("has not changed")
                        ? DdnsUpdateResult.Status.UNCHANGED
                        : DdnsUpdateResult.Status.UPDATED;
                return new DdnsUpdateResult(getId(), status, response.getStatusCode(),
                        status == DdnsUpdateResult.Status.UPDATED
                                ? "FreeDNS 已接受 AAAA 更新；等待解析确认。"
                                : "FreeDNS 报告 AAAA 无需变更；等待解析确认。");
            }
            return new DdnsUpdateResult(getId(), DdnsUpdateResult.Status.PERMANENT_ERROR,
                    response.getStatusCode(), "FreeDNS 未确认更新；请检查 update key。");
        } catch (IOException error) {
            return DdnsProviderSupport.networkFailure(getId(), error);
        }
    }
}
