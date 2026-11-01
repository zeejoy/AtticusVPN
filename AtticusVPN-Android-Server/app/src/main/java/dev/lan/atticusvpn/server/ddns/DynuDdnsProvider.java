/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.ddns;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/** 使用 Dynu 动态更新 API 发布 IPv6 地址。 */
public final class DynuDdnsProvider implements DdnsProvider {
    private final DdnsHttpTransport transport;
    private final String username;
    private final String password;

    /** 使用标准 URLConnection 传输创建 Provider。 */
    public DynuDdnsProvider(String username, String password) {
        this(new UrlConnectionDdnsHttpTransport(), username, password);
    }

    /** 使用可替换传输创建 Provider。 */
    public DynuDdnsProvider(DdnsHttpTransport transport, String username, String password) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.username = DdnsProviderSupport.requireValue(username, "Dynu 用户名", 512);
        this.password = DdnsProviderSupport.requireSecret(password, "Dynu 密码");
    }

    /** 返回固定 Provider 标识。 */
    @Override
    public String getId() {
        return "dynu";
    }

    /** 发起 IPv6 更新并解析 Dynu 的 good/nochg/badauth 等固定响应。 */
    @Override
    public DdnsUpdateResult update(DdnsUpdateRequest request) {
        Objects.requireNonNull(request, "request");
        String urlText = "https://api.dynu.com/nic/update?hostname="
                + DdnsProviderSupport.queryEncode(request.getHostname())
                + "&myip=no&myipv6="
                + DdnsProviderSupport.queryEncode(request.getAddressText());
        Map<String, String> headers = DdnsProviderSupport.basicHeaders(username, password);
        DdnsHttpTransport.Request httpRequest = new DdnsHttpTransport.Request(
                DdnsProviderSupport.httpsUrl(urlText), "GET", headers, null,
                DdnsProviderSupport.DEFAULT_CONNECT_TIMEOUT_MILLIS,
                DdnsProviderSupport.DEFAULT_READ_TIMEOUT_MILLIS,
                "dynu-update");
        try {
            DdnsHttpTransport.Response response = transport.execute(httpRequest);
            if (!DdnsProviderSupport.isSuccessfulHttp(response.getStatusCode())) {
                return DdnsProviderSupport.httpFailure(getId(), response.getStatusCode());
            }
            String body = DdnsProviderSupport.normalizedBody(response);
            if (body.startsWith("good")) {
                return new DdnsUpdateResult(getId(), DdnsUpdateResult.Status.UPDATED,
                        response.getStatusCode(), "Dynu 已接受 AAAA 更新；等待解析确认。");
            }
            if (body.startsWith("nochg")) {
                return new DdnsUpdateResult(getId(), DdnsUpdateResult.Status.UNCHANGED,
                        response.getStatusCode(), "Dynu 报告 AAAA 无需变更；等待解析确认。");
            }
            if (body.startsWith("911") || body.startsWith("dnserr")
                    || body.startsWith("servererror")) {
                return new DdnsUpdateResult(getId(), DdnsUpdateResult.Status.RETRYABLE_ERROR,
                        response.getStatusCode(), "Dynu 服务暂时不可用；可稍后重试。");
            }
            return new DdnsUpdateResult(getId(), DdnsUpdateResult.Status.PERMANENT_ERROR,
                    response.getStatusCode(), "Dynu 拒绝更新；请检查账号和域名配置。");
        } catch (IOException error) {
            return DdnsProviderSupport.networkFailure(getId(), error);
        }
    }
}
