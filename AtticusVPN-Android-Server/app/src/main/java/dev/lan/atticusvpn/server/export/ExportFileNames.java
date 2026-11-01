/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.export;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/** 文件名只来自 Peer 名称和新生成的随机标识，绝不接受配置、密钥或 Endpoint。 */
public final class ExportFileNames {
    private ExportFileNames() { }

    public static String safePeerName(String peerName) {
        String safe = peerName == null ? "" : peerName.replaceAll("[^A-Za-z0-9_-]", "_");
        if (safe.isEmpty()) safe = "peer";
        return safe.substring(0, Math.min(safe.length(), 40));
    }

    public static String png(String peerName) {
        // MediaStore 插入新记录，旧系统同时用 createNewFile 原子保留文件名；绝不覆盖旧图。
        String timestamp = LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT));
        return "AtticusVPN-" + safePeerName(peerName) + "-" + timestamp + "-"
                + UUID.randomUUID() + ".png";
    }

    public static String conf(String peerName) {
        // 官方 WireGuard 会从文件名推导隧道名称，沿用项目的短 Peer 名称限制。
        return safePeerName(peerName) + ".conf";
    }
}
