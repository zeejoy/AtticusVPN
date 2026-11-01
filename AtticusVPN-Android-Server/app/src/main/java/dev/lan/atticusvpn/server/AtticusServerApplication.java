/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server;

import android.app.Application;
import android.os.Looper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import dev.lan.atticusvpn.server.storage.EncryptedStateRepository;
import dev.lan.atticusvpn.server.runtime.DeviceCheckController;
import dev.lan.atticusvpn.server.storage.SecretCleanupScheduler;
import java.util.concurrent.ScheduledExecutorService;
import android.util.Log;

/**
 * 保存进程内唯一的加密仓库实例，并在后台完成 AndroidKeyStore 初始化。
 *
 * <p>Activity 与前台服务共享同一仓库对象，仓库自身的同步边界因此能够覆盖 UI、网络
 * 回调和 Service 重载。初始化失败只以固定错误向上传递，不把 provider 异常或密钥写入
 * Logcat。</p>
 */
public final class AtticusServerApplication extends Application {
    private static final long INITIALIZATION_TIMEOUT_SECONDS = 30L;

    private final CountDownLatch initialized = new CountDownLatch(1);
    private final ExecutorService initializationExecutor = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(final Runnable runnable) {
                    final Thread thread = new Thread(runnable, "AtticusVPN-StateInit");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    private EncryptedStateRepository repository;
    private volatile boolean initializationFailed;
    private DeviceCheckController deviceChecks;
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(
            runnable -> { Thread thread = new Thread(runnable, "AtticusVPN-KeyCleanup");
                thread.setDaemon(true); return thread; });
    private SecretCleanupScheduler keyCleanup;

    @Override
    public void onCreate() {
        super.onCreate();
        // 构造不触发 su；Root 队列不与 WireGuard 或仓库初始化共用。
        deviceChecks = DeviceCheckController.create();
        repository = new EncryptedStateRepository(this);
        keyCleanup = new SecretCleanupScheduler(repository::cleanupRevokedKeys,
                (action, delay) -> cleanupExecutor.schedule(action, delay, TimeUnit.SECONDS),
                code -> Log.w("AtticusKeyCleanup", code));
        initializationExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    repository.initialize();
                    keyCleanup.request();
                } catch (final Exception ignored) {
                    // 底层异常文本可能含文件/provider 信息；只保留固定失败状态。
                    initializationFailed = true;
                } finally {
                    initialized.countDown();
                    initializationExecutor.shutdown();
                }
            }
        });
    }

    public DeviceCheckController deviceChecks() { return deviceChecks; }
    public void requestKeyCleanup() { keyCleanup.request(); }

    /**
     * 仅供后台线程取得已初始化仓库；主线程调用会直接失败，避免界面被 Keystore 阻塞。
     */
    public EncryptedStateRepository awaitRepository() throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("REPOSITORY_WAIT_ON_MAIN_THREAD");
        }
        if (!initialized.await(INITIALIZATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("REPOSITORY_INITIALIZATION_TIMEOUT");
        }
        if (initializationFailed) {
            throw new IllegalStateException("REPOSITORY_INITIALIZATION_FAILED");
        }
        return repository;
    }
}
