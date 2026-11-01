/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.capability;

import android.os.Build;
import dev.lan.atticusvpn.server.root.RootCommandExecutor;
import dev.lan.atticusvpn.server.root.RootManager;
import dev.lan.atticusvpn.server.root.RootState;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;

/**
 * Root 与普通网络信息分离；未授权时检查 Android/ABI/本机 IPv6 不会弹出 su 对话框。
 * IPv6 这里只观察本机接口，不能代替运营商入站、防火墙、DDNS 和端到端连通测试。
 */
public final class DeviceCapabilityChecker {
    private final RootManager root;
    public DeviceCapabilityChecker(RootManager root) { this.root = java.util.Objects.requireNonNull(root); }

    /** 用户主动检查后调用；启动时使用 checkWithoutRootCommands()。 */
    public DeviceCapabilitySnapshot check() {
        final IpsecCapabilityChecker.Result ipsec = new IpsecCapabilityChecker(root).check();
        String selinux = readLocalSelinux();
        if (root.state() == RootState.ROOT_GRANTED && !Thread.currentThread().isInterrupted()) {
            final RootCommandExecutor.Result result = root.executeReadOnly(RootCommandExecutor.Command.SELINUX);
            selinux = result.succeeded() ? result.value() : "未知（读取失败；未改变 SELinux）";
        }
        return snapshot(selinux, ipsec);
    }

    /** 不使用已缓存的授权偷偷执行 su；生命周期恢复也可安全调用这个入口。 */
    public DeviceCapabilitySnapshot checkWithoutRootCommands() {
        return snapshot(readLocalSelinux(), IpsecCapabilityChecker.notChecked());
    }

    private DeviceCapabilitySnapshot snapshot(String selinux, IpsecCapabilityChecker.Result ipsec) {
        final String android = safeMetadata(Build.VERSION.RELEASE) + " (API " + Build.VERSION.SDK_INT + ")";
        return new DeviceCapabilitySnapshot(root.state(), android,
                safeMetadata(System.getProperty("os.version", "Unknown")),
                Build.SUPPORTED_ABIS == null ? "Unknown" : safeMetadata(String.join(", ", Build.SUPPORTED_ABIS)),
                selinux, observeIpv6(), ipsec);
    }

    static String safeMetadata(String source) {
        if (source == null || source.isEmpty()) return "Unknown";
        // 不把 ROM 厂商任意控制字符直接放入状态面板/日志；长度也限制到单行。
        final String clean = source.replaceAll("[^\\p{L}\\p{N} ._+,()/-]", "?");
        return clean.substring(0, Math.min(clean.length(), 160));
    }

    private static String readLocalSelinux() {
        try (FileInputStream stream = new FileInputStream("/sys/fs/selinux/enforce")) {
            final int value = stream.read();
            if (value == '1') return "Enforcing";
            if (value == '0') return "Permissive";
        } catch (IOException | SecurityException unavailable) { /* 不存在可能为权限，不推断 Disabled。 */ }
        return "未知（只读信息不可用）";
    }

    private static String observeIpv6() {
        try {
            final java.util.Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return "未观察到本机 IPv6";
            boolean anyV6 = false;
            for (NetworkInterface network : Collections.list(interfaces)) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (!(address instanceof Inet6Address)) continue;
                    anyV6 = true;
                    if (isGlobalIpv6Candidate(address)) return "观察到全局 IPv6 地址候选；公网入站与 IKEv2 可达性未验证";
                }
            }
            return anyV6 ? "仅观察到非全局 IPv6；未证明公网可达" : "未观察到本机 IPv6";
        } catch (SocketException | SecurityException unavailable) { return "IPv6 接口读取失败"; }
    }

    static boolean isGlobalIpv6Candidate(InetAddress address) {
        if (!(address instanceof Inet6Address)) return false;
        final byte[] bytes = address.getAddress();
        // 仅接受当前常用全球单播 2000::/3；ULA/link-local/multicast 不标为公网。
        return (bytes[0] & 0xe0) == 0x20 && !address.isAnyLocalAddress()
                && !address.isLoopbackAddress() && !address.isLinkLocalAddress() && !address.isSiteLocalAddress()
                && !(bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d && (bytes[3] & 0xff) == 0xb8);
    }
}
