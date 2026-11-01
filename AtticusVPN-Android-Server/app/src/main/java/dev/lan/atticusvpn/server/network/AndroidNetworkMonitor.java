/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import dev.lan.atticusvpn.server.BuildConfig;

/**
 * 使用 {@link ConnectivityManager#registerDefaultNetworkCallback} 观察 Android 默认网络。
 *
 * <p>监听器会收到 {@code onAvailable}、{@code onLost}、
 * {@code onLinkPropertiesChanged} 导出的快照，内容包括传输类型、IPv6 地址状态、
 * 选中的 Endpoint 和 DNS。调用方必须在不再需要时调用 {@link #stop()}，避免泄漏回调。</p>
 */
public final class AndroidNetworkMonitor implements AutoCloseable {
    // Linux/Android if_addr.h 标志值。使用稳定内核 ABI，避免不同 SDK 对 OsConstants 的暴露差异。
    private static final int IFA_F_TEMPORARY = 0x01;
    private static final int IFA_F_OPTIMISTIC = 0x04;
    private static final int IFA_F_DADFAILED = 0x08;
    private static final int IFA_F_DEPRECATED = 0x20;
    private static final int IFA_F_TENTATIVE = 0x40;

    /** 接收默认网络快照和丢失事件。 */
    public interface Listener {
        /** 默认网络可用或属性变化时调用。 */
        void onNetworkSnapshot(NetworkSnapshot snapshot);

        /** 当前默认网络丢失时调用。 */
        void onNetworkLost(long networkHandle);
    }

    private final ConnectivityManager connectivityManager;
    private final GlobalIpv6Selector selector;
    private final Object lifecycleLock = new Object();

    private Listener listener;
    private final NetworkCallbackState<Network, NetworkCapabilities, LinkData> state =
            new NetworkCallbackState<>();
    private ConnectivityManager.NetworkCallback callback;

    /**
     * 创建默认网络观察器。
     *
     * @param context Android Context；内部只保存 application context 的系统服务
     * @param selector IPv6 Endpoint 选择器
     */
    public AndroidNetworkMonitor(Context context, GlobalIpv6Selector selector) {
        Objects.requireNonNull(context, "context");
        this.selector = Objects.requireNonNull(selector, "selector");
        ConnectivityManager manager =
                (ConnectivityManager) context.getApplicationContext()
                        .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            throw new IllegalStateException("系统未提供 ConnectivityManager。");
        }
        this.connectivityManager = manager;
    }

    /**
     * 注册默认网络回调。
     *
     * @param listener 事件接收者
     * @throws IllegalStateException 重复启动时抛出
     * @throws SecurityException 应用缺少 ACCESS_NETWORK_STATE 时由系统抛出
     */
    public void start(Listener listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (lifecycleLock) {
            if (callback != null) {
                throw new IllegalStateException("AndroidNetworkMonitor 已启动。");
            }
            this.listener = listener;
            this.callback = createCallback();
            connectivityManager.registerDefaultNetworkCallback(callback);
        }
    }

    /** 注销网络回调；可重复调用。 */
    public void stop() {
        synchronized (lifecycleLock) {
            if (callback == null) {
                return;
            }
            try {
                connectivityManager.unregisterNetworkCallback(callback);
            } catch (IllegalArgumentException ignored) {
                // 系统已注销该回调时视为停止成功。
            }
            callback = null;
            listener = null;
            state.clear();
        }
    }

    /** 等同于 {@link #stop()}。 */
    @Override
    public void close() {
        stop();
    }

    private ConnectivityManager.NetworkCallback createCallback() {
        return new ConnectivityManager.NetworkCallback() {
            /**
             * 先清空旧网络数据，再等待系统的能力和链路属性回调。
             * 不能在回调内重新同步查询 ConnectivityManager：查询可能返回旧值或 null。
             */
            @Override
            public void onAvailable(Network network) {
                synchronized (lifecycleLock) {
                    if (callback != this) return;
                    state.available(network);
                    emitSnapshotLocked(network);
                }
            }

            /** 只对当前默认网络发送丢失事件，忽略切换过程中的旧网络迟到回调。 */
            @Override
            public void onLost(Network network) {
                synchronized (lifecycleLock) {
                    if (callback != this || !state.lost(network)) {
                        return;
                    }
                    // Listener 只做非阻塞投递；在锁内调用可保证旧 lost 先于新 available。
                    if (listener != null) {
                        listener.onNetworkLost(network.getNetworkHandle());
                    }
                }
            }

            /** IPv6 地址、前缀或 DNS 变化时使用回调提供的最新 LinkProperties。 */
            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                synchronized (lifecycleLock) {
                    if (callback != this || !state.links(network, new LinkData(linkProperties))) {
                        return;
                    }
                    emitSnapshotLocked(network);
                }
            }

            /** 网络验证或传输类型变化时重新输出完整快照。 */
            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                synchronized (lifecycleLock) {
                    if (callback != this || !state.capabilities(
                            network, new NetworkCapabilities(capabilities))) {
                        return;
                    }
                    emitSnapshotLocked(network);
                }
            }
        };
    }

    /** 在生命周期锁内把回调提供的数据合并为不可变快照，不进行同步系统查询。 */
    private void emitSnapshotLocked(Network network) {
        if (listener == null || !state.isCurrent(network)) {
            return;
        }
        NetworkCapabilities capabilities = state.capabilities();
        LinkData links = state.links();
        List<GlobalIpv6Selector.Candidate> candidates = links == null
                ? Collections.emptyList() : links.candidates;
        GlobalIpv6Selector.Candidate endpoint = selector.selectEndpoint(candidates).orElse(null);
        List<InetAddress> dnsServers = links == null ? Collections.emptyList() : links.dnsServers;

        boolean validated = capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        boolean metered = capabilities == null
                || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        NetworkSnapshot snapshot = new NetworkSnapshot(
                network.getNetworkHandle(),
                network,
                classifyNetworkType(capabilities),
                candidates,
                endpoint,
                dnsServers,
                validated,
                metered);
        if (BuildConfig.DEBUG) {
            // 仅开发包输出计数与布尔值，不记录地址、SSID、密钥或 DNS/DDNS 凭据。
            Log.d("AtticusNetwork", "validated=" + validated
                    + " ipv6Count=" + candidates.size() + " endpoint=" + (endpoint != null));
        }
        // 与 onLost、stop 串行化投递；监听器只做非阻塞任务投递。
        listener.onNetworkSnapshot(snapshot);
    }

    /** 只复制实际需要的地址/DNS 值，不调用 LinkProperties 的隐藏复制构造器。 */
    private static final class LinkData {
        private final List<GlobalIpv6Selector.Candidate> candidates;
        private final List<InetAddress> dnsServers;

        private LinkData(final LinkProperties properties) {
            candidates = Collections.unmodifiableList(extractIpv6(properties));
            dnsServers = Collections.unmodifiableList(new ArrayList<>(properties.getDnsServers()));
        }
    }

    /** 从 LinkProperties 提取 IPv6 地址和内核状态标志。 */
    private static List<GlobalIpv6Selector.Candidate> extractIpv6(LinkProperties properties) {
        if (properties == null) {
            return Collections.emptyList();
        }
        List<GlobalIpv6Selector.Candidate> result = new ArrayList<>();
        for (LinkAddress linkAddress : properties.getLinkAddresses()) {
            InetAddress address = linkAddress.getAddress();
            if (!(address instanceof Inet6Address)) {
                continue;
            }
            int flags = linkAddress.getFlags();
            if (BuildConfig.DEBUG) {
                // 故障定位只记录内核状态位和作用域，不输出实际 IPv6 地址。
                Log.d("AtticusNetwork", "ipv6Flags=" + flags
                        + " scope=" + GlobalIpv6Selector.classify((Inet6Address) address));
            }
            result.add(new GlobalIpv6Selector.Candidate(
                    (Inet6Address) address,
                    linkAddress.getPrefixLength(),
                    hasFlag(flags, IFA_F_TEMPORARY),
                    hasFlag(flags, IFA_F_DEPRECATED),
                    hasFlag(flags, IFA_F_TENTATIVE),
                    hasFlag(flags, IFA_F_DADFAILED),
                    hasFlag(flags, IFA_F_OPTIMISTIC)));
        }
        return result;
    }

    /** 根据 NetworkCapabilities 选择一个主要传输类型。 */
    private static NetworkSnapshot.NetworkType classifyNetworkType(NetworkCapabilities capabilities) {
        if (capabilities == null) {
            return NetworkSnapshot.NetworkType.UNKNOWN;
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return NetworkSnapshot.NetworkType.VPN;
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return NetworkSnapshot.NetworkType.WIFI;
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return NetworkSnapshot.NetworkType.CELLULAR;
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return NetworkSnapshot.NetworkType.ETHERNET;
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) {
            return NetworkSnapshot.NetworkType.BLUETOOTH;
        }
        return NetworkSnapshot.NetworkType.OTHER;
    }

    /** 判断 Android/Linux 地址状态位是否存在。 */
    private static boolean hasFlag(int flags, int expected) {
        return (flags & expected) != 0;
    }
}
