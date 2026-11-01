/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 供 Service、通知与 GUI 共享的不可变、非敏感运行状态。 */
public final class RuntimeStatus {
    public enum Phase { STOPPED, STARTING, WAITING_NETWORK, RUNNING, STOPPING, ERROR }

    private final Phase phase;
    private final String safeCode;
    private final String endpointAddress;
    private final int enabledPeers;
    private final String networkType;
    private final String ddnsStatus;
    private final List<PeerRuntime> peers;
    private final int listenPort;
    private final String endpoint;
    private final boolean dnsEnabled;
    private final String ddnsProvider;
    private final NetworkCheckResult networkCheck;

    public RuntimeStatus(
            final Phase phase,
            final String safeCode,
            final String endpointAddress,
            final int enabledPeers) {
        this(phase, safeCode, endpointAddress, enabledPeers, "UNKNOWN", "DISABLED",
                Collections.emptyList(), 0, "", false);
    }

    public RuntimeStatus(
            final Phase phase,
            final String safeCode,
            final String endpointAddress,
            final int enabledPeers,
            final String networkType,
            final String ddnsStatus,
            final List<PeerRuntime> peers,
            final int listenPort,
            final String endpoint,
            final boolean dnsEnabled) {
        this(phase, safeCode, endpointAddress, enabledPeers, networkType, ddnsStatus,
                peers, listenPort, endpoint, dnsEnabled, "disabled");
    }

    public RuntimeStatus(
            final Phase phase,
            final String safeCode,
            final String endpointAddress,
            final int enabledPeers,
            final String networkType,
            final String ddnsStatus,
            final List<PeerRuntime> peers,
            final int listenPort,
            final String endpoint,
            final boolean dnsEnabled,
            final String ddnsProvider) {
        this(phase, safeCode, endpointAddress, enabledPeers, networkType, ddnsStatus, peers,
                listenPort, endpoint, dnsEnabled, ddnsProvider, NetworkCheckResult.IDLE);
    }

    private RuntimeStatus(
            final Phase phase, final String safeCode, final String endpointAddress,
            final int enabledPeers, final String networkType, final String ddnsStatus,
            final List<PeerRuntime> peers, final int listenPort, final String endpoint,
            final boolean dnsEnabled, final String ddnsProvider, final NetworkCheckResult networkCheck) {
        this.phase = Objects.requireNonNull(phase, "phase");
        this.safeCode = safeCode == null ? "" : safeCode;
        this.endpointAddress = endpointAddress == null ? "" : endpointAddress;
        this.enabledPeers = Math.max(0, enabledPeers);
        this.networkType = networkType == null ? "UNKNOWN" : networkType;
        this.ddnsStatus = ddnsStatus == null ? "UNKNOWN" : ddnsStatus;
        this.peers = Collections.unmodifiableList(new ArrayList<>(peers));
        this.listenPort = Math.max(0, listenPort);
        this.endpoint = endpoint == null ? "" : endpoint;
        this.dnsEnabled = dnsEnabled;
        this.ddnsProvider = ddnsProvider == null ? "disabled" : ddnsProvider;
        this.networkCheck = Objects.requireNonNull(networkCheck, "networkCheck");
    }

    /** 检测结果与当前运行快照一起发布，避免页面旋转时显示旧网络的成功结果。 */
    public RuntimeStatus withNetworkCheck(final NetworkCheckResult result) {
        return new RuntimeStatus(phase, safeCode, endpointAddress, enabledPeers, networkType,
                ddnsStatus, peers, listenPort, endpoint, dnsEnabled, ddnsProvider, result);
    }

    public NetworkCheckResult networkCheck() { return networkCheck; }

    public static RuntimeStatus stopped() {
        return new RuntimeStatus(Phase.STOPPED, "", "", 0);
    }

    public Phase phase() { return phase; }
    public String safeCode() { return safeCode; }
    public String endpointAddress() { return endpointAddress; }
    public int enabledPeers() { return enabledPeers; }
    public String networkType() { return networkType; }
    public String ddnsStatus() { return ddnsStatus; }
    public List<PeerRuntime> peers() { return peers; }
    public int listenPort() { return listenPort; }
    public String endpoint() { return endpoint; }
    public boolean dnsEnabled() { return dnsEnabled; }
    public String ddnsProvider() { return ddnsProvider; }

    /** 已把 native 公钥映射为本地名称后的脱敏 Peer 统计。 */
    public static final class PeerRuntime {
        private final String name;
        private final long latestHandshakeUnix;
        private final long receiveBytes;
        private final long transmitBytes;

        public PeerRuntime(
                final String name,
                final long latestHandshakeUnix,
                final long receiveBytes,
                final long transmitBytes) {
            this.name = Objects.requireNonNull(name, "name");
            this.latestHandshakeUnix = Math.max(0L, latestHandshakeUnix);
            this.receiveBytes = Math.max(0L, receiveBytes);
            this.transmitBytes = Math.max(0L, transmitBytes);
        }

        public String name() { return name; }
        public long latestHandshakeUnix() { return latestHandshakeUnix; }
        public long receiveBytes() { return receiveBytes; }
        public long transmitBytes() { return transmitBytes; }

        /** 最近 180 秒内握手过即视为在线；这是展示推断，不是连接会话承诺。 */
        public boolean isOnline(final long nowUnixSeconds) {
            return latestHandshakeUnix > 0L
                    && nowUnixSeconds >= latestHandshakeUnix
                    && nowUnixSeconds - latestHandshakeUnix <= 180L;
        }

        @Override
        public String toString() {
            return "PeerRuntime[name=" + name + ", handshake=" + latestHandshakeUnix
                    + ", rx=" + receiveBytes + ", tx=" + transmitBytes + ']';
        }
    }

    @Override
    public String toString() {
        return "RuntimeStatus[phase=" + phase + ", code=" + safeCode
                + ", endpointAvailable=" + !endpointAddress.isEmpty()
                + ", enabledPeers=" + enabledPeers
                + ", network=" + networkType + ", ddns=" + ddnsStatus + ']';
    }
}
