/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.core;

import java.util.Objects;

/**
 * 生成当前 AtticusVPN Windows/Android 客户端共同接受的标准 WireGuard 配置。
 *
 * <p>返回值包含客户端私钥与 PSK，调用方必须使用 try-with-resources，并且只通过
 * SAF 等明确的安全导出边界写出。渲染器不会加入脚本钩子、Table、SaveConfig、
 * Android 专属字段或任何自定义协议字段。</p>
 */
public final class ClientConfigRenderer {
    public static final int MAX_CONFIGURATION_BYTES = 64 * 1024;

    private final AllowedIpsPolicy allowedIpsPolicy;

    public ClientConfigRenderer() {
        this(new AllowedIpsPolicy());
    }

    public ClientConfigRenderer(final AllowedIpsPolicy allowedIpsPolicy) {
        this.allowedIpsPolicy = Objects.requireNonNull(allowedIpsPolicy, "allowedIpsPolicy");
    }

    public SecretBytes render(
            final PeerRecord peer,
            final ServerSettings settings,
            final String serverPublicKey) {
        return render(peer, settings, serverPublicKey, ClientExportOptions.defaults());
    }

    /** 所有导出格式共用同一渲染器；可选覆盖值只影响本次客户端文件。 */
    public SecretBytes render(final PeerRecord peer, final ServerSettings settings,
            final String serverPublicKey, final ClientExportOptions options) {
        Objects.requireNonNull(peer, "peer");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(options, "options");
        final String checkedServerPublicKey = WireGuardKeyFormat.requirePublicKey(serverPublicKey);
        if (!peer.isExportable()) {
            throw new IllegalStateException("sealed peer cannot be exported");
        }

        try (SecretBytes privateKey = peer.copyClientPrivateKey();
             SecretBytes presharedKey = peer.copyPresharedKey();
             SecretByteAccumulator output = new SecretByteAccumulator()) {
            WireGuardKeyFormat.requireSecretKey(privateKey);
            WireGuardKeyFormat.requireSecretKey(presharedKey);

            output.appendAscii("# AtticusVPN client: " + peer.name() + "\n");
            output.appendAscii("# Author: TianzeL\n");
            output.appendAscii("[Interface]\n");
            output.appendAscii("PrivateKey = ");
            output.appendSecret(privateKey);
            output.appendAscii("\n");
            output.appendAscii("Address = " + options.addresses(peer) + "\n");
            final String dns = options.dns(settings);
            if (!dns.isEmpty()) {
                output.appendAscii("DNS = " + dns + "\n");
            }
            output.appendAscii("MTU = " + settings.mtu() + "\n");
            output.appendAscii("\n[Peer]\n");
            output.appendAscii("PublicKey = " + checkedServerPublicKey + "\n");
            output.appendAscii("PresharedKey = ");
            output.appendSecret(presharedKey);
            output.appendAscii("\n");
            output.appendAscii("AllowedIPs = " + options.allowedIps(peer, settings, allowedIpsPolicy) + "\n");
            output.appendAscii("Endpoint = " + settings.endpoint() + "\n");
            output.appendAscii("PersistentKeepalive = "
                    + ServerSettings.PERSISTENT_KEEPALIVE_SECONDS + "\n");

            if (output.size() > MAX_CONFIGURATION_BYTES) {
                throw new IllegalStateException("rendered configuration exceeds 64 KiB");
            }
            return output.finish();
        }
    }
}
