/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.export;

import android.graphics.Bitmap;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumMap;
import dev.lan.atticusvpn.server.core.SecretBytes;

/** 本地标准 QR：1024px、黑白、M 纠错、四模块静区；不访问网络或磁盘。 */
public final class QrCodeGenerator {
    public static final int SIZE = 1024;
    // QR version 40 / M 的 byte mode 容量，另为 UTF-8 ECI 保留开销。
    private static final int MAX_UTF8_BYTES = 2330;

    public static final class TooLongException extends Exception {
        private static final long serialVersionUID = 1L;
    }

    /** 暴露无 Android 依赖的矩阵生成边界，供实际编码/解码往返测试。 */
    public BitMatrix encode(SecretBytes configuration) throws TooLongException, WriterException {
        byte[] bytes = configuration.copy();
        try {
            if (bytes.length > MAX_UTF8_BYTES) throw new TooLongException();
            if (bytes.length == 0) throw new IllegalArgumentException("EMPTY_CONFIGURATION");
            EnumMap<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 4);
            // ZXing 的 API 接受 String；该临时字符串无法可靠清零，不缓存或持久化它。
            return new QRCodeWriter().encode(new String(bytes, StandardCharsets.UTF_8),
                    BarcodeFormat.QR_CODE, SIZE, SIZE, hints);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    public Bitmap generate(SecretBytes configuration) throws TooLongException, WriterException {
        BitMatrix matrix = encode(configuration);
        int[] pixels = null;
        try {
            pixels = new int[SIZE * SIZE];
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) pixels[y * SIZE + x] = matrix.get(x, y)
                        ? 0xff000000 : 0xffffffff;
            }
            Bitmap bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
            try {
                bitmap.setPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE);
                return bitmap;
            } catch (RuntimeException | OutOfMemoryError failure) {
                release(bitmap);
                throw failure;
            }
        } finally {
            matrix.clear();
            if (pixels != null) Arrays.fill(pixels, 0);
        }
    }

    /** 必须先从 ImageView 移除；不在 UI 和保存线程之间共享同一个 Bitmap。 */
    public static void release(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            if (bitmap.isMutable()) bitmap.eraseColor(0xffffffff);
            bitmap.recycle();
        }
    }
}
