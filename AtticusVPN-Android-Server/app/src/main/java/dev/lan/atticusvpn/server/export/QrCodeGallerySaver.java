/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.export;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

/** 显式确认后保存公共 PNG。现代系统不申请照片读取权限或整个存储访问权限。 */
public final class QrCodeGallerySaver {
    public static final String RELATIVE_PATH = "Pictures/AtticusVPN";
    private final Context application;

    public QrCodeGallerySaver(Context context) {
        application = context.getApplicationContext();
    }

    public Uri save(Bitmap bitmap, String peerName) throws IOException {
        if (bitmap == null || bitmap.isRecycled() || bitmap.getWidth() != QrCodeGenerator.SIZE
                || bitmap.getHeight() != QrCodeGenerator.SIZE) throw new IOException("INVALID_BITMAP");
        final ContentResolver resolver = application.getContentResolver();
        final String name = ExportFileNames.png(peerName);
        return GalleryWriteTransaction.write(new GalleryWriteTransaction.Store<Uri>() {
            private File legacyFile;

            @Override public Uri create() throws IOException {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                if (Build.VERSION.SDK_INT >= 29) {
                    values.put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH);
                    values.put(MediaStore.Images.Media.IS_PENDING, 1);
                } else {
                    // 仅 API 24–28；Activity 在用户确认后才请求 WRITE_EXTERNAL_STORAGE。
                    legacyFile = reserveLegacyFile(name);
                    values.put(MediaStore.Images.Media.DATA, legacyFile.getAbsolutePath());
                }
                try {
                    Uri item = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                    if (item == null) discardLegacy();
                    return item;
                } catch (RuntimeException error) {
                    discardLegacy();
                    throw error;
                }
            }

            @Override public OutputStream open(Uri item) throws IOException {
                return resolver.openOutputStream(item, "w");
            }

            @Override public void publish(Uri item) throws IOException {
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues ready = new ContentValues();
                    ready.put(MediaStore.Images.Media.IS_PENDING, 0);
                    if (resolver.update(item, ready, null, null) != 1) {
                        throw new IOException("MEDIA_PUBLISH_FAILED");
                    }
                } else {
                    MediaScannerConnection.scanFile(application,
                            new String[]{legacyFile.getAbsolutePath()}, new String[]{"image/png"}, null);
                }
            }

            @Override public void delete(Uri item) {
                try { resolver.delete(item, null, null); }
                finally { discardLegacy(); }
            }

            private void discardLegacy() {
                if (legacyFile != null) {
                    // 只删除 createNewFile 成功保留的本次文件；不扫描、覆盖或删除其他照片。
                    // 不登记跨操作的延迟删除任务；失败由本次事务统一报告。
                    legacyFile.delete();
                    legacyFile = null;
                }
            }
        }, output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
    }

    @SuppressWarnings("deprecation")
    private static File reserveLegacyFile(String name) throws IOException {
        File folder = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), "AtticusVPN");
        if (!folder.isDirectory() && !folder.mkdirs() && !folder.isDirectory()) {
            throw new IOException("PICTURES_UNAVAILABLE");
        }
        File result = new File(folder, name);
        if (!result.createNewFile()) throw new IOException("FILE_ALREADY_EXISTS");
        return result;
    }
}
