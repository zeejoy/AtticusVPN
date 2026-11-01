/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.capability;

import dev.lan.atticusvpn.server.root.RootCommandExecutor;
import dev.lan.atticusvpn.server.root.RootManager;
import dev.lan.atticusvpn.server.root.RootState;

/**
 * 此阶段只收集现场证据，不修改防火墙、路由、转发开关或 SELinux。
 * /proc 端口检查有竞态且受 namespace 限制，因此“未观察到冲突”不能当作 bind 成功。
 */
public final class IpsecCapabilityChecker {
    public enum PortObservation {
        NOT_CHECKED("未检查 UDP 500 / 4500"),
        UNKNOWN("端口检查失败；占用情况未知"),
        NO_CONFLICT_OBSERVED("本次 /proc 快照未观察到 UDP 500 / 4500 占用；未实际绑定"),
        BUSY_500("观察到 UDP 500 占用"),
        BUSY_4500("观察到 UDP 4500 占用"),
        BUSY_500_4500("观察到 UDP 500 和 4500 占用");
        private final String message;
        PortObservation(String message) { this.message = message; }
        public String message() { return message; }
        public boolean conflictObserved() { return this == BUSY_500 || this == BUSY_4500 || this == BUSY_500_4500; }
    }
    public enum ForwardingObservation {
        NOT_CHECKED("未检查"), UNKNOWN("读取失败"), ENABLED("已开启（仅观察，未改动）"), DISABLED("未开启（本应用未改动）");
        private final String message;
        ForwardingObservation(String message) { this.message = message; }
        public String message() { return message; }
    }
    public static final class Result {
        private final KernelCapability kernel;
        private final PortObservation ports;
        private final ForwardingObservation ipv4Forwarding, ipv6Forwarding;
        Result(KernelCapability kernel, PortObservation ports,
               ForwardingObservation ipv4Forwarding, ForwardingObservation ipv6Forwarding) {
            this.kernel = kernel; this.ports = ports;
            this.ipv4Forwarding = ipv4Forwarding; this.ipv6Forwarding = ipv6Forwarding;
        }
        public KernelCapability kernel() { return kernel; }
        public PortObservation ports() { return ports; }
        public ForwardingObservation ipv4Forwarding() { return ipv4Forwarding; }
        public ForwardingObservation ipv6Forwarding() { return ipv6Forwarding; }
        public boolean readChecksPassed() { return kernel.readChecksPassed(); }
        public boolean dataPlaneVerified() { return false; }
        public String summary() {
            if (kernel.xfrmStat() == KernelCapability.Evidence.NOT_CHECKED) return "IPsec 未检查；未取得 Root 授权";
            if (!readChecksPassed()) return "XFRM 只读检查未通过；不能据此认定内核永久不支持";
            if (ports.conflictObserved()) return "XFRM 只读检查通过，但观察到 IKE 端口占用";
            return "XFRM 只读检查通过；IKEv2 / ESP / NAT-T / 路由数据面尚未验证";
        }
    }

    private final RootManager root;
    public IpsecCapabilityChecker(RootManager root) { this.root = java.util.Objects.requireNonNull(root); }

    /** 只应在用户主动申请 Root 后执行，不放入后台自动重试。 */
    public Result check() {
        if (root.state() != RootState.ROOT_GRANTED) return notChecked();
        final KernelCapability kernel = new KernelCapability(
                evidence(read(RootCommandExecutor.Command.XFRM_STAT)),
                evidence(read(RootCommandExecutor.Command.XFRM_STATE)),
                evidence(read(RootCommandExecutor.Command.XFRM_POLICY)));
        final RootCommandExecutor.Result portResult = read(RootCommandExecutor.Command.UDP_PORTS);
        final PortObservation ports = ports(portResult);
        final ForwardingObservation ipv4 = forwarding(read(RootCommandExecutor.Command.IPV4_FORWARDING));
        final ForwardingObservation ipv6 = forwarding(read(RootCommandExecutor.Command.IPV6_FORWARDING));
        return new Result(kernel, ports, ipv4, ipv6);
    }

    public static Result notChecked() {
        final KernelCapability.Evidence absent = KernelCapability.Evidence.NOT_CHECKED;
        return new Result(new KernelCapability(absent, absent, absent), PortObservation.NOT_CHECKED,
                ForwardingObservation.NOT_CHECKED, ForwardingObservation.NOT_CHECKED);
    }

    private RootCommandExecutor.Result read(RootCommandExecutor.Command command) {
        if (Thread.currentThread().isInterrupted())
            return RootCommandExecutor.Result.failure(RootCommandExecutor.Status.INTERRUPTED);
        return root.executeReadOnly(command);
    }
    private static KernelCapability.Evidence evidence(RootCommandExecutor.Result result) {
        return result.succeeded() ? KernelCapability.Evidence.READ_CHECK_PASSED
                : KernelCapability.Evidence.FAILED_OR_UNAVAILABLE;
    }
    private static ForwardingObservation forwarding(RootCommandExecutor.Result result) {
        if (!result.succeeded()) return ForwardingObservation.UNKNOWN;
        if (result.value().equals("0")) return ForwardingObservation.DISABLED;
        if (result.value().equals("1")) return ForwardingObservation.ENABLED;
        return ForwardingObservation.UNKNOWN;
    }
    private static PortObservation ports(RootCommandExecutor.Result result) {
        if (!result.succeeded()) return PortObservation.UNKNOWN;
        switch (result.value()) {
            case "BUSY_500": return PortObservation.BUSY_500;
            case "BUSY_4500": return PortObservation.BUSY_4500;
            case "BUSY_500_4500": return PortObservation.BUSY_500_4500;
            case "NO_CONFLICT_OBSERVED": return PortObservation.NO_CONFLICT_OBSERVED;
            default: return PortObservation.UNKNOWN;
        }
    }
}
