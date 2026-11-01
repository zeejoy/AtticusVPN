/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.ddns;

import java.util.Objects;

/** 明确关闭 DDNS 的 Provider，便于上层使用统一流程而不发送任何网络请求。 */
public final class DisabledDdnsProvider implements DdnsProvider {

    /** 返回固定 Provider 标识。 */
    @Override
    public String getId() {
        return "disabled";
    }

    /** 始终返回 DISABLED，并且绝不会访问网络。 */
    @Override
    public DdnsUpdateResult update(DdnsUpdateRequest request) {
        Objects.requireNonNull(request, "request");
        return DdnsUpdateResult.local(
                getId(),
                DdnsUpdateResult.Status.DISABLED,
                "DDNS 已关闭。");
    }
}
