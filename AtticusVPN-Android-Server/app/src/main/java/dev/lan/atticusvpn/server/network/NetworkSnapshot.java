/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.network;

import android.net.Network;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Android 默认网络在某一时刻的不可变快照。
 *
 * <p>快照包含网络类型、全部 IPv6 候选、选中的公网 Endpoint 和 DNS 服务器。
 * 它不包含 Wi-Fi SSID、运营商账户、DDNS 凭据或其他用户标识。</p>
 */
public final class NetworkSnapshot {

    /** 默认网络的主要传输类型。 */
    public enum NetworkType {
        VPN,
        WIFI,
        CELLULAR,
        ETHERNET,
        BLUETOOTH,
        OTHER,
        UNKNOWN
    }

    private final long networkHandle;
    private final Network androidNetwork;
    private final NetworkType networkType;
    private final List<GlobalIpv6Selector.Candidate> ipv6Addresses;
    private final GlobalIpv6Selector.Candidate endpointCandidate;
    private final List<InetAddress> dnsServers;
    private final boolean validated;
    private final boolean metered;

    /**
     * 创建网络快照。
     *
     * @param networkHandle Android Network 的稳定进程内句柄
     * @param networkType 主要传输类型
     * @param ipv6Addresses IPv6 地址及状态
     * @param endpointCandidate 选中的 GUA，可为 {@code null}
     * @param dnsServers 系统报告的 DNS 服务器
     * @param validated 网络是否通过系统互联网验证
     * @param metered 网络是否按流量计费
     */
    public NetworkSnapshot(
            long networkHandle,
            NetworkType networkType,
            List<GlobalIpv6Selector.Candidate> ipv6Addresses,
            GlobalIpv6Selector.Candidate endpointCandidate,
            List<InetAddress> dnsServers,
            boolean validated,
            boolean metered) {
        this(networkHandle, null, networkType, ipv6Addresses, endpointCandidate,
                dnsServers, validated, metered);
    }

    /**
     * 创建包含原始 Android Network 的快照。
     *
     * <p>保留系统回调给出的对象，可让 Android 7/8 直接绑定 listener socket；这些版本
     * 尚无 {@code Network.fromNetworkHandle}，因此不能只保存数字句柄再重建对象。</p>
     */
    public NetworkSnapshot(
            long networkHandle,
            Network androidNetwork,
            NetworkType networkType,
            List<GlobalIpv6Selector.Candidate> ipv6Addresses,
            GlobalIpv6Selector.Candidate endpointCandidate,
            List<InetAddress> dnsServers,
            boolean validated,
            boolean metered) {
        this.networkHandle = networkHandle;
        this.androidNetwork = androidNetwork;
        this.networkType = Objects.requireNonNull(networkType, "networkType");
        this.ipv6Addresses = immutableCopy(ipv6Addresses);
        this.endpointCandidate = endpointCandidate;
        this.dnsServers = immutableCopy(dnsServers);
        this.validated = validated;
        this.metered = metered;
    }

    /** 返回 Android 网络句柄。 */
    public long getNetworkHandle() {
        return networkHandle;
    }

    /** 返回系统回调中的 Android Network；纯 JVM 单元测试快照可能没有该对象。 */
    public Optional<Network> getAndroidNetwork() {
        return Optional.ofNullable(androidNetwork);
    }

    /** 返回网络类型。 */
    public NetworkType getNetworkType() {
        return networkType;
    }

    /** 返回不可变 IPv6 地址列表。 */
    public List<GlobalIpv6Selector.Candidate> getIpv6Addresses() {
        return ipv6Addresses;
    }

    /** 返回选中的公网 Endpoint；不存在健康 GUA 时为空。 */
    public Optional<GlobalIpv6Selector.Candidate> getEndpointCandidate() {
        return Optional.ofNullable(endpointCandidate);
    }

    /** 返回不可变 DNS 服务器列表（可能同时包含 IPv4 和 IPv6）。 */
    public List<InetAddress> getDnsServers() {
        return dnsServers;
    }

    /** 返回系统是否验证该网络可访问互联网。 */
    public boolean isValidated() {
        return validated;
    }

    /** 返回系统是否认为该网络按流量计费。 */
    public boolean isMetered() {
        return metered;
    }

    /** 返回不含凭据的网络摘要，适合经安全日志器输出。 */
    @Override
    public String toString() {
        return "NetworkSnapshot{networkHandle=" + networkHandle
                + ", networkType=" + networkType
                + ", ipv6Count=" + ipv6Addresses.size()
                + ", endpoint=" + (endpointCandidate == null
                        ? "none" : endpointCandidate.getAddress().getHostAddress())
                + ", dnsCount=" + dnsServers.size()
                + ", validated=" + validated
                + ", metered=" + metered
                + "}";
    }

    /** 复制列表并返回不可变视图，避免调用方在快照创建后修改内容。 */
    private static <T> List<T> immutableCopy(List<T> source) {
        Objects.requireNonNull(source, "source");
        return Collections.unmodifiableList(new ArrayList<>(source));
    }
}
