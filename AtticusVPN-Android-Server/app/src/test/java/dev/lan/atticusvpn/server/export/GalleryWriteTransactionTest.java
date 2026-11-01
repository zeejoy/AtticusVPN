/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** 对真实事务注入存储故障，验证发布顺序、回滚范围和权限/空间错误传播。 */
public final class GalleryWriteTransactionTest {
    @Test public void publishOnlyAfterCompleteWriteAndClose() throws Exception {
        FakeStore store = new FakeStore("");
        assertEquals("new-item", GalleryWriteTransaction.write(store, output -> {
            output.write(new byte[]{1, 2, 3}); return true;
        }));
        assertEquals(List.of("create", "open", "close", "publish"), store.events);
        assertArrayEquals(new byte[]{1, 2, 3}, store.bytes.toByteArray());
    }

    @Test public void insertFailureDoesNotTouchAnyExistingImage() {
        for (String failure : new String[]{"insert-null", "permission"}) {
            FakeStore store = new FakeStore(failure);
            assertThrows(Exception.class, () -> GalleryWriteTransaction.write(store, output -> true));
            assertEquals(List.of("create"), store.events);
        }
    }

    @Test public void openEncodeCloseAndPublishFailuresRollBackOnlyNewItem() {
        for (String failure : new String[]{"open-null", "open-error", "close-error", "publish-error", "no-space", "encode-false"}) {
            FakeStore store = new FakeStore(failure);
            assertThrows(IOException.class, () -> GalleryWriteTransaction.write(store, output -> {
                output.write(1);
                if (failure.equals("no-space")) throw new IOException("ENOSPC");
                return !failure.equals("encode-false");
            }));
            assertEquals("delete:new-item", store.events.get(store.events.size() - 1));
            assertEquals(1, store.events.stream().filter(value -> value.startsWith("delete:")).count());
            if (!failure.equals("publish-error")) assertFalse(store.events.contains("publish"));
        }
    }

    @Test public void rollbackFailureDoesNotMaskOriginalError() {
        FakeStore store = new FakeStore("delete-error");
        IOException failure = assertThrows(IOException.class,
                () -> GalleryWriteTransaction.write(store, output -> { throw new IOException("encoding-failed"); }));
        assertEquals("encoding-failed", failure.getMessage());
        assertEquals("delete:new-item", store.events.get(store.events.size() - 1));
    }

    private static final class FakeStore implements GalleryWriteTransaction.Store<String> {
        final String failure;
        final List<String> events = new ArrayList<>();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        FakeStore(String failure) { this.failure = failure; }
        @Override public String create() {
            events.add("create");
            if (failure.equals("permission")) throw new SecurityException();
            return failure.equals("insert-null") ? null : "new-item";
        }
        @Override public OutputStream open(String item) throws IOException {
            assertEquals("new-item", item); events.add("open");
            if (failure.equals("open-null")) return null;
            if (failure.equals("open-error")) throw new IOException();
            return new OutputStream() {
                @Override public void write(int value) { bytes.write(value); }
                @Override public void close() throws IOException {
                    events.add("close");
                    if (failure.equals("close-error")) throw new IOException();
                }
            };
        }
        @Override public void publish(String item) throws IOException {
            assertEquals("new-item", item); events.add("publish");
            if (failure.equals("publish-error")) throw new IOException();
        }
        @Override public void delete(String item) throws IOException {
            events.add("delete:" + item);
            if (failure.equals("delete-error")) throw new IOException();
        }
    }
}
