/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.lan.atticusvpn.server.core.ServerSettings;

/** 应用的加密持久状态；敏感字段只会交给 EncryptedStateRepository。 */
public final class ServerState {
    public String serverPrivateKey;
    public String serverPublicKey;
    /** 客户端配置优先使用的固定域名或公网 IPv6；为空时可使用 DDNS 域名/当前 GUA。 */
    public String endpointHost = "";
    public int listenPort = ServerSettings.DEFAULT_LISTEN_PORT;
    public int mtu = ServerSettings.DEFAULT_MTU;
    public boolean dnsEnabled = true;
    public String dnsMode = "AUTOMATIC";
    public String customDns = "";
    public String ddnsProvider = "Disabled";
    public String ddnsHostname = "";
    public String ddnsAccount = "";
    public String ddnsSecret = "";
    public String ddnsZone = "";
    public String ddnsRecord = "";
    public String ddnsCustomUrl = "";
    private final List<StoredPeer> peers = new ArrayList<>();

    public List<StoredPeer> peers() {
        return Collections.unmodifiableList(peers);
    }

    public void addPeer(final StoredPeer peer) { peers.add(peer); }
    public boolean removePeer(final StoredPeer peer) { return peers.remove(peer); }

    /** 仅供 repository 在删除持久化失败时按原位置恢复授权 Peer。 */
    public void restorePeerAfterPersistenceFailure(final int index, final StoredPeer peer) {
        if (index < 0 || index > peers.size() || peers.contains(peer)) {
            throw new IllegalArgumentException("invalid peer rollback");
        }
        peers.add(index, peer);
    }
}
