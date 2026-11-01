/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.ddns;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/** 使用 No-IP 标准动态更新端点发布 IPv6 地址。 */
public final class NoIpDdnsProvider implements DdnsProvider {
    private final DdnsHttpTransport transport;
    private final String username;
    private final String password;
    private final String userAgent;

    /** 使用标准 URLConnection 传输创建 Provider。 */
    public NoIpDdnsProvider(String username, String password) {
        this(new UrlConnectionDdnsHttpTransport(), username, password, username);
    }

    /**
     * 使用符合 No-IP 集成约定的维护者联系信息创建 Provider。
     *
     * @param username No-IP 用户名或 DDNS key 用户名
     * @param password No-IP 密码或 DDNS key 密码
     * @param maintainerContact 供 No-IP 识别客户端的维护者联系信息，建议使用邮箱
     */
    public NoIpDdnsProvider(String username, String password, String maintainerContact) {
        this(new UrlConnectionDdnsHttpTransport(), username, password, maintainerContact);
    }

    /** 使用可替换传输创建 Provider。 */
    public NoIpDdnsProvider(DdnsHttpTransport transport, String username, String password) {
        this(transport, username, password, username);
    }

    /** 使用可替换传输和明确维护者联系信息创建 Provider。 */
    public NoIpDdnsProvider(
            DdnsHttpTransport transport,
            String username,
            String password,
            String maintainerContact) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.username = DdnsProviderSupport.requireValue(username, "No-IP 用户名", 512);
        this.password = DdnsProviderSupport.requireSecret(password, "No-IP 密码");
        String contact = DdnsProviderSupport.requireValue(
                maintainerContact, "No-IP 维护者联系信息", 512);
        this.userAgent = "AtticusVPN AtticusVPN-Server/Android-0.3.0 " + contact;
    }

    /** 返回固定 Provider 标识。 */
    @Override
    public String getId() {
        return "noip";
    }

    /** 发起更新并解析 No-IP 的 good、nochg 和错误代码。 */
    @Override
    public DdnsUpdateResult update(DdnsUpdateRequest request) {
        Objects.requireNonNull(request, "request");
        String urlText = "https://dynupdate.no-ip.com/nic/update?hostname="
                + DdnsProviderSupport.queryEncode(request.getHostname())
                + "&myip=" + DdnsProviderSupport.queryEncode(request.getAddressText());
        Map<String, String> headers = DdnsProviderSupport.basicHeaders(
                username, password, userAgent);
        DdnsHttpTransport.Request httpRequest = new DdnsHttpTransport.Request(
                DdnsProviderSupport.httpsUrl(urlText), "GET", headers, null,
                DdnsProviderSupport.DEFAULT_CONNECT_TIMEOUT_MILLIS,
                DdnsProviderSupport.DEFAULT_READ_TIMEOUT_MILLIS,
                "noip-update");
        try {
            DdnsHttpTransport.Response response = transport.execute(httpRequest);
            if (!DdnsProviderSupport.isSuccessfulHttp(response.getStatusCode())) {
                return DdnsProviderSupport.httpFailure(getId(), response.getStatusCode());
            }
            return parse(response);
        } catch (IOException error) {
            return DdnsProviderSupport.networkFailure(getId(), error);
        }
    }

    /** 只匹配 No-IP 文档中的固定首字段，不回显正文。 */
    private DdnsUpdateResult parse(DdnsHttpTransport.Response response) {
        String body = DdnsProviderSupport.normalizedBody(response);
        if (body.startsWith("good")) {
            return new DdnsUpdateResult(getId(), DdnsUpdateResult.Status.UPDATED,
                    response.getStatusCode(), "No-IP 已接受 AAAA 更新；等待解析确认。");
        }
        if (body.startsWith("nochg")) {
            return new DdnsUpdateResult(getId(), DdnsUpdateResult.Status.UNCHANGED,
                    response.getStatusCode(), "No-IP 报告 AAAA 无需变更；等待解析确认。");
        }
        if (body.startsWith("911")) {
            return new DdnsUpdateResult(getId(), DdnsUpdateResult.Status.RETRYABLE_ERROR,
                    response.getStatusCode(), "No-IP 服务暂时不可用；可稍后重试。");
        }
        if (body.startsWith("badauth") || body.startsWith("nohost")
                || body.startsWith("abuse") || body.startsWith("badagent")
                || body.startsWith("!donator")) {
            return new DdnsUpdateResult(getId(), DdnsUpdateResult.Status.PERMANENT_ERROR,
                    response.getStatusCode(), "No-IP 拒绝更新；请检查账号、域名和服务状态。");
        }
        return new DdnsUpdateResult(getId(), DdnsUpdateResult.Status.RETRYABLE_ERROR,
                response.getStatusCode(), "No-IP 返回了无法识别的响应；可稍后重试。");
    }
}
