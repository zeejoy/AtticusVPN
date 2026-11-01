/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.config;

import com.wireguard.crypto.Key;
import com.wireguard.crypto.KeyFormatException;
import com.wireguard.crypto.KeyPair;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import dev.lan.atticusvpn.server.core.ClientConfigRenderer;
import dev.lan.atticusvpn.server.core.ClientExportOptions;
import dev.lan.atticusvpn.server.core.PeerRecord;
import dev.lan.atticusvpn.server.core.SecretBytes;
import dev.lan.atticusvpn.server.core.ServerSettings;
import dev.lan.atticusvpn.server.core.TunnelMode;
import dev.lan.atticusvpn.server.model.ServerState;
import dev.lan.atticusvpn.server.model.StoredPeer;

/**
 * 在加密持久状态与两种 WireGuard 文本格式之间建立唯一转换边界。
 *
 * <p>客户端文件是标准 wg-quick {@code .conf}；服务端文本是 wireguard-go UAPI。
 * 本类不缓存、不打印任何输出，调用方必须及时关闭 {@link SecretBytes}。默认不向
 * 客户端下发 {@code ::/0}，仅保留内部 ULA /64；管理员显式覆盖导出路由不增加转发能力。</p>
 */
public final class WireGuardConfigFactory {
    public static final int MAX_UAPI_BYTES = 64 * 1024;

    private final ClientConfigRenderer renderer = new ClientConfigRenderer();

    /** 生成兼容现有 Windows/Android 客户端的标准 WireGuard 配置。 */
    public SecretBytes renderClientConfiguration(
            final ServerState state,
            final StoredPeer peer,
            final String clientPrivateKey,
            final String endpointHost) {
        return renderClientConfiguration(state, peer, clientPrivateKey, endpointHost,
                ClientExportOptions.defaults());
    }

    /** 二维码、PNG、SAF 和剪贴板共用；不会改变持久化设置或 Peer。 */
    public SecretBytes renderClientConfiguration(final ServerState state, final StoredPeer peer,
            final String clientPrivateKey, final String endpointHost, final ClientExportOptions options) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(peer, "peer");
        if (peer.sealed()) throw new IllegalStateException("PEER_SEALED");
        try {
            if (!new KeyPair(Key.fromBase64(clientPrivateKey)).getPublicKey().toBase64()
                    .equals(peer.publicKey())) throw new IllegalArgumentException("CLIENT_KEY_MISMATCH");
        } catch (KeyFormatException error) {
            throw new IllegalArgumentException("INVALID_CLIENT_KEY");
        }
        final ServerSettings settings = new ServerSettings(
                endpointHost,
                state.listenPort,
                state.mtu,
                state.dnsEnabled,
                false);
        try (SecretBytes privateKey = SecretBytes.fromAscii(clientPrivateKey);
             SecretBytes presharedKey = SecretBytes.fromAscii(peer.presharedKey());
             PeerRecord record = PeerRecord.active(
                     peer.name(), peer.clientId(), TunnelMode.FULL, Collections.emptyList(),
                     peer.publicKey(), presharedKey, privateKey,
                     Instant.ofEpochSecond(peer.createdAtUnix()))) {
            return renderer.render(record, settings, state.serverPublicKey, options);
        }
    }

    /**
     * 生成 native-engine 接受的 UAPI。禁用 Peer 不进入运行态；Seal 不会撤销已授权 Peer。
     */
    public String renderServerUapi(final ServerState state) {
        Objects.requireNonNull(state, "state");
        final StringBuilder output = new StringBuilder(1024);
        output.append("private_key=").append(toHex(state.serverPrivateKey)).append('\n');
        output.append("listen_port=").append(state.listenPort).append('\n');
        output.append("replace_peers=true\n");
        for (final StoredPeer peer : state.peers()) {
            if (!peer.enabled()) {
                continue;
            }
            output.append("public_key=").append(toHex(peer.publicKey())).append('\n');
            output.append("preshared_key=").append(toHex(peer.presharedKey())).append('\n');
            output.append("replace_allowed_ips=true\n");
            output.append("allowed_ip=").append(peer.ipv4Cidr()).append('\n');
            output.append("allowed_ip=").append(peer.ipv6Cidr()).append('\n');
        }
        if (output.toString().getBytes(StandardCharsets.US_ASCII).length > MAX_UAPI_BYTES) {
            throw new IllegalStateException("SERVER_UAPI_TOO_LARGE");
        }
        return output.toString();
    }

    private static String toHex(final String base64) {
        try {
            return Key.fromBase64(Objects.requireNonNull(base64, "key")).toHex();
        } catch (final KeyFormatException error) {
            throw new IllegalStateException("INVALID_STORED_KEY");
        }
    }
}
