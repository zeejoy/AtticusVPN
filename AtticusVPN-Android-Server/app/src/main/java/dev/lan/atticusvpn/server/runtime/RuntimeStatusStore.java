/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.runtime;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/** 进程内状态总线；回调统一投递主线程，且永不携带配置正文。 */
public final class RuntimeStatusStore {
    public interface Listener { void onRuntimeStatus(RuntimeStatus status); }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile RuntimeStatus current = RuntimeStatus.stopped();

    private RuntimeStatusStore() {
    }

    public static RuntimeStatus current() { return current; }

    public static void publish(final RuntimeStatus status) {
        current = status;
        dev.lan.atticusvpn.server.logging.RuntimeLogCollector.current().runtime(
                dev.lan.atticusvpn.server.logging.RunLogModel.Protocol.WG,status);
        for (final Listener listener : LISTENERS) {
            MAIN.post(() -> listener.onRuntimeStatus(status));
        }
    }

    public static void addListener(final Listener listener) {
        LISTENERS.addIfAbsent(listener);
        MAIN.post(() -> listener.onRuntimeStatus(current));
    }

    public static void removeListener(final Listener listener) {
        LISTENERS.remove(listener);
    }
}
