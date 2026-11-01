/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.core;

/**
 * 可安全显示或记录的固定错误码。
 *
 * <p>Android 层不得把异常 message、配置正文、密钥或 token 拼接到这些文本中。</p>
 */
public enum SafeErrorCode {
    INVALID_NAME("Peer 名称无效。"),
    DUPLICATE_NAME("已存在同名 Peer。"),
    ADDRESS_POOL_FULL("客户端地址池已满。"),
    INVALID_ENDPOINT("Endpoint 主机或端口无效。"),
    INVALID_ROUTE("路由网段无效。"),
    INVALID_KEY_MATERIAL("WireGuard 密钥材料无效。"),
    PROFILE_SEALED("该 Peer 已封存，不能再次导出。"),
    VPN_PERMISSION_DENIED("未获得 Android VPN 授权。"),
    STORAGE_FAILURE("安全存储操作失败。"),
    BACKEND_FAILURE("WireGuard 服务操作失败。"),
    EXPORT_FAILURE("配置导出失败。"),
    INTERNAL_FAILURE("操作失败，请稍后重试。");

    private final String userMessage;

    SafeErrorCode(final String userMessage) {
        this.userMessage = userMessage;
    }

    public String userMessage() {
        return userMessage;
    }
}
