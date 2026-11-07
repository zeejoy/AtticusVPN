/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.storage;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AndroidKeyStore AES-256-GCM + AtomicFile 的小型加密 blob。
 *
 * <p>文件中只有 magic/version/随机 IV/密文与 GCM tag；alias 与用途写入 AAD，防止
 * 把一个 Peer 的密文替换给另一个 Peer。异常只向上返回固定类别，调用方不得记录
 * exception message，因为底层 provider 文本不属于稳定或安全的用户输出。</p>
 */
final class KeystoreBlob {
    private static final byte[] MAGIC = {'A', 'T', 'S', 'V'};
    private static final int VERSION = 1;
    private static final int MAX_BLOB = 512 * 1024;

    private final String alias;
    private final byte[] aad;
    private final AtomicFile file;

    KeystoreBlob(final Context context, final String alias, final String filename, final String purpose) {
        this.alias = alias;
        this.aad = ("AtticusVPN/" + purpose + "/v1").getBytes(StandardCharsets.UTF_8);
        this.file = new AtomicFile(new File(context.getNoBackupFilesDir(), filename));
    }

    // AtomicFile may retain only a legacy backup after an interrupted write.
    // An unfinished new file or an unexpected directory must fail closed too:
    // neither means that it is safe to replace the user's server identity.
    boolean exists() {
        final File base = file.getBaseFile();
        return base.exists() || new File(base + ".bak").exists()
                || new File(base + ".new").exists();
    }

    void write(final byte[] plaintext) throws Exception {
        final SecretKey key = getOrCreateKey();
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        cipher.updateAAD(aad);
        final byte[] encrypted = cipher.doFinal(plaintext);
        final byte[] iv = cipher.getIV();
        final ByteBuffer frame = ByteBuffer.allocate(4 + 1 + 1 + iv.length + encrypted.length);
        frame.put(MAGIC).put((byte) VERSION).put((byte) iv.length).put(iv).put(encrypted);
        final byte[] output = frame.array();
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            stream.write(output);
            file.finishWrite(stream);
            stream = null;
            // AtomicFile.finishWrite reports some sync/rename failures only via
            // platform logging. Confirm the committed bytes before publishing
            // state or allowing Peer cleanup. openRead also rolls back a retained
            // legacy backup if finishWrite could not remove it.
            final byte[] committed = readRaw(file.openRead());
            try {
                if (!Arrays.equals(output, committed)) {
                    throw new SecurityException("encrypted state commit unavailable");
                }
            } finally {
                Arrays.fill(committed, (byte) 0);
            }
        } finally {
            if (stream != null) {
                file.failWrite(stream);
            }
            Arrays.fill(encrypted, (byte) 0);
            Arrays.fill(output, (byte) 0);
        }
    }

    byte[] read() throws Exception {
        final boolean hadState = exists();
        final File backup = new File(file.getBaseFile() + ".bak");
        if (backup.exists()) {
            // AtomicFile replaces the base with its authoritative legacy backup.
            // Authenticate it first so a damaged/unreadable backup cannot destroy
            // either recovery candidate. Never create a key on a read path.
            final byte[] checked = decrypt(readRaw(new FileInputStream(backup)));
            Arrays.fill(checked, (byte) 0);
        }
        final FileInputStream stream;
        try {
            // openRead performs Android's recovery before we decide it is a new install.
            stream = file.openRead();
        } catch (final FileNotFoundException missing) {
            if (hadState || exists()) {
                throw new SecurityException("encrypted state recovery unavailable");
            }
            return null;
        }
        return decrypt(readRaw(stream));
    }

    private static byte[] readRaw(final FileInputStream stream) throws Exception {
        try (FileInputStream input = stream;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            final byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
                if (output.size() > MAX_BLOB) {
                    throw new SecurityException("encrypted state too large");
                }
            }
            Arrays.fill(buffer, (byte) 0);
            return output.toByteArray();
        }
    }

    private byte[] decrypt(final byte[] raw) throws Exception {
        try {
            if (raw.length < 34) throw new SecurityException("encrypted state header invalid");
            final ByteBuffer frame = ByteBuffer.wrap(raw);
            final byte[] magic = new byte[4];
            frame.get(magic);
            final int version = frame.get() & 0xff;
            final int ivLength = frame.get() & 0xff;
            if (!Arrays.equals(magic, MAGIC) || version != VERSION || ivLength != 12
                    || frame.remaining() < ivLength + 16) {
                throw new SecurityException("encrypted state header invalid");
            }
            final byte[] iv = new byte[ivLength];
            frame.get(iv);
            final byte[] encrypted = new byte[frame.remaining()];
            frame.get(encrypted);
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad);
            final byte[] plaintext = cipher.doFinal(encrypted);
            Arrays.fill(encrypted, (byte) 0);
            Arrays.fill(iv, (byte) 0);
            return plaintext;
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    void delete() throws Exception {
        // 普通闪存/文件系统删除不能被描述为可证明的物理安全擦除；删除 alias 后，
        // 即使旧密文块仍由存储介质保留，应用也失去解密密钥。
        final KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(alias)) {
            store.deleteEntry(alias);
        }
        // 先删除密钥，再清理 ciphertext。崩溃在两步之间只会留下不可解密密文。
        file.delete();
    }

    private SecretKey getOrCreateKey() throws Exception {
        final KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(alias)) {
            return (SecretKey) store.getKey(alias, null);
        }
        if (exists()) {
            throw new SecurityException("encrypted state key unavailable");
        }
        final KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private SecretKey getExistingKey() throws Exception {
        final KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(alias)) {
            throw new SecurityException("encrypted state key unavailable");
        }
        return (SecretKey) store.getKey(alias, null);
    }
}
