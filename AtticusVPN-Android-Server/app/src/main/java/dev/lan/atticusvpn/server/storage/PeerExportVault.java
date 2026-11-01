/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.storage;

import android.content.Context;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 每个 Peer 独立的临时客户端私钥保险库。
 *
 * <p>Seal 的顺序由 repository 控制：先把 SEALED 元数据原子提交，再删除本密文和
 * 独立 Keystore alias。进程若在两步之间终止，下次启动会依据 SEALED 状态补做删除。</p>
 */
public final class PeerExportVault {
    private static final String ALIAS_PREFIX = "dev.lan.atticusvpn.server.peer.export.";
    private final Context context;

    public PeerExportVault(final Context context) {
        this.context = context.getApplicationContext();
    }

    public void put(final String peerId, final String clientPrivateKey) throws Exception {
        final byte[] raw = clientPrivateKey.getBytes(StandardCharsets.US_ASCII);
        try {
            blob(peerId).write(raw);
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    public String get(final String peerId) throws Exception {
        final byte[] raw = blob(peerId).read();
        if (raw == null) {
            throw new IllegalStateException("PEER_EXPORT_NOT_AVAILABLE");
        }
        try {
            return new String(raw, StandardCharsets.US_ASCII);
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    public boolean exists(final String peerId) { return blob(peerId).exists(); }
    public void delete(final String peerId) throws Exception { blob(peerId).delete(); }

    /**
     * 删除不属于未 Seal Peer 的保险库。
     *
     * <p>同时枚举 AndroidKeyStore alias 和 no-backup 文件，覆盖“只写成了一侧”以及
     * 删除/Seal 在两步间崩溃的情况。每次删除前查询最新的已提交状态；仓库锁不包围慢速
     * Keystore I/O。清理失败由独立的有限重试队列处理，不会恢复授权或阻断运行态撤销。</p>
     */
    public void cleanupOrphans(final Predicate<String> retainPeer) throws Exception {
        for (final String peerId : discoverFilePeerIds()) {
            if (!retainPeer.test(peerId)) {
                blob(peerId).delete();
            }
        }

        final KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        final Enumeration<String> aliases = store.aliases();
        while (aliases.hasMoreElements()) {
            final String alias = aliases.nextElement();
            if (!alias.startsWith(ALIAS_PREFIX)) {
                continue;
            }
            final String peerId = alias.substring(ALIAS_PREFIX.length());
            if (peerId.matches("^[a-f0-9]{32}$") && !retainPeer.test(peerId)) {
                // 即使对应文件已不在，残留 alias 也不应无限保留。
                store.deleteEntry(alias);
            }
        }
    }

    /** 从严格文件名中提取 Peer ID；测试也使用此纯函数验证白名单算法。 */
    static Set<String> orphanIds(
            final Iterable<String> filenames,
            final Set<String> retainedPeerIds) {
        final Set<String> result = new HashSet<>();
        for (final String filename : filenames) {
            if (filename != null && filename.matches("^peer-[a-f0-9]{32}\\.export\\.enc$")) {
                final String peerId = filename.substring(5, 37);
                if (!retainedPeerIds.contains(peerId)) {
                    result.add(peerId);
                }
            }
        }
        return result;
    }

    private Set<String> discoverFilePeerIds() {
        final File[] files = context.getNoBackupFilesDir().listFiles();
        if (files == null) {
            return Collections.emptySet();
        }
        final Set<String> names = new HashSet<>();
        for (final File file : files) {
            names.add(file.getName());
        }
        return orphanIds(names, Collections.emptySet());
    }

    private KeystoreBlob blob(final String peerId) {
        if (peerId == null || !peerId.matches("^[a-f0-9]{32}$")) {
            throw new IllegalArgumentException("invalid peer id");
        }
        return new KeystoreBlob(
                context,
                ALIAS_PREFIX + peerId,
                "peer-" + peerId + ".export.enc",
                "peer-export/" + peerId);
    }
}
