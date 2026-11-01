/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.ddns;

/**
 * 动态 DNS 服务商的最小统一接口。
 *
 * <p>实现类必须返回经过脱敏的结果，不得在异常消息中回传请求 URL、认证头、请求体或令牌。</p>
 */
public interface DdnsProvider {

    /**
     * 返回稳定、非敏感的 Provider 标识符。
     *
     * @return 例如 {@code duckdns} 或 {@code cloudflare}
     */
    String getId();

    /**
     * 将请求中的 AAAA 地址提交给服务商。
     *
     * @param request 已验证的主机名和公网 IPv6 地址
     * @return 不包含凭据的更新结果
     */
    DdnsUpdateResult update(DdnsUpdateRequest request);
}
