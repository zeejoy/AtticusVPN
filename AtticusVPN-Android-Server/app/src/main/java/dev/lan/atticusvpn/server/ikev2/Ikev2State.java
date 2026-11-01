/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ikev2;

import dev.lan.atticusvpn.server.capability.DeviceCapabilitySnapshot;
import dev.lan.atticusvpn.server.capability.KernelCapability;

/**
 * IKEv2 与 WireGuard 的运行状态完全分开。这里仅决定开发预览版的锁定原因，
 * 不能用 Root 成功、端口快照或只读 XFRM 成功冒充真实 IPsec 数据面验证。
 */
public enum Ikev2State {
    IKEV2_AVAILABLE("可用"),
    IKEV2_ROOT_REQUIRED("需要已有 Root 的授权及兼容 IPsec 内核；WireGuard 不受影响"),
    IKEV2_ROOT_DENIED("Root 授权被拒绝；可重新请求，WireGuard 不受影响"),
    IKEV2_KERNEL_UNSUPPORTED("设备内核不支持所需 IPsec 功能"),
    IKEV2_XFRM_UNAVAILABLE("XFRM 只读检查未通过；可能是权限、工具或内核限制"),
    IKEV2_DATAPLANE_UNAVAILABLE("本开发版未集成可运行的 IKEv2 服务；IPsec 数据面尚未验证"),
    IKEV2_PORT_CONFLICT("观察到 UDP 500 / 4500 占用；未启动 IKEv2"),
    IKEV2_ERROR("Root 或设备检查异常；请手动重试");

    private final String message;
    Ikev2State(String message) { this.message = message; }
    public String message() { return message; }

    /**
     * 当前阶段没有 AVAILABLE 的生产路径。保留完整枚举给后续真实引擎状态机使用，
     * 不提供“强制支持”设置，也不把未完成的检查归为永久 KERNEL_UNSUPPORTED。
     */
    public static Ikev2State fromReadOnlySnapshot(DeviceCapabilitySnapshot snapshot) {
        if (snapshot == null) return IKEV2_ROOT_REQUIRED;
        switch (snapshot.rootState()) {
            case ROOT_PERMISSION_DENIED: return IKEV2_ROOT_DENIED;
            case ROOT_ERROR: return IKEV2_ERROR;
            case ROOT_NOT_FOUND: case ROOT_UNKNOWN: return IKEV2_ROOT_REQUIRED;
            case ROOT_GRANTED:
                if (snapshot.ipsec().ports().conflictObserved()) return IKEV2_PORT_CONFLICT;
                final KernelCapability kernel = snapshot.ipsec().kernel();
                if (kernel.xfrmStat() == KernelCapability.Evidence.FAILED_OR_UNAVAILABLE
                        || kernel.xfrmState() == KernelCapability.Evidence.FAILED_OR_UNAVAILABLE
                        || kernel.xfrmPolicy() == KernelCapability.Evidence.FAILED_OR_UNAVAILABLE)
                    return IKEV2_XFRM_UNAVAILABLE;
                return IKEV2_DATAPLANE_UNAVAILABLE;
            default: return IKEV2_ERROR;
        }
    }
}
