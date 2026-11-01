/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.storage;

import android.content.Context;

import com.wireguard.crypto.Key;
import com.wireguard.crypto.KeyPair;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import dev.lan.atticusvpn.server.core.AddressAllocator;
import dev.lan.atticusvpn.server.core.CrossClientNamePolicy;
import dev.lan.atticusvpn.server.model.ServerState;
import dev.lan.atticusvpn.server.model.StoredPeer;

/**
 * 服务器状态的唯一写入口。
 *
 * <p>主状态由一个 AndroidKeyStore AES key 保护；客户端私钥则交给每 Peer 独立 vault。
 * 所有 mutating 方法都在同一监视器内完成，防止 GUI、网络回调和前台服务交错写坏状态。
 * 本类不使用 SharedPreferences/SQLite，也不会把密钥放入 Intent、通知或日志。</p>
 */
public final class EncryptedStateRepository {
    private static final String MAIN_ALIAS = "dev.lan.atticusvpn.server.state.aes.v1";
    private static final int SCHEMA = 1;

    private final KeystoreBlob stateBlob;
    private final PeerExportVault exportVault;
    private final SecureRandom random = new SecureRandom();
    private ServerState state;

    public EncryptedStateRepository(final Context context) {
        final Context application = context.getApplicationContext();
        stateBlob = new KeystoreBlob(
                application,
                MAIN_ALIAS,
                "server-state.enc",
                "server-state");
        exportVault = new PeerExportVault(application);
    }

    public synchronized void initialize() throws Exception {
        if (stateBlob.exists()) {
            state = decode(stateBlob.read());
        } else {
            state = createFreshState();
            persist();
        }
        // Orphan cleanup is scheduled independently after initialization succeeds.
    }

    public synchronized ServerState state() {
        requireReady();
        return state;
    }

    public synchronized StoredPeer addPeer(final String name) throws Exception {
        requireReady();
        CrossClientNamePolicy.requireNew(
                name,
                state.peers().stream().map(StoredPeer::name).collect(Collectors.toList()));
        final Set<Integer> used = new HashSet<>();
        for (final StoredPeer peer : state.peers()) {
            used.add(peer.clientId());
        }
        final int clientId = new AddressAllocator().allocate(used).clientId();
        final KeyPair client = new KeyPair();
        final byte[] pskBytes = new byte[32];
        random.nextBytes(pskBytes);
        final Key psk = Key.fromBytes(pskBytes);
        Arrays.fill(pskBytes, (byte) 0);
        final String peerId = randomIdentifier();
        final String privateKey = client.getPrivateKey().toBase64();
        final StoredPeer peer = new StoredPeer(
                peerId,
                name,
                clientId,
                client.getPublicKey().toBase64(),
                psk.toBase64(),
                true,
                false,
                Instant.now().getEpochSecond(),
                0L);
        // 先写独立 vault，再提交授权 Peer；若第二步失败，孤立 vault 不会进入 runtime。
        exportVault.put(peerId, privateKey);
        state.addPeer(peer);
        try {
            persist();
        } catch (final Exception exception) {
            state.removePeer(peer);
            exportVault.delete(peerId);
            throw exception;
        }
        return peer;
    }

    public synchronized void setEnabled(final String id, final boolean enabled) throws Exception {
        final StoredPeer peer = requirePeer(id);
        final boolean previousEnabled = peer.enabled();
        peer.setEnabled(enabled);
        try {
            persist();
        } catch (final Exception error) {
            peer.restoreMutableStateAfterPersistenceFailure(
                    previousEnabled, peer.sealed(), peer.sealedAtUnix());
            throw error;
        }
    }

    public synchronized void seal(final String id) throws Exception {
        final StoredPeer peer = requirePeer(id);
        if (!peer.sealed()) {
            final boolean previousEnabled = peer.enabled();
            final boolean previousSealed = peer.sealed();
            final long previousSealedAt = peer.sealedAtUnix();
            peer.markSealed(Instant.now().getEpochSecond());
            try {
                persist();
            } catch (final Exception error) {
                peer.restoreMutableStateAfterPersistenceFailure(
                        previousEnabled, previousSealed, previousSealedAt);
                throw error;
            }
        }
        // persist 成功后才删；即使这里失败，isExportable 也立即为 false，启动时继续清理。
        if (exportVault.exists(id)) {
            exportVault.delete(id);
        }
    }

    /** Only changes durable authorization. Never performs key cleanup or runtime I/O. */
    public synchronized void revokePeerAuthorization(final String id) throws Exception {
        final StoredPeer peer = requirePeer(id);
        final int previousIndex = state.peers().indexOf(peer);
        state.removePeer(peer);
        try {
            persist();
        } catch (final Exception error) {
            // 撤销尚未提交，必须恢复进程内授权；否则 runtime 会与磁盘状态分叉。
            state.restorePeerAfterPersistenceFailure(previousIndex, peer);
            throw error;
        }
    }

    /** Keystore cleanup I/O never holds the authorization repository monitor. */
    public void cleanupRevokedKeys() throws Exception {
        synchronized (this) { requireReady(); }
        exportVault.cleanupOrphans(this::retainExport);
    }

    private synchronized boolean retainExport(String id) {
        // addPeer holds this monitor until its vault and authorization are committed together.
        // A cleanup pass therefore cannot delete an export while addPeer is committing it.
        return state.peers().stream().anyMatch(peer -> peer.id().equals(id) && !peer.sealed());
    }

    public synchronized boolean isExportable(final String id) {
        final StoredPeer peer = requirePeer(id);
        return !peer.sealed() && exportVault.exists(id);
    }

    public synchronized String readClientPrivateKey(final String id) throws Exception {
        final StoredPeer peer = requirePeer(id);
        if (peer.sealed()) {
            throw new IllegalStateException("PEER_SEALED");
        }
        return exportVault.get(id);
    }

    public synchronized void saveSettings() throws Exception { persist(); }

    private ServerState createFreshState() {
        final KeyPair server = new KeyPair();
        final ServerState fresh = new ServerState();
        fresh.serverPrivateKey = server.getPrivateKey().toBase64();
        fresh.serverPublicKey = server.getPublicKey().toBase64();
        return fresh;
    }

    private StoredPeer requirePeer(final String id) {
        requireReady();
        return state.peers().stream()
                .filter(peer -> peer.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("PEER_NOT_FOUND"));
    }

    private void requireReady() {
        if (state == null) {
            throw new IllegalStateException("REPOSITORY_NOT_INITIALIZED");
        }
    }

    private void persist() throws Exception {
        final byte[] plain = encode(state);
        try {
            stateBlob.write(plain);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    private static byte[] encode(final ServerState value) throws Exception {
        final JSONObject root = new JSONObject();
        root.put("schema", SCHEMA);
        root.put("serverPrivateKey", value.serverPrivateKey);
        root.put("serverPublicKey", value.serverPublicKey);
        root.put("endpointHost", value.endpointHost);
        root.put("listenPort", value.listenPort);
        root.put("mtu", value.mtu);
        root.put("dnsEnabled", value.dnsEnabled);
        root.put("dnsMode", value.dnsMode);
        root.put("customDns", value.customDns);
        root.put("ddnsProvider", value.ddnsProvider);
        root.put("ddnsHostname", value.ddnsHostname);
        root.put("ddnsAccount", value.ddnsAccount);
        root.put("ddnsSecret", value.ddnsSecret);
        root.put("ddnsZone", value.ddnsZone);
        root.put("ddnsRecord", value.ddnsRecord);
        root.put("ddnsCustomUrl", value.ddnsCustomUrl);
        final JSONArray peers = new JSONArray();
        for (final StoredPeer peer : value.peers()) {
            peers.put(new JSONObject()
                    .put("id", peer.id())
                    .put("name", peer.name())
                    .put("clientId", peer.clientId())
                    .put("publicKey", peer.publicKey())
                    .put("presharedKey", peer.presharedKey())
                    .put("enabled", peer.enabled())
                    .put("sealed", peer.sealed())
                    .put("createdAt", peer.createdAtUnix())
                    .put("sealedAt", peer.sealedAtUnix()));
        }
        root.put("peers", peers);
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static ServerState decode(final byte[] plain) throws Exception {
        if (plain == null || plain.length == 0 || plain.length > 512 * 1024) {
            throw new SecurityException("state payload invalid");
        }
        try {
            final JSONObject root = new JSONObject(new String(plain, StandardCharsets.UTF_8));
            if (root.getInt("schema") != SCHEMA) {
                throw new SecurityException("state schema unsupported");
            }
            final ServerState value = new ServerState();
            value.serverPrivateKey = root.getString("serverPrivateKey");
            value.serverPublicKey = root.getString("serverPublicKey");
            Key.fromBase64(value.serverPrivateKey);
            Key.fromBase64(value.serverPublicKey);
            // schema v1 的早期开发快照没有 endpointHost；缺失时保持向后兼容。
            value.endpointHost = root.optString("endpointHost", "");
            value.listenPort = root.getInt("listenPort");
            value.mtu = root.getInt("mtu");
            value.dnsEnabled = root.getBoolean("dnsEnabled");
            value.dnsMode = root.getString("dnsMode");
            value.customDns = root.getString("customDns");
            value.ddnsProvider = root.getString("ddnsProvider");
            value.ddnsHostname = root.getString("ddnsHostname");
            value.ddnsAccount = root.getString("ddnsAccount");
            value.ddnsSecret = root.getString("ddnsSecret");
            value.ddnsZone = root.getString("ddnsZone");
            value.ddnsRecord = root.getString("ddnsRecord");
            value.ddnsCustomUrl = root.getString("ddnsCustomUrl");
            final JSONArray peers = root.getJSONArray("peers");
            if (peers.length() > 253) {
                throw new SecurityException("too many peers");
            }
            for (int index = 0; index < peers.length(); index++) {
                final JSONObject peer = peers.getJSONObject(index);
                value.addPeer(new StoredPeer(
                        peer.getString("id"),
                        peer.getString("name"),
                        peer.getInt("clientId"),
                        peer.getString("publicKey"),
                        peer.getString("presharedKey"),
                        peer.getBoolean("enabled"),
                        peer.getBoolean("sealed"),
                        peer.getLong("createdAt"),
                        peer.getLong("sealedAt")));
            }
            return value;
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    private String randomIdentifier() {
        final byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        final StringBuilder text = new StringBuilder(32);
        for (final byte value : bytes) {
            text.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        }
        Arrays.fill(bytes, (byte) 0);
        return text.toString();
    }
}
