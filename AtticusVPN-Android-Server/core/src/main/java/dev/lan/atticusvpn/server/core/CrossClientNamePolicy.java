/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.core;

import java.util.Collection;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Windows 与 Android 客户端 tunnel 名称约束的安全交集。
 *
 * <p>Android WireGuard backend 最多接受 15 字符；Windows 还拒绝设备保留名。
 * 服务器侧使用更严格的首字母规则，与 Linux AtticusVPN peer 名保持一致。</p>
 */
public final class CrossClientNamePolicy {
    public static final int MAX_LENGTH = 15;

    private static final Pattern SAFE_NAME =
            Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,14}$");
    private static final Set<String> WINDOWS_RESERVED = buildReservedNames();

    private CrossClientNamePolicy() {
    }

    public static boolean isValid(final String name) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            return false;
        }
        return !WINDOWS_RESERVED.contains(name.toUpperCase(Locale.ROOT));
    }

    public static String requireValid(final String name) {
        if (!isValid(name)) {
            throw new IllegalArgumentException("invalid cross-client peer name");
        }
        return name;
    }

    /**
     * 验证新名称，并按 ASCII 大小写不敏感规则排除重复。
     * Windows 文件系统和服务名通常不区分大小写，因此不能只依赖 Java equals。
     */
    public static String requireNew(final String name, final Collection<String> existingNames) {
        requireValid(name);
        Objects.requireNonNull(existingNames, "existingNames");
        final String key = comparisonKey(name);
        for (final String existing : existingNames) {
            if (existing != null && comparisonKey(existing).equals(key)) {
                throw new IllegalArgumentException("duplicate cross-client peer name");
            }
        }
        return name;
    }

    public static String comparisonKey(final String name) {
        return Objects.requireNonNull(name, "name").toLowerCase(Locale.ROOT);
    }

    private static Set<String> buildReservedNames() {
        final Set<String> names = new HashSet<>(Arrays.asList("CON", "PRN", "AUX", "NUL"));
        for (int index = 1; index <= 9; index++) {
            names.add("COM" + index);
            names.add("LPT" + index);
        }
        return Collections.unmodifiableSet(names);
    }
}
