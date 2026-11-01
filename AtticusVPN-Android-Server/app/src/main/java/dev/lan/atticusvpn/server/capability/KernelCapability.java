/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.capability;

/**
 * 内核只读证据不是内核 IPsec 的运行承诺。ESP/NAT-T/transform 必须由真正的
 * CHILD_SA 建立和收发测试证明，不能由 /proc 文件或命令返回 0 推断。
 */
public final class KernelCapability {
    public enum Evidence {
        NOT_CHECKED("未检查"),
        READ_CHECK_PASSED("只读检查通过"),
        FAILED_OR_UNAVAILABLE("无法完成读取（工具/权限/内核均可能）");
        private final String message;
        Evidence(String message) { this.message = message; }
        public String message() { return message; }
    }

    private final Evidence xfrmStat, xfrmState, xfrmPolicy;
    public KernelCapability(Evidence xfrmStat, Evidence xfrmState, Evidence xfrmPolicy) {
        this.xfrmStat = xfrmStat; this.xfrmState = xfrmState; this.xfrmPolicy = xfrmPolicy;
    }
    public Evidence xfrmStat() { return xfrmStat; }
    public Evidence xfrmState() { return xfrmState; }
    public Evidence xfrmPolicy() { return xfrmPolicy; }
    public boolean readChecksPassed() {
        return xfrmStat == Evidence.READ_CHECK_PASSED && xfrmState == Evidence.READ_CHECK_PASSED
                && xfrmPolicy == Evidence.READ_CHECK_PASSED;
    }
    public boolean dataPlaneVerified() { return false; }
    public String transformStatus() { return "ESP / transform / NAT-T 未验证；未加载内核模块或写入 XFRM"; }
}
