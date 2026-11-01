/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.ui;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;

import dev.lan.atticusvpn.server.core.ClientConfigRenderer;
import dev.lan.atticusvpn.server.core.SecretBytes;

/**
 * 只通过 Storage Access Framework 写出标准 WireGuard 配置。
 *
 * <p>应用不申请广泛存储权限；目标位置由用户在系统文档选择器中明确决定。复制出的
 * 明文字节在 finally 中清零，调用方仍负责关闭原始 SecretBytes 并在失败时删除半成品。</p>
 */
public final class SafConfigExporter {
    private SafConfigExporter() {
    }

    public static void write(
            final ContentResolver resolver,
            final Uri destination,
            final SecretBytes configuration) throws IOException {
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(configuration, "configuration");
        final byte[] plain = configuration.copy();
        try {
            if (plain.length == 0 || plain.length > ClientConfigRenderer.MAX_CONFIGURATION_BYTES) {
                throw new IOException("CONFIGURATION_SIZE_INVALID");
            }
            // "wt" 明确要求 ContentProvider 截断旧文件，避免较短的新配置留下旧尾部。
            try (OutputStream output = resolver.openOutputStream(destination, "wt")) {
                if (output == null) {
                    throw new IOException("SAF_OPEN_FAILED");
                }
                output.write(plain);
                output.flush();
            }
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }
}
