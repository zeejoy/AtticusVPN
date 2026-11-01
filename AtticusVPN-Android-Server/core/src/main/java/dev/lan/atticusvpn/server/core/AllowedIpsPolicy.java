/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.core;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** 生成与 Linux 0.1.0 相同的 full/LAN AllowedIPs。 */
public final class AllowedIpsPolicy {
    private static final Pattern IPV4_SHAPE = Pattern.compile("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$");
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9A-Fa-f:.]+$");

    public String render(final PeerRecord peer, final ServerSettings settings) {
        Objects.requireNonNull(peer, "peer");
        Objects.requireNonNull(settings, "settings");
        if (peer.mode() == TunnelMode.FULL) {
            return settings.fullTunnelIpv6()
                    ? "0.0.0.0/0, ::/0"
                    : "0.0.0.0/0, " + ServerSettings.VPN_IPV6_NETWORK;
        }

        final List<String> routes = new ArrayList<>();
        routes.add(ServerSettings.VPN_IPV4_NETWORK);
        routes.add(ServerSettings.VPN_IPV6_NETWORK);
        routes.addAll(normalizeLanRoutes(peer.lanRoutes()));
        return String.join(", ", routes);
    }

    /** 验证、去重并保留管理员输入顺序；LAN 模式不允许任何默认路由。 */
    public static List<String> normalizeLanRoutes(final Collection<String> routes) {
        Objects.requireNonNull(routes, "routes");
        final Map<String, String> unique = new LinkedHashMap<>();
        for (final String route : routes) {
            final ParsedCidr parsed = parseCidr(route);
            if (parsed.prefixLength() == 0) {
                throw new IllegalArgumentException("LAN routes must not contain a default route");
            }
            final String key = route.toLowerCase(Locale.ROOT);
            if (!route.equals(ServerSettings.VPN_IPV4_NETWORK)
                    && !route.equalsIgnoreCase(ServerSettings.VPN_IPV6_NETWORK)) {
                unique.putIfAbsent(key, route);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(unique.values()));
    }

    static ParsedCidr parseCidr(final String cidr) {
        if (cidr == null
                || cidr.isEmpty()
                || !cidr.equals(cidr.trim())
                || cidr.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("invalid CIDR");
        }
        final int slash = cidr.indexOf('/');
        if (slash <= 0 || slash != cidr.lastIndexOf('/') || slash == cidr.length() - 1) {
            throw new IllegalArgumentException("invalid CIDR");
        }
        final String addressText = cidr.substring(0, slash);
        final int prefix;
        try {
            prefix = Integer.parseInt(cidr.substring(slash + 1));
        } catch (final NumberFormatException ignored) {
            throw new IllegalArgumentException("invalid CIDR prefix");
        }

        final InetAddress address;
        try {
            if (addressText.indexOf(':') >= 0) {
                if (!IPV6_CHARS.matcher(addressText).matches()) {
                    throw new IllegalArgumentException("invalid IPv6 CIDR");
                }
            } else {
                validateIpv4Literal(addressText);
            }
            address = InetAddress.getByName(addressText);
        } catch (final UnknownHostException ignored) {
            throw new IllegalArgumentException("invalid CIDR address");
        }

        if (address instanceof Inet4Address) {
            if (prefix < 0 || prefix > 32) {
                throw new IllegalArgumentException("invalid IPv4 prefix");
            }
            return new ParsedCidr(false, prefix);
        }
        if (address instanceof Inet6Address) {
            if (prefix < 0 || prefix > 128) {
                throw new IllegalArgumentException("invalid IPv6 prefix");
            }
            return new ParsedCidr(true, prefix);
        }
        throw new IllegalArgumentException("unsupported address family");
    }

    /**
     * 只接受四段十进制 IPv4 字面量。InetAddress 也接受 127.1 等历史缩写，
     * 但这些写法不属于 AtticusVPN 生成配置所用的规范格式。
     */
    private static void validateIpv4Literal(final String addressText) {
        if (!IPV4_SHAPE.matcher(addressText).matches()) {
            throw new IllegalArgumentException("invalid IPv4 CIDR");
        }
        for (final String octet : addressText.split("\\.", -1)) {
            final int value;
            try {
                value = Integer.parseInt(octet);
            } catch (final NumberFormatException ignored) {
                throw new IllegalArgumentException("invalid IPv4 CIDR");
            }
            if (value > 255) {
                throw new IllegalArgumentException("invalid IPv4 CIDR");
            }
        }
    }

    record ParsedCidr(boolean ipv6, int prefixLength) {
    }
}
