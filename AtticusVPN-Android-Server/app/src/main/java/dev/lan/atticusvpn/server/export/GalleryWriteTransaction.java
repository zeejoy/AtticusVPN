/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.export;

import java.io.IOException;
import java.io.OutputStream;

/** 公共图片的单次事务：创建、完整写入并关闭、发布；任何失败只回滚本次新对象。 */
public final class GalleryWriteTransaction {
    private GalleryWriteTransaction() { }

    public interface Store<T> {
        T create() throws IOException;
        OutputStream open(T item) throws IOException;
        void publish(T item) throws IOException;
        void delete(T item) throws IOException;
    }

    public interface Encoder {
        boolean write(OutputStream output) throws IOException;
    }

    public static <T> T write(Store<T> store, Encoder encoder) throws IOException {
        T item = store.create();
        if (item == null) throw new IOException("MEDIA_INSERT_FAILED");
        boolean published = false;
        try {
            try (OutputStream output = store.open(item)) {
                if (output == null) throw new IOException("MEDIA_OPEN_FAILED");
                if (!encoder.write(output)) throw new IOException("PNG_ENCODING_FAILED");
                output.flush();
            }
            store.publish(item);
            published = true;
            return item;
        } finally {
            if (!published) {
                try { store.delete(item); }
                catch (IOException | RuntimeException ignored) {
                    // 不掩盖原始错误；系统若拒绝删除，待处理记录可能需用户手工清理。
                }
            }
        }
    }
}
