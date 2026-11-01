/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.runtime;

import android.os.Handler;
import android.os.Looper;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import dev.lan.atticusvpn.server.capability.DeviceCapabilityChecker;
import dev.lan.atticusvpn.server.capability.DeviceCapabilitySnapshot;
import dev.lan.atticusvpn.server.root.RootManager;

/**
 * 进程内唯一的能力检查队列。独立于 WireGuard 与配置存储队列，su 授权等待不会阻塞
 * WireGuard 启停。旋转屏幕只订阅现有任务，不重复请求 Root，也不持有旧 Activity。
 */
public final class DeviceCheckController {
    public interface Listener { void onDeviceCheck(State state); }
    public interface Probe { DeviceCapabilitySnapshot inspect(boolean requestRoot); }
    public static final class State {
        private final DeviceCapabilitySnapshot snapshot;
        private final boolean busy, failed;
        private State(DeviceCapabilitySnapshot snapshot, boolean busy, boolean failed) {
            this.snapshot = snapshot; this.busy = busy; this.failed = failed;
        }
        public DeviceCapabilitySnapshot snapshot() { return snapshot; }
        public boolean busy() { return busy; }
        public boolean failed() { return failed; }
    }

    private final Probe probe;
    private final Executor worker, ui;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile State current = new State(null, false, false);

    public DeviceCheckController(Probe probe, Executor worker, Executor ui) {
        this.probe = Objects.requireNonNull(probe);
        this.worker = Objects.requireNonNull(worker);
        this.ui = Objects.requireNonNull(ui);
    }

    /** 生产入口；测试只替换调度器和探针，不会运行本机 su。 */
    public static DeviceCheckController create() {
        final RootManager root = new RootManager();
        final DeviceCapabilityChecker checker = new DeviceCapabilityChecker(root);
        final Handler main = new Handler(Looper.getMainLooper());
        return new DeviceCheckController(requestRoot -> {
            if (requestRoot) {
                root.requestAuthorization();
                return checker.check();
            }
            root.discoverWithoutPrompt();
            return checker.checkWithoutRootCommands();
        }, Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "AtticusVPN-DeviceCheck");
            thread.setDaemon(true);
            return thread;
        }), runnable -> main.post(runnable));
    }

    public State current() { return current; }
    public void addListener(Listener listener) {
        listeners.addIfAbsent(listener);
        notifyListener(listener);
    }
    public void removeListener(Listener listener) { listeners.remove(listener); }

    /** 初次打开界面只查元数据，已有结果/任务不因 Activity 重建而重跑。 */
    public synchronized void inspectOnceWithoutPrompt() {
        if (current.snapshot == null && !current.busy && !current.failed) submit(false);
    }

    /** 必须由用户确认后调用，不从 Service、定时器或网络回调触发。 */
    public synchronized boolean requestRootAndInspect() { return submit(true); }

    private synchronized boolean submit(boolean requestRoot) {
        if (current.busy) return false;
        current = new State(current.snapshot, true, false);
        notifyListeners();
        try {
            worker.execute(() -> {
                try {
                    final DeviceCapabilitySnapshot result = Objects.requireNonNull(probe.inspect(requestRoot));
                    finish(result, false);
                } catch (RuntimeException failure) {
                    // 原始异常可能包含 ROM 路径或命令输出；界面只接收固定失败状态。
                    finish(null, true);
                }
            });
        } catch (RuntimeException rejected) { finish(null, true); }
        return true;
    }

    private synchronized void finish(DeviceCapabilitySnapshot snapshot, boolean failed) {
        current = new State(snapshot, false, failed);
        notifyListeners();
    }
    private void notifyListeners() { for (Listener listener : listeners) notifyListener(listener); }
    private void notifyListener(Listener listener) {
        ui.execute(() -> {
            // 使用执行时最新状态，避免旋转或跨线程投递顺序让旧快照覆盖新结果。
            if (listeners.contains(listener)) listener.onDeviceCheck(current);
        });
    }
}
