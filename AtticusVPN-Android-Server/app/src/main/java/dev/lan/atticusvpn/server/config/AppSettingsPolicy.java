/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.config;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import dev.lan.atticusvpn.server.core.EndpointFormatter;
import dev.lan.atticusvpn.server.model.ServerState;

/**
 * GUI 和运行时共用的非秘密设置校验。
 *
 * <p>所有校验都只做本地语法检查，不触发 DNS 查询；这样用户输入不会在点击保存时
 * 意外发送到当前网络。异常仅含固定字段名，不回显用户输入、DDNS token 或账户。</p>
 */
public final class AppSettingsPolicy {
    public static final String DNS_AUTOMATIC = "AUTOMATIC";
    public static final String DNS_CUSTOM = "CUSTOM";

    private static final Set<String> PROVIDERS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    "disabled", "duckdns", "cloudflare", "noip", "dynu", "freedns", "custom")));

    private AppSettingsPolicy() {
    }

    /** 规范化可选 Endpoint；空值表示运行时使用 DDNS 域名或当前公网 IPv6。 */
    public static String normalizeOptionalEndpointHost(final String value) {
        final String candidate = Objects.requireNonNull(value, "endpointHost").trim();
        return candidate.isEmpty() ? "" : EndpointFormatter.requireValidHost(candidate);
    }

    /** 检查监听端口与第一版用户态网关支持的 MTU 范围。 */
    public static void requirePortAndMtu(final int port, final int mtu) {
        EndpointFormatter.requireValidPort(port);
        if (mtu < 1280 || mtu > 1420) {
            throw new IllegalArgumentException("INVALID_MTU");
        }
    }

    /** 只接受两个固定 DNS 模式，避免持久状态被任意字符串改变解释。 */
    public static String normalizeDnsMode(final String value) {
        final String mode = Objects.requireNonNull(value, "dnsMode").trim()
                .toUpperCase(Locale.ROOT);
        if (!DNS_AUTOMATIC.equals(mode) && !DNS_CUSTOM.equals(mode)) {
            throw new IllegalArgumentException("INVALID_DNS_MODE");
        }
        return mode;
    }

    /**
     * 规范化逗号分隔的数字 DNS 地址。禁止主机名，确保 {@link InetAddress} 不会做网络查询。
     */
    public static String normalizeCustomDns(final String value) {
        Objects.requireNonNull(value, "customDns");
        if (value.trim().isEmpty()) {
            return "";
        }
        final LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (final String raw : value.split(",", -1)) {
            final String candidate = raw.trim();
            if (candidate.isEmpty() || candidate.length() > 64 || !isNumericAddressShape(candidate)) {
                throw new IllegalArgumentException("INVALID_CUSTOM_DNS");
            }
            try {
                final InetAddress address = InetAddress.getByName(candidate);
                if (!(address instanceof Inet4Address) && !(address instanceof Inet6Address)) {
                    throw new IllegalArgumentException("INVALID_CUSTOM_DNS");
                }
                String text = address.getHostAddress();
                final int zone = text.indexOf('%');
                normalized.add(zone < 0 ? text : text.substring(0, zone));
            } catch (final UnknownHostException error) {
                throw new IllegalArgumentException("INVALID_CUSTOM_DNS");
            }
        }
        if (normalized.size() > 8) {
            throw new IllegalArgumentException("TOO_MANY_DNS_SERVERS");
        }
        return String.join(",", normalized);
    }

    /** 规范化支持的 DDNS provider 标识。 */
    public static String normalizeProvider(final String value) {
        final String provider = Objects.requireNonNull(value, "provider").trim()
                .toLowerCase(Locale.ROOT);
        if (!PROVIDERS.contains(provider)) {
            throw new IllegalArgumentException("INVALID_DDNS_PROVIDER");
        }
        return provider;
    }

    /** 规范化完整域名；disabled 时允许为空。 */
    public static String normalizeDdnsHostname(final String value, final String provider) {
        final String checkedProvider = normalizeProvider(provider);
        String candidate = Objects.requireNonNull(value, "hostname").trim();
        // DuckDNS 的常见输入就是单个 subdomain；其余 provider 仍要求完整域名。
        if ("duckdns".equals(checkedProvider)
                && !candidate.isEmpty()
                && candidate.indexOf('.') < 0) {
            candidate = candidate + ".duckdns.org";
        }
        if (candidate.endsWith(".")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        if (candidate.isEmpty()) {
            if ("disabled".equals(checkedProvider)) {
                return "";
            }
            throw new IllegalArgumentException("DDNS_HOSTNAME_REQUIRED");
        }
        try {
            final String ascii = IDN.toASCII(candidate, IDN.USE_STD3_ASCII_RULES)
                    .toLowerCase(Locale.ROOT);
            if (ascii.length() > 253 || ascii.indexOf('.') <= 0) {
                throw new IllegalArgumentException("INVALID_DDNS_HOSTNAME");
            }
            for (final String label : ascii.split("\\.", -1)) {
                if (label.isEmpty() || label.length() > 63) {
                    throw new IllegalArgumentException("INVALID_DDNS_HOSTNAME");
                }
            }
            if ("duckdns".equals(checkedProvider)) {
                final String suffix = ".duckdns.org";
                if (!ascii.endsWith(suffix)
                        || ascii.substring(0, ascii.length() - suffix.length()).indexOf('.') >= 0) {
                    throw new IllegalArgumentException("INVALID_DUCKDNS_HOSTNAME");
                }
            }
            return ascii;
        } catch (final IllegalArgumentException error) {
            throw new IllegalArgumentException("INVALID_DDNS_HOSTNAME");
        }
    }

    /**
     * 选择客户端导出使用的 Endpoint。管理员固定值优先，其次 DDNS，最后当前 GUA。
     */
    public static String resolveEndpointHost(
            final ServerState state,
            final String currentGlobalIpv6) {
        Objects.requireNonNull(state, "state");
        if (state.endpointHost != null && !state.endpointHost.trim().isEmpty()) {
            return normalizeOptionalEndpointHost(state.endpointHost);
        }
        final String provider = normalizeProvider(state.ddnsProvider);
        if (!"disabled".equals(provider)
                && state.ddnsHostname != null
                && !state.ddnsHostname.trim().isEmpty()) {
            return EndpointFormatter.requireValidHost(state.ddnsHostname.trim());
        }
        if (currentGlobalIpv6 != null && !currentGlobalIpv6.trim().isEmpty()) {
            return EndpointFormatter.requireValidHost(currentGlobalIpv6.trim());
        }
        throw new IllegalStateException("ENDPOINT_UNAVAILABLE");
    }

    /** 把已规范化 DNS CSV 变成 native-engine 使用的不可变列表。 */
    public static List<String> splitDns(final String normalizedCsv) {
        if (normalizedCsv == null || normalizedCsv.trim().isEmpty()) {
            return Collections.emptyList();
        }
        final List<String> values = new ArrayList<>();
        for (final String value : normalizedCsv.split(",", -1)) {
            values.add(value);
        }
        return Collections.unmodifiableList(values);
    }

    private static boolean isNumericAddressShape(final String value) {
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (!Character.isDigit(character)
                    && character != '.' && character != ':'
                    && !(character >= 'a' && character <= 'f')
                    && !(character >= 'A' && character <= 'F')) {
                return false;
            }
        }
        return value.indexOf('.') >= 0 || value.indexOf(':') >= 0;
    }
}
