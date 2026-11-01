/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.ddns;

import java.io.IOException;
import java.net.Inet6Address;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 在 Provider 接受更新后，通过独立 AAAA 解析确认当前系统可见结果。
 *
 * <p>只有非空解析结果中的每个 AAAA 都等于目标 IPv6 的完整 16 字节值时才返回
 * {@code VERIFIED}。没有 AAAA、仍混有旧地址或 DNS 尚未传播时返回
 * {@code PENDING_VERIFICATION}；解析器错误返回可重试状态。任何情况下都不会把
 * “Provider 返回 2xx”误报为“DNS 已确认”。</p>
 */
public final class DdnsAaaaVerifier {
    private final AaaaRecordResolver resolver;

    /**
     * 创建 AAAA 验证器。
     *
     * @param resolver 不接触凭据的 DNS 解析器
     */
    public DdnsAaaaVerifier(AaaaRecordResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    /**
     * 验证 Provider 结果对应的公开 AAAA 记录。
     *
     * @param request 原始非敏感 DDNS 请求
     * @param providerResult Provider 接受或拒绝结果
     * @return 已确认、待传播、可重试，或原样保留的 Provider 错误
     */
    public DdnsUpdateResult verify(
            DdnsUpdateRequest request,
            DdnsUpdateResult providerResult) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(providerResult, "providerResult");
        if (providerResult.getStatus() != DdnsUpdateResult.Status.UPDATED
                && providerResult.getStatus() != DdnsUpdateResult.Status.UNCHANGED) {
            return providerResult;
        }

        final List<Inet6Address> resolved;
        try {
            resolved = resolver.resolve(request.getHostname());
            if (resolved == null) {
                throw new IOException("resolver returned null");
            }
        } catch (IOException | RuntimeException error) {
            // 不包含异常文字；自定义解析器的异常可能带有内部网络信息。
            return new DdnsUpdateResult(
                    providerResult.getProviderId(),
                    DdnsUpdateResult.Status.RETRYABLE_ERROR,
                    providerResult.getHttpStatus(),
                    "DDNS 服务已接受请求，但 AAAA 解析查询失败；可稍后重试确认。");
        }

        byte[] expected = request.getAddress().getAddress();
        boolean foundTarget = false;
        boolean foundDifferentAddress = false;
        for (Inet6Address address : resolved) {
            if (address != null && Arrays.equals(expected, address.getAddress())) {
                foundTarget = true;
            } else {
                foundDifferentAddress = true;
            }
        }
        if (foundTarget && !foundDifferentAddress) {
            return new DdnsUpdateResult(
                    providerResult.getProviderId(),
                    DdnsUpdateResult.Status.VERIFIED,
                    providerResult.getHttpStatus(),
                    "当前系统 DNS 的 AAAA 解析结果已确认目标 IPv6。");
        }
        return new DdnsUpdateResult(
                providerResult.getProviderId(),
                DdnsUpdateResult.Status.PENDING_VERIFICATION,
                providerResult.getHttpStatus(),
                "DDNS 服务已接受请求，但当前系统所见 AAAA 尚未完全传播到目标 IPv6。");
    }
}
