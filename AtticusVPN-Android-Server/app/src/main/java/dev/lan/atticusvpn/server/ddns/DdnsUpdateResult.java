/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.ddns;

import java.util.Objects;

/**
 * DDNS 更新的不可变、安全结果。
 *
 * <p>消息会在构造时再次脱敏，防止 Provider 错把 URL 或认证字段带到界面和日志。</p>
 */
public final class DdnsUpdateResult {

    /** 调用方可据此决定是否重试或提示用户修改配置。 */
    public enum Status {
        /** 服务商接受了写入；尚不表示公共 DNS 已解析到目标地址。 */
        UPDATED,
        /** 服务商报告记录无需变更；仍需独立解析确认。 */
        UNCHANGED,
        /** 独立 AAAA 查询已经包含目标 IPv6。 */
        VERIFIED,
        /** 服务商已接受请求，但 AAAA 仍在传播或仍返回旧值。 */
        PENDING_VERIFICATION,
        /** 本次无需或无法发起更新，例如没有公网 IPv6。 */
        SKIPPED,
        /** 临时网络或服务端错误，稍后可以重试。 */
        RETRYABLE_ERROR,
        /** 凭据、域名或配置错误，自动重试通常无效。 */
        PERMANENT_ERROR,
        /** DDNS 功能明确关闭。 */
        DISABLED
    }

    private final String providerId;
    private final Status status;
    private final int httpStatus;
    private final String message;

    /**
     * 创建更新结果。
     *
     * @param providerId 非敏感 Provider 标识
     * @param status 结果状态
     * @param httpStatus HTTP 状态码；没有 HTTP 响应时为 -1
     * @param message 面向用户的简短消息；将自动脱敏
     */
    public DdnsUpdateResult(
            String providerId,
            Status status,
            int httpStatus,
            String message) {
        this.providerId = DdnsProviderSupport.requireProviderId(providerId);
        this.status = Objects.requireNonNull(status, "status");
        if (httpStatus < -1 || httpStatus > 999) {
            throw new IllegalArgumentException("HTTP 状态码超出允许范围。");
        }
        this.httpStatus = httpStatus;
        this.message = DdnsLogSanitizer.sanitize(message == null ? "" : message);
    }

    /** 返回 Provider 标识。 */
    public String getProviderId() {
        return providerId;
    }

    /** 返回分类后的状态。 */
    public Status getStatus() {
        return status;
    }

    /** 返回 HTTP 状态码，或在未收到响应时返回 -1。 */
    public int getHttpStatus() {
        return httpStatus;
    }

    /** 返回已经脱敏的用户消息。 */
    public String getMessage() {
        return message;
    }

    /** 返回本次操作是否以可接受状态结束。 */
    public boolean isSuccess() {
        return status == Status.UPDATED
                || status == Status.UNCHANGED
                || status == Status.VERIFIED
                || status == Status.SKIPPED
                || status == Status.DISABLED;
    }

    /**
     * 返回是否已通过独立 AAAA 解析确认。
     *
     * @return 仅 {@link Status#VERIFIED} 返回 {@code true}
     */
    public boolean isDnsVerified() {
        return status == Status.VERIFIED;
    }

    /** 返回不含凭据和请求内容的诊断文本。 */
    @Override
    public String toString() {
        return "DdnsUpdateResult{provider=" + providerId
                + ", status=" + status
                + ", httpStatus=" + httpStatus
                + ", message=" + message + "}";
    }

    /** 创建无 HTTP 响应的标准结果。 */
    static DdnsUpdateResult local(String providerId, Status status, String message) {
        return new DdnsUpdateResult(providerId, status, -1, message);
    }
}
