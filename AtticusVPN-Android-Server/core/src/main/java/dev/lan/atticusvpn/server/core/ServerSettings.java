/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.core;

import java.util.Objects;

/**
 * 与 AtticusVPN 0.1.0 Linux 服务器兼容的固定地址方案和可变连接设置。
 *
 * <p>ULA 只用于隧道内部；它绝不能被当作公网 Endpoint。是否输出 DNS 或 IPv6
 * 默认路由由显式设置控制，Android runtime 仍必须先验证对应能力。</p>
 */
public final class ServerSettings {
    public static final String VPN_IPV4_NETWORK = "10.77.0.0/24";
    public static final String SERVER_IPV4_CIDR = "10.77.0.1/24";
    public static final String SERVER_IPV4_ADDRESS = "10.77.0.1";
    public static final String CLIENT_IPV4_PREFIX = "10.77.0";

    public static final String VPN_IPV6_NETWORK = "fd77:6174:7469:6375::/64";
    public static final String SERVER_IPV6_CIDR = "fd77:6174:7469:6375::1/64";
    public static final String SERVER_IPV6_ADDRESS = "fd77:6174:7469:6375::1";
    public static final String CLIENT_IPV6_PREFIX = "fd77:6174:7469:6375::";

    // 仅用于新配置；从存储读取的旧端口及管理员自定义端口不会被此默认值覆盖。
    public static final int DEFAULT_LISTEN_PORT = 51_820;
    public static final int DEFAULT_MTU = 1_380;
    public static final int PERSISTENT_KEEPALIVE_SECONDS = 25;

    private final String endpointHost;
    private final int listenPort;
    private final int mtu;
    private final boolean dnsEnabled;
    private final boolean fullTunnelIpv6;

    public ServerSettings(
            final String endpointHost,
            final int listenPort,
            final int mtu,
            final boolean dnsEnabled,
            final boolean fullTunnelIpv6) {
        this.endpointHost = EndpointFormatter.requireValidHost(endpointHost);
        EndpointFormatter.requireValidPort(listenPort);
        if (mtu < 1_280 || mtu > 1_420) {
            throw new IllegalArgumentException("MTU must be between 1280 and 1420");
        }
        this.listenPort = listenPort;
        this.mtu = mtu;
        this.dnsEnabled = dnsEnabled;
        this.fullTunnelIpv6 = fullTunnelIpv6;
    }

    public static ServerSettings defaults(final String endpointHost) {
        return new ServerSettings(
                endpointHost,
                DEFAULT_LISTEN_PORT,
                DEFAULT_MTU,
                false,
                false);
    }

    public String endpointHost() {
        return endpointHost;
    }

    public int listenPort() {
        return listenPort;
    }

    public int mtu() {
        return mtu;
    }

    public boolean dnsEnabled() {
        return dnsEnabled;
    }

    public boolean fullTunnelIpv6() {
        return fullTunnelIpv6;
    }

    public String endpoint() {
        return EndpointFormatter.format(endpointHost, listenPort);
    }

    @Override
    public boolean equals(final Object other) {
        if (!(other instanceof ServerSettings that)) {
            return false;
        }
        return listenPort == that.listenPort
                && mtu == that.mtu
                && dnsEnabled == that.dnsEnabled
                && fullTunnelIpv6 == that.fullTunnelIpv6
                && endpointHost.equals(that.endpointHost);
    }

    @Override
    public int hashCode() {
        return Objects.hash(endpointHost, listenPort, mtu, dnsEnabled, fullTunnelIpv6);
    }

    @Override
    public String toString() {
        return "ServerSettings[endpoint=" + endpoint()
                + ", mtu=" + mtu
                + ", dnsEnabled=" + dnsEnabled
                + ", fullTunnelIpv6=" + fullTunnelIpv6 + ']';
    }
}
