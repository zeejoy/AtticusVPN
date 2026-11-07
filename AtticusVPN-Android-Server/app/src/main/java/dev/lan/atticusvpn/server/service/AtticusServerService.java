/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.drawable.Icon;
import android.net.Network;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.ResultReceiver;

import com.wireguard.crypto.Key;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import dev.lan.atticusvpn.server.AtticusServerApplication;
import dev.lan.atticusvpn.server.R;
import dev.lan.atticusvpn.server.config.AppSettingsPolicy;
import dev.lan.atticusvpn.server.config.WireGuardConfigFactory;
import dev.lan.atticusvpn.server.core.EndpointFormatter;
import dev.lan.atticusvpn.server.ddns.DdnsUpdateManager;
import dev.lan.atticusvpn.server.model.ServerState;
import dev.lan.atticusvpn.server.model.StoredPeer;
import dev.lan.atticusvpn.server.network.AndroidNetworkMonitor;
import dev.lan.atticusvpn.server.network.GlobalIpv6Selector;
import dev.lan.atticusvpn.server.network.NetworkSnapshot;
import dev.lan.atticusvpn.server.runtime.DdnsRuntimeFactory;
import dev.lan.atticusvpn.server.runtime.DnsUpstreamResolver;
import dev.lan.atticusvpn.server.runtime.NativeEngine;
import dev.lan.atticusvpn.server.runtime.NetworkCheckResult;
import dev.lan.atticusvpn.server.runtime.ServerNetworkProbe;
import dev.lan.atticusvpn.server.runtime.NativeEngineException;
import dev.lan.atticusvpn.server.runtime.ReflectiveNativeEngine;
import dev.lan.atticusvpn.server.runtime.RuntimeStatus;
import dev.lan.atticusvpn.server.runtime.RuntimeStatusStore;
import dev.lan.atticusvpn.server.runtime.RuntimeNetworkPolicy;
import dev.lan.atticusvpn.server.runtime.PeerRevocationController;
import dev.lan.atticusvpn.server.storage.EncryptedStateRepository;
import dev.lan.atticusvpn.server.ui.MainActivity;
import dev.lan.atticusvpn.server.ui.AppLanguage;

/**
 * 用户明确启动的自托管 WireGuard 前台服务。
 *
 * <p>所有 Keystore、配置渲染、native 与网络切换操作都在单一后台线程串行执行。
 * 网络变化时先停止旧数据面，再绑定新的 Android Network，防止连接继续经失效网络。
 * 通知和运行状态只含固定文本、Peer 数与公开 Endpoint，不含配置、key 或 DDNS 凭据。</p>
 */
public final class AtticusServerService extends Service implements AndroidNetworkMonitor.Listener {
    private static final String ACTION_START = "dev.lan.atticusvpn.server.action.START";
    private static final String ACTION_STOP = "dev.lan.atticusvpn.server.action.STOP";
    private static final String ACTION_RELOAD = "dev.lan.atticusvpn.server.action.RELOAD";
    private static final String ACTION_CHECK = "dev.lan.atticusvpn.server.action.CHECK";
    private static final String ACTION_REVOKE = "dev.lan.atticusvpn.server.action.REVOKE";
    private static final String EXTRA_PEER = "peerId";
    private static final String EXTRA_RESULT = "revocationResult";
    public static final int REVOCATION_APPLIED = 1;
    public static final int REVOCATION_SERVICE_STOPPED = 2;
    private static final String CHANNEL_ID = "atticusvpn-server";
    private static final int NOTIFICATION_ID = 17077;
    private static final long WAKE_LOCK_TIMEOUT_MILLIS = 10L * 60L * 1000L;

    private final SharedPreferences.OnSharedPreferenceChangeListener languageListener =
            (preferences, key) -> refreshNotificationLanguage();

    private String localized(int resource, Object... arguments) {
        return AppLanguage.text(this, resource, arguments);
    }

    private void refreshNotificationLanguage() {
        synchronized (commandLock) {
            if (destroyed) return;
            createNotificationChannel();
            final RuntimeStatus status = RuntimeStatusStore.current();
            if (status.phase() == RuntimeStatus.Phase.STOPPED) return;
            showNotification(statusText(status), false);
        }
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        refreshNotificationLanguage();
    }

    private final WireGuardConfigFactory configFactory = new WireGuardConfigFactory();
    /** 保护命令代次与销毁标志，确保最后收到的 START/STOP 意图生效。 */
    private final Object commandLock = new Object();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(final Runnable runnable) {
            final Thread thread = new Thread(runnable, "AtticusVPN-Server");
            thread.setDaemon(true);
            return thread;
        }
    });

    private AndroidNetworkMonitor networkMonitor;
    private DdnsUpdateManager ddnsManager;
    private NativeEngine engine;
    private PowerManager.WakeLock wakeLock;
    private long wakeRenewAt;
    private String lastNotificationKey = "";
    private NetworkSnapshot lastSnapshot;
    private boolean desiredRunning;
    private boolean engineRunning;
    private boolean monitorStarted;
    private String activeSignature = "";
    private String ddnsStatus = "DISABLED";
    private int configuredListenPort;
    private String configuredEndpoint = "";
    private boolean configuredDnsEnabled;
    private String configuredDdnsProvider = "disabled";
    private boolean ddnsLifecycleStarted;
    private long commandGeneration;
    private volatile boolean destroyed;
    /** 网络请求在独立线程运行；停止/切网命令不等待 HTTPS 或 DNS 超时。 */
    // 系统同步 DNS 可能不响应中断；固定线程数和有界队列，防止反复重测无限堆积。
    private final ThreadPoolExecutor probeWorker = new ThreadPoolExecutor(2, 2, 15, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), runnable -> {
        final Thread thread = new Thread(runnable, "AtticusVPN-Server-NetCheck");
        thread.setDaemon(true);
        return thread;
    });
    private NetworkCheckResult networkCheck = NetworkCheckResult.IDLE;
    private long checkGeneration;
    private ServerNetworkProbe activeProbe;
    private Future<?> probeTask;
    private ScheduledFuture<?> probeDeadline;
    private String activeUpstreams = "";

    /** 手动复测只附着已运行服务，不隐式启动监听器。 */
    public static void checkNetwork(final Context context) {
        if (RuntimeStatusStore.current().phase() == RuntimeStatus.Phase.RUNNING) {
            context.startService(new Intent(context, AtticusServerService.class).setAction(ACTION_CHECK));
        }
    }

    /** 从用户手势启动前台服务。 */
    public static void start(final Context context) {
        final Intent intent = new Intent(context, AtticusServerService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /** 显式停止服务。 */
    public static void stop(final Context context) {
        context.startService(new Intent(context, AtticusServerService.class).setAction(ACTION_STOP));
    }

    /** The service acknowledges actual native synchronization, not just Intent delivery. */
    public static void revokePeer(Context context, String id, ResultReceiver result) {
        if (id == null || !id.matches("^[a-f0-9]{32}$")) {
            throw new IllegalArgumentException("INVALID_PEER_ID");
        }
        Intent intent = new Intent(context, AtticusServerService.class).setAction(ACTION_REVOKE)
                .putExtra(EXTRA_PEER, id).putExtra(EXTRA_RESULT, result);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    /** 仅在服务已经活动时重载；不会因为修改设置而暗中启动服务器。 */
    public static void reloadIfActive(final Context context) {
        final RuntimeStatus.Phase phase = RuntimeStatusStore.current().phase();
        if (phase == RuntimeStatus.Phase.STARTING
                || phase == RuntimeStatus.Phase.WAITING_NETWORK
                || phase == RuntimeStatus.Phase.RUNNING
                || phase == RuntimeStatus.Phase.ERROR) {
            final Intent intent = new Intent(context, AtticusServerService.class)
                    .setAction(ACTION_RELOAD);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // 显示语言不属于连接设置；监听只刷新通知，绝不排队 START/STOP/RELOAD。
        AppLanguage.preferences(this).registerOnSharedPreferenceChangeListener(languageListener);
        createNotificationChannel();
        networkMonitor = new AndroidNetworkMonitor(this, new GlobalIpv6Selector());
        final PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "AtticusVPN:ServerRuntime");
            wakeLock.setReferenceCounted(false);
        }
        worker.scheduleWithFixedDelay(this::pollRuntimeStatus, 1L, 1L, TimeUnit.SECONDS);
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        final String action = intent == null ? ACTION_STOP : intent.getAction();
        final long generation;
        synchronized (commandLock) {
            if (destroyed) {
                return START_NOT_STICKY;
            }
            // RELOAD 只附着当前用户意图，不能越过或撤销已经排队的显式 STOP。
            generation = (ACTION_RELOAD.equals(action) || ACTION_CHECK.equals(action)
                    || ACTION_REVOKE.equals(action))
                    ? commandGeneration
                    : ++commandGeneration;
            // 与旧 STOP 的最终检查使用同一把锁；新命令总能保留或恢复前台通知。
            showNotification(localized(R.string.notification_preparing), true);
        }
        if (ACTION_REVOKE.equals(action)) {
            final String id = intent.getStringExtra(EXTRA_PEER);
            final ResultReceiver result = intent.getParcelableExtra(EXTRA_RESULT);
            worker.execute(() -> handleRevocation(id, result, startId));
        } else if (ACTION_STOP.equals(action)) {
            worker.execute(() -> handleStop(generation, startId));
        } else if (ACTION_RELOAD.equals(action)) {
            worker.execute(() -> handleReload(generation, startId));
        } else if (ACTION_CHECK.equals(action)) {
            worker.execute(() -> {
                if (isCurrentCommand(generation) && desiredRunning && engineRunning) startNetworkCheck();
                else if (isCurrentCommand(generation) && !desiredRunning) handleStop(generation, startId);
            });
        } else {
            worker.execute(() -> handleStart(generation));
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(final Intent intent) {
        return null;
    }

    @Override
    public void onNetworkSnapshot(final NetworkSnapshot snapshot) {
        enqueueCallback(() -> {
            lastSnapshot = snapshot;
            if (desiredRunning) {
                applySnapshot(snapshot, false);
            }
        });
    }

    @Override
    public void onNetworkLost(final long networkHandle) {
        enqueueCallback(() -> {
            if (lastSnapshot != null && lastSnapshot.getNetworkHandle() != networkHandle) {
                // 新网络快照已经生效时，迟到的旧网络丢失事件不得拆掉新 listener。
                return;
            }
            lastSnapshot = null;
            stopEngineSafely();
            if (desiredRunning) {
                publish(new RuntimeStatus(
                        RuntimeStatus.Phase.WAITING_NETWORK,
                        "NETWORK_UNAVAILABLE", "", enabledPeerCountSafely()));
            }
        });
    }

    private void handleStart(final long generation) {
        if (!isCurrentCommand(generation)) {
            return;
        }
        desiredRunning = true;
        acquireWakeLock();
        refreshConfiguredDisplay("");
        publish(new RuntimeStatus(RuntimeStatus.Phase.STARTING, "", "", enabledPeerCountSafely()));
        try {
            if (!monitorStarted) {
                networkMonitor.start(this);
                monitorStarted = true;
            }
            if (lastSnapshot == null) {
                publish(new RuntimeStatus(
                        RuntimeStatus.Phase.WAITING_NETWORK,
                        "NETWORK_UNAVAILABLE", "", enabledPeerCountSafely()));
            } else {
                applySnapshot(lastSnapshot, true);
            }
        } catch (final RuntimeException error) {
            fail("RUNTIME_START_FAILED");
        }
    }

    private void handleReload(final long generation, final int startId) {
        if (!isCurrentCommand(generation)) {
            return;
        }
        if (!desiredRunning) {
            // 新进程收到过期 reload Intent 时保持停止，不扩大用户授权。
            handleStop(generation, startId);
            return;
        }
        try {
            refreshConfiguredDisplay(lastSnapshot == null
                    || !lastSnapshot.getEndpointCandidate().isPresent()
                    ? ""
                    : withoutZone(lastSnapshot.getEndpointCandidate().get()
                            .getAddress().getHostAddress()));
            stopDdns("DISABLED");
            if (lastSnapshot != null) {
                applySnapshot(lastSnapshot, true);
            }
        } catch (final RuntimeException error) {
            fail("RUNTIME_RELOAD_FAILED");
        }
    }

    private void handleRevocation(String id, ResultReceiver receiver, int startId) {
        int response = 0;
        try {
            EncryptedStateRepository repository = repository();
            PeerRevocationController controller = new PeerRevocationController(
                    repository::revokePeerAuthorization,
                    new PeerRevocationController.Runtime() {
                        @Override public void synchronizeAuthorization() throws Exception {
                            // Stop old sessions before any settings/DDNS/rendering can fail.
                            stopEngineForRevocation();
                            if (!desiredRunning || destroyed) return;
                            if (lastSnapshot == null) {
                                publish(new RuntimeStatus(RuntimeStatus.Phase.WAITING_NETWORK,
                                        "NETWORK_UNAVAILABLE", "", enabledPeerCountSafely()));
                                return;
                            }
                            refreshConfiguredDisplay(lastSnapshot.getEndpointCandidate().isPresent()
                                    ? withoutZone(lastSnapshot.getEndpointCandidate().get()
                                            .getAddress().getHostAddress()) : "");
                            stopDdns("DISABLED");
                            applySnapshot(lastSnapshot, true);
                            if (RuntimeStatusStore.current().phase() == RuntimeStatus.Phase.ERROR) {
                                throw new NativeEngineException("REVOCATION_SYNC_FAILED");
                            }
                        }
                        @Override public void stopService() { stopAfterRevocationFailure(); }
                    }, ((AtticusServerApplication) getApplication())::requestKeyCleanup);
            PeerRevocationController.Result result = controller.revoke(id);
            response = result == PeerRevocationController.Result.APPLIED
                    ? REVOCATION_APPLIED : REVOCATION_SERVICE_STOPPED;
        } catch (Exception ignored) {
            // Persistence failures and fixed runtime failures never expose provider text.
        } finally {
            if (receiver != null) {
                try { receiver.send(response, null); }
                catch (RuntimeException ignored) { /* UI lifetime cannot affect revocation. */ }
            }
            if (!desiredRunning) handleStop(commandGeneration, startId);
        }
    }

    /** Unlike best-effort lifecycle cleanup, revocation must not swallow native stop failure. */
    private void stopEngineForRevocation() throws NativeEngineException {
        NativeEngine nativeEngine = engine != null ? engine : new ReflectiveNativeEngine();
        nativeEngine.stop(); // Also closes the global native engine if a failed reload cleared Java's field.
        engine = null;
        engineRunning = false;
        activeSignature = "";
        activeUpstreams = "";
        cancelNetworkCheck();
    }

    private void stopAfterRevocationFailure() {
        desiredRunning = false;
        synchronized (commandLock) { commandGeneration++; }
        try {
            stopEngineForRevocation();
        } catch (Exception | LinkageError cannotConfirmClosed) {
            // Last resort: process-owned sockets cannot survive termination. Never claim safe shutdown.
            android.os.Process.killProcess(android.os.Process.myPid());
            throw new IllegalStateException("REVOCATION_STOP_FAILED");
        }
        stopDdns("STOPPED");
        networkMonitor.stop();
        monitorStarted = false;
        releaseWakeLock();
        publish(RuntimeStatus.stopped());
        synchronized (commandLock) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            lastNotificationKey = "";
        }
        stopSelf();
    }

    private void applySnapshot(final NetworkSnapshot snapshot, final boolean force) {
        if (RuntimeNetworkPolicy.isRejectedDefaultType(snapshot.getNetworkType())) {
            stopEngineSafely();
            stopDdns("WAITING_PHYSICAL_NETWORK");
            publish(new RuntimeStatus(
                    RuntimeStatus.Phase.WAITING_NETWORK,
                    RuntimeNetworkPolicy.DEFAULT_VPN_REJECTED,
                    "",
                    enabledPeerCountSafely()));
            return;
        }
        final String waitingCode = RuntimeNetworkPolicy.waitingCode(
                snapshot.isValidated(), snapshot.getEndpointCandidate().isPresent());
        if (!waitingCode.isEmpty()) {
            stopEngineSafely();
            publish(new RuntimeStatus(
                    RuntimeStatus.Phase.WAITING_NETWORK,
                    waitingCode, "", enabledPeerCountSafely()));
            return;
        }

        if (!ddnsLifecycleStarted) {
            try {
                restartDdns();
            } catch (final RuntimeException error) {
                fail("DDNS_CONFIGURATION_FAILED");
                return;
            }
        }

        final String endpoint = withoutZone(
                snapshot.getEndpointCandidate().get().getAddress().getHostAddress());
        try {
            final EncryptedStateRepository repository = repository();
            final String uapi;
            final String upstreams;
            final int mtu;
            final int peers;
            synchronized (repository) {
                final ServerState state = repository.state();
                uapi = configFactory.renderServerUapi(state);
                upstreams = DnsUpstreamResolver.resolve(state, snapshot);
                mtu = state.mtu;
                peers = countEnabled(state);
                configuredListenPort = state.listenPort;
                configuredDnsEnabled = state.dnsEnabled;
                configuredDdnsProvider = AppSettingsPolicy.normalizeProvider(state.ddnsProvider);
                configuredEndpoint = EndpointFormatter.format(
                        AppSettingsPolicy.resolveEndpointHost(state, endpoint),
                        state.listenPort);
            }
            // Endpoint GUA 必须进入签名：同一个 Android Network 上 IPv6 重编号也需要
            // 重建 listener，不能只看 networkHandle。
            final String signature = snapshot.getNetworkHandle() + ":" + endpoint + ":" + mtu + ":"
                    + upstreams + ":" + uapi.hashCode();
            if (!force && engineRunning && signature.equals(activeSignature)) {
                publish(new RuntimeStatus(RuntimeStatus.Phase.RUNNING, "", endpoint, peers));
                return;
            }

            stopEngineSafely();
            engine = new ReflectiveNativeEngine();
            engine.setNetworkHandle(snapshot.getNetworkHandle());
            engine.start(uapi, upstreams, mtu);
            final Network androidNetwork = snapshot.getAndroidNetwork()
                    .orElseThrow(() -> new NativeEngineException("ANDROID_NETWORK_OBJECT_UNAVAILABLE"));
            bindListenerSockets(androidNetwork, engine);
            engineRunning = true;
            dev.lan.atticusvpn.server.logging.RuntimeLogCollector.current().engineStarted(dev.lan.atticusvpn.server.logging.RunLogModel.Protocol.WG);
            activeSignature = signature;
            activeUpstreams = upstreams;
            publish(new RuntimeStatus(RuntimeStatus.Phase.RUNNING, "", endpoint, peers));
            startNetworkCheck();
        } catch (final NativeEngineException error) {
            stopEngineSafely();
            fail(error.safeCode());
        } catch (final Exception error) {
            stopEngineSafely();
            fail("RUNTIME_CONFIGURATION_FAILED");
        }
    }

    /** 用 detach 保证 ParcelFileDescriptor 永不取得 native listener fd 的最终所有权。 */
    private static void bindListenerSockets(final Network network, final NativeEngine nativeEngine)
            throws Exception {
        final int fd4 = nativeEngine.socketFd4();
        final int fd6 = nativeEngine.socketFd6();
        if (fd6 < 0) {
            throw new NativeEngineException("NATIVE_IPV6_LISTENER_UNAVAILABLE");
        }
        // 公网入口是 IPv6，因此 fd6 必须存在且绑定成功。IPv4 listener 只服务可选
        // 的本机/双栈路径，失败不影响动态公网 IPv6 的主要能力。
        bindBorrowedFd(network, fd6);
        try {
            bindBorrowedFd(network, fd4);
        } catch (final Exception ignored) {
            // 固定为 best-effort，不输出 fd、网络句柄或异常原文。
        }
    }

    private static void bindBorrowedFd(final Network network, final int fd) throws Exception {
        if (fd < 0) {
            return;
        }
        final ParcelFileDescriptor descriptor = ParcelFileDescriptor.adoptFd(fd);
        try {
            network.bindSocket(descriptor.getFileDescriptor());
        } finally {
            descriptor.detachFd();
        }
    }

    private void restartDdns() {
        if (ddnsManager != null) {
            ddnsManager.close();
            ddnsManager = null;
        }
        ddnsStatus = "DISABLED";
        ddnsLifecycleStarted = false;
        try {
            final EncryptedStateRepository repository = repository();
            synchronized (repository) {
                ddnsManager = DdnsRuntimeFactory.create(this, repository.state());
            }
            if (ddnsManager != null) {
                ddnsStatus = "WAITING";
                ddnsManager.start(result -> {
                    enqueueCallback(() -> {
                        // DdnsUpdateResult 由 provider 层保证脱敏；这里只传播枚举。
                        ddnsStatus = result.getStatus().name();
                        pollRuntimeStatus();
                    });
                });
            }
            ddnsLifecycleStarted = true;
        } catch (final Exception error) {
            throw new IllegalStateException("DDNS_CONFIGURATION_FAILED");
        }
    }

    /** 停止 DDNS 网络活动，并保留固定、非敏感的 UI 状态。 */
    private void stopDdns(final String status) {
        if (ddnsManager != null) {
            ddnsManager.close();
            ddnsManager = null;
        }
        ddnsLifecycleStarted = false;
        ddnsStatus = status;
    }

    private void handleStop(final long generation, final int startId) {
        if (!isCurrentCommand(generation)) {
            return;
        }
        desiredRunning = false;
        publish(new RuntimeStatus(RuntimeStatus.Phase.STOPPING, "", "", 0));
        stopEngineSafely();
        stopDdns("STOPPED");
        if (monitorStarted) {
            networkMonitor.stop();
            monitorStarted = false;
        }
        releaseWakeLock();
        synchronized (commandLock) {
            if (destroyed || generation != commandGeneration) {
                // 清理期间若出现新 START，后续串行任务会按新意图重建数据面。
                return;
            }
            publish(RuntimeStatus.stopped());
            stopForeground(STOP_FOREGROUND_REMOVE);
            lastNotificationKey = "";
            stopSelfResult(startId);
        }
    }

    /** 判断后台任务是否仍对应最新命令。 */
    private boolean isCurrentCommand(final long generation) {
        synchronized (commandLock) {
            return !destroyed && generation == commandGeneration;
        }
    }

    /**
     * 安全投递网络或 DDNS 回调；销毁开始后丢弃迟到事件，避免已关闭执行器抛错。
     */
    private void enqueueCallback(final Runnable runnable) {
        if (destroyed) {
            return;
        }
        try {
            worker.execute(() -> {
                if (!destroyed) {
                    runnable.run();
                }
            });
        } catch (final RejectedExecutionException ignored) {
            // onDestroy 与系统网络回调竞态时，迟到事件按已销毁处理。
        }
    }

    private void stopEngineSafely() {
        cancelNetworkCheck();
        if(engineRunning && engine!=null)try {
            dev.lan.atticusvpn.server.logging.RuntimeLogCollector.current().nativeSnapshot(
                    dev.lan.atticusvpn.server.logging.RunLogModel.Protocol.WG,engine.status(),repository());
        }catch(Exception ignored){dev.lan.atticusvpn.server.logging.RuntimeLogCollector.current().event("WG","TELEMETRY_READ_FAILED");}
        if (engine != null) {
            try {
                engine.stop();
            } catch (final NativeEngineException ignored) {
                // 停止仍继续释放 Java 生命周期；只对外提供固定状态。
            }
        }
        engine = null;
        engineRunning = false;
        activeSignature = "";
        activeUpstreams = "";
    }

    /** 每次真实启动/重建后自动检测，也可由按钮复测；总期限 25 秒。 */
    private void startNetworkCheck() {
        if (!desiredRunning || !engineRunning || lastSnapshot == null
                || !lastSnapshot.getAndroidNetwork().isPresent()) return;
        if (networkCheck == NetworkCheckResult.CHECKING) return;
        cancelNetworkCheck();
        final long generation = checkGeneration;
        final Network network = lastSnapshot.getAndroidNetwork().get();
        final String upstreams = activeUpstreams;
        final ServerNetworkProbe probe = new ServerNetworkProbe();
        activeProbe = probe;
        networkCheck = NetworkCheckResult.CHECKING;
        publish(RuntimeStatusStore.current());
        try {
            probeWorker.purge();
            probeTask = probeWorker.submit(() -> {
                final NetworkCheckResult result = probe.check(network, upstreams);
                enqueueCallback(() -> {
                    if (generation != checkGeneration || !engineRunning || !desiredRunning) return;
                    if (probeDeadline != null) probeDeadline.cancel(false);
                    activeProbe = null;
                    networkCheck = result;
                    publish(RuntimeStatusStore.current());
                });
            });
        } catch (final RejectedExecutionException ignored) {
            probe.cancel();
            activeProbe = null;
            networkCheck = NetworkCheckResult.UNAVAILABLE;
            publish(RuntimeStatusStore.current());
            return;
        }
        probeDeadline = worker.schedule(() -> {
            if (generation == checkGeneration && networkCheck == NetworkCheckResult.CHECKING) {
                cancelNetworkCheck();
                networkCheck = NetworkCheckResult.TIMEOUT;
                publish(RuntimeStatusStore.current());
            }
        }, 25L, TimeUnit.SECONDS);
    }

    /** 先换代再取消，迟到结果永远不能覆盖断开/新网络的状态。 */
    private void cancelNetworkCheck() {
        checkGeneration++;
        if (probeDeadline != null) probeDeadline.cancel(false);
        if (activeProbe != null) activeProbe.cancel();
        if (probeTask != null) probeTask.cancel(true);
        probeWorker.purge();
        activeProbe = null; probeTask = null; probeDeadline = null;
        networkCheck = NetworkCheckResult.IDLE;
    }

    private EncryptedStateRepository repository() throws Exception {
        return ((AtticusServerApplication) getApplication()).awaitRepository();
    }

    private int enabledPeerCountSafely() {
        try {
            final EncryptedStateRepository repository = repository();
            synchronized (repository) {
                return countEnabled(repository.state());
            }
        } catch (final Exception ignored) {
            return 0;
        }
    }

    private static int countEnabled(final ServerState state) {
        int count = 0;
        for (final StoredPeer peer : state.peers()) {
            if (peer.enabled()) {
                count++;
            }
        }
        return count;
    }

    /** 即使网络尚未就绪，也把持久端口、固定域名和 DNS 开关显示在主状态中。 */
    private void refreshConfiguredDisplay(final String currentGlobalIpv6) {
        try {
            final EncryptedStateRepository repository = repository();
            synchronized (repository) {
                final ServerState state = repository.state();
                configuredListenPort = state.listenPort;
                configuredDnsEnabled = state.dnsEnabled;
                configuredDdnsProvider = AppSettingsPolicy.normalizeProvider(state.ddnsProvider);
                try {
                    configuredEndpoint = EndpointFormatter.format(
                            AppSettingsPolicy.resolveEndpointHost(state, currentGlobalIpv6),
                            state.listenPort);
                } catch (final RuntimeException unavailable) {
                    configuredEndpoint = "";
                }
            }
        } catch (final Exception ignored) {
            configuredListenPort = 0;
            configuredEndpoint = "";
            configuredDnsEnabled = false;
            configuredDdnsProvider = "disabled";
        }
    }

    private void fail(final String safeCode) {
        // 保留用户“正在运行”的意图。网络重编号/短暂 native 失败后，后续
        // NetworkCallback 或“保存并应用”可自动重试；只有 STOP 会清除此标志。
        stopEngineSafely();
        publish(new RuntimeStatus(RuntimeStatus.Phase.ERROR, safeCode, "", 0));
    }

    private void publish(final RuntimeStatus status) {
        if (destroyed) {
            return;
        }
        final String networkType = lastSnapshot == null
                ? "UNKNOWN" : lastSnapshot.getNetworkType().name();
        final RuntimeStatus enriched = new RuntimeStatus(
                status.phase(), status.safeCode(), status.endpointAddress(), status.enabledPeers(),
                networkType, ddnsStatus, status.peers(), configuredListenPort,
                configuredEndpoint, configuredDnsEnabled, configuredDdnsProvider)
                .withNetworkCheck(networkCheck);
        RuntimeStatusStore.publish(enriched);
        showNotification(statusText(enriched), false);
    }

    /** 每五秒读取 native 的脱敏 JSON，并在内存中把公钥映射为 Peer 名称。 */
    private void pollRuntimeStatus() {
        if (destroyed) {
            return;
        }
        if (desiredRunning) {
            // 使用有限时锁并由 5 秒轮询续租，异常退出最多保留十分钟而不是永久持锁。
            acquireWakeLock();
        }
        if (!engineRunning || engine == null || !desiredRunning) {
            return;
        }
        try {
            final String json = engine.status();
            if (json == null || json.length() > dev.lan.atticusvpn.server.logging.RuntimeLogCollector.MAX_NATIVE_JSON) {
                dev.lan.atticusvpn.server.logging.RuntimeLogCollector.current().event("WG","TELEMETRY_READ_FAILED");
                return;
            }
            dev.lan.atticusvpn.server.logging.RuntimeLogCollector.current().nativeSnapshot(
                    dev.lan.atticusvpn.server.logging.RunLogModel.Protocol.WG,json,repository());
            final List<RuntimeStatus.PeerRuntime> peerStats = parsePeerStats(json);
            final RuntimeStatus current = RuntimeStatusStore.current();
            publish(new RuntimeStatus(
                    RuntimeStatus.Phase.RUNNING,
                    "",
                    current.endpointAddress(),
                    enabledPeerCountSafely(),
                    current.networkType(),
                    ddnsStatus,
                    peerStats,
                    configuredListenPort,
                    configuredEndpoint,
                    configuredDnsEnabled,
                    configuredDdnsProvider));
        } catch (final Exception ignored) {
            dev.lan.atticusvpn.server.logging.RuntimeLogCollector.current().event("WG","TELEMETRY_READ_FAILED");
        }
    }

    private List<RuntimeStatus.PeerRuntime> parsePeerStats(final String json) throws Exception {
        final Map<String, String> namesByHexKey = new HashMap<>();
        final EncryptedStateRepository repository = repository();
        synchronized (repository) {
            for (final StoredPeer peer : repository.state().peers()) {
                namesByHexKey.put(Key.fromBase64(peer.publicKey()).toHex(), peer.name());
            }
        }
        final JSONArray peers = new JSONObject(json).optJSONArray("peers");
        final List<RuntimeStatus.PeerRuntime> result = new ArrayList<>();
        if (peers == null) {
            return result;
        }
        for (int index = 0; index < peers.length(); index++) {
            final JSONObject item = peers.optJSONObject(index);
            if (item == null) {
                continue;
            }
            final String name = namesByHexKey.get(item.optString("publicKey", ""));
            if (name == null) {
                continue;
            }
            result.add(new RuntimeStatus.PeerRuntime(
                    name,
                    item.optLong("latestHandshakeUnix", 0L),
                    item.optLong("receiveBytes", 0L),
                    item.optLong("transmitBytes", 0L)));
        }
        return result;
    }

    /** One serialized entry point for every notification write and its dedup key. */
    private void showNotification(final String text, final boolean foreground) {
        synchronized (commandLock) {
            if (destroyed) return;
            final Notification notification = buildNotification(text);
            final String key = notification.extras.getCharSequence(Notification.EXTRA_TITLE)
                    + "\n" + notification.extras.getCharSequence(Notification.EXTRA_TEXT)
                    + "\n" + notification.actions[0].title;
            if (foreground) {
                startForeground(NOTIFICATION_ID, notification);
            } else {
                final NotificationManager manager =
                        (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (manager == null || key.equals(lastNotificationKey)) return;
                manager.notify(NOTIFICATION_ID, notification);
            }
            lastNotificationKey = key;
        }
    }

    private Notification buildNotification(final String text) {
        final Intent activityIntent = new Intent(this, MainActivity.class);
        final PendingIntent activity = PendingIntent.getActivity(
                this, 0, activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        final PendingIntent stop = PendingIntent.getService(
                this, 1,
                new Intent(this, AtticusServerService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        final Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_atticus_shield)
                .setContentTitle(localized(R.string.server_running))
                .setContentText(text)
                .setContentIntent(activity)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_atticus_shield),
                        localized(R.string.stop), stop).build())
                .build();
    }

    private String statusText(final RuntimeStatus status) {
        switch (status.phase()) {
            case RUNNING:
                return localized(R.string.notification_running, status.enabledPeers());
            case WAITING_NETWORK:
                return localized(R.string.server_waiting_network);
            case ERROR:
                return localized(R.string.notification_error, status.safeCode());
            case STOPPING:
                return localized(R.string.notification_stopping);
            case STARTING:
                return localized(R.string.notification_starting);
            default:
                return localized(R.string.notification_stopped);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        final NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        final NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                localized(R.string.server_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(localized(R.string.server_channel_description));
        manager.createNotificationChannel(channel);
    }

    private void acquireWakeLock() {
        if (wakeLock != null && (!wakeLock.isHeld() || android.os.SystemClock.elapsedRealtime() >= wakeRenewAt)) {
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MILLIS);
            wakeRenewAt=android.os.SystemClock.elapsedRealtime()+WAKE_LOCK_TIMEOUT_MILLIS/2;
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private static String withoutZone(final String address) {
        final int zone = address.indexOf('%');
        return zone < 0 ? address : address.substring(0, zone);
    }

    @Override
    public void onDestroy() {
        AppLanguage.preferences(this).unregisterOnSharedPreferenceChangeListener(languageListener);
        synchronized (commandLock) {
            destroyed = true;
            commandGeneration++;
        }
        // 先阻止新回调进入；数据面/DDNS/唤醒锁仍由同一 worker 按顺序清理。
        networkMonitor.stop();
        worker.execute(() -> {
            desiredRunning = false;
            stopDdns("STOPPED");
            stopEngineSafely();
            monitorStarted = false;
            releaseWakeLock();
            RuntimeStatusStore.publish(new RuntimeStatus(
                    RuntimeStatus.Phase.STOPPED, "", "", 0,
                    lastSnapshot == null ? "UNKNOWN" : lastSnapshot.getNetworkType().name(),
                    ddnsStatus, java.util.Collections.emptyList(), configuredListenPort,
                    configuredEndpoint, configuredDnsEnabled, configuredDdnsProvider));
        });
        // shutdown 会执行已排队清理，但取消周期任务；不从主线程并发触碰 native 状态。
        worker.shutdown();
        probeWorker.shutdownNow();
        super.onDestroy();
    }
}
