/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.core;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.regex.Pattern;

/** 标准 WireGuard Endpoint 的纯文本验证与 IPv6 方括号格式化。 */
public final class EndpointFormatter {
    private static final Pattern HOSTNAME_CHARS = Pattern.compile("^[A-Za-z0-9.-]+$");
    private static final Pattern IPV4 = Pattern.compile("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$");
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9A-Fa-f:.]+$");

    private EndpointFormatter() {
    }

    public static String format(final String host, final int port) {
        final String checkedHost = requireValidHost(host);
        requireValidPort(port);
        return checkedHost.indexOf(':') >= 0
                ? '[' + checkedHost + "]:" + port
                : checkedHost + ':' + port;
    }

    public static String requireValidHost(final String host) {
        Objects.requireNonNull(host, "host");
        if (host.isEmpty()
                || host.length() > 253
                || !host.equals(host.trim())
                || host.indexOf('/') >= 0
                || host.indexOf('?') >= 0
                || host.indexOf('#') >= 0
                || host.indexOf('[') >= 0
                || host.indexOf(']') >= 0) {
            throw new IllegalArgumentException("invalid endpoint host");
        }

        if (host.indexOf(':') >= 0) {
            if (!IPV6_CHARS.matcher(host).matches() || !isNumericIpv6(host)) {
                throw new IllegalArgumentException("invalid IPv6 endpoint host");
            }
            return host;
        }

        if (IPV4.matcher(host).matches()) {
            final String[] octets = host.split("\\.", -1);
            for (final String octet : octets) {
                if (Integer.parseInt(octet) > 255) {
                    throw new IllegalArgumentException("invalid IPv4 endpoint host");
                }
            }
            return host;
        }

        if (!HOSTNAME_CHARS.matcher(host).matches()
                || host.startsWith(".")
                || host.endsWith(".")
                || host.contains("..")) {
            throw new IllegalArgumentException("invalid endpoint hostname");
        }
        for (final String label : host.split("\\.", -1)) {
            if (label.isEmpty()
                    || label.length() > 63
                    || !Character.isLetterOrDigit(label.charAt(0))
                    || !Character.isLetterOrDigit(label.charAt(label.length() - 1))) {
                throw new IllegalArgumentException("invalid endpoint hostname label");
            }
            for (int index = 0; index < label.length(); index++) {
                final char character = label.charAt(index);
                if (!(character >= 'A' && character <= 'Z')
                        && !(character >= 'a' && character <= 'z')
                        && !(character >= '0' && character <= '9')
                        && character != '-') {
                    throw new IllegalArgumentException("invalid endpoint hostname label");
                }
            }
        }
        return host;
    }

    public static int requireValidPort(final int port) {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("invalid endpoint port");
        }
        return port;
    }

    private static boolean isNumericIpv6(final String value) {
        try {
            return InetAddress.getByName(value) instanceof Inet6Address;
        } catch (final UnknownHostException ignored) {
            return false;
        }
    }
}
