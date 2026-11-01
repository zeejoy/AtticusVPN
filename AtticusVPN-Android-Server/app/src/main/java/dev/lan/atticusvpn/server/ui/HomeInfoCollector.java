/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.ui;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.net.InetAddress;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import dev.lan.atticusvpn.server.AtticusServerApplication;
import dev.lan.atticusvpn.server.config.AppSettingsPolicy;
import dev.lan.atticusvpn.server.core.EndpointFormatter;
import dev.lan.atticusvpn.server.model.ServerState;
import dev.lan.atticusvpn.server.model.StoredPeer;
import dev.lan.atticusvpn.server.network.AndroidNetworkMonitor;
import dev.lan.atticusvpn.server.network.GlobalIpv6Selector;
import dev.lan.atticusvpn.server.network.NetworkSnapshot;
import dev.lan.atticusvpn.server.runtime.DnsUpstreamResolver;
import dev.lan.atticusvpn.server.runtime.NetworkCheckResult;
import dev.lan.atticusvpn.server.runtime.RuntimeNetworkPolicy;
import dev.lan.atticusvpn.server.runtime.RuntimeStatus;
import dev.lan.atticusvpn.server.runtime.RuntimeStatusStore;
import dev.lan.atticusvpn.server.runtime.ServerNetworkProbe;
import dev.lan.atticusvpn.server.storage.EncryptedStateRepository;

/**
 * 首页终端的只读、分阶段信息采集器。
 *
 * <p>设置读取和手机网络检测不依赖服务器是否启动；这里既不调用服务，也不执行 su、
 * DDNS 更新或设置写入。HTTPS 与 DNS 检测完全复用既有 {@link ServerNetworkProbe}，
 * 使用平台 CA 和主机名校验，不放宽 TLS，也不上传配置或身份信息。</p>
 *
 * <p>所有公开方法和 Listener 回调在主线程执行。阻塞工作进入最多两个线程、两个排队
 * 任务的有界池；重开一帧时取消旧任务，并以 generation 拒绝迟到结果。Android 域名
 * 解析在少数系统上可能不响应线程中断，因此不无限创建替代线程，界面的五秒期限仍然
 * 独立成立。关闭时注销网络回调、移除计时器并关闭检测 socket。</p>
 */
public final class HomeInfoCollector implements AutoCloseable {
    private static final long ITEM_WAIT_MS = 5_000L;
    private static final long FRAME_MAX_MS = 20_000L;
    private static final long NETWORK_SETTLE_MS = 160L;
    private static final long CHANGE_DEBOUNCE_MS = 300L;

    /** 某项已返回但失败，与尚未返回的 ready=false 明确区分。 */
    public enum SafeError { NONE, SETTINGS_UNAVAILABLE, NETWORK_UNAVAILABLE, INFORMATION_TIMEOUT }

    /** 终端光标抵达尚未就绪的字段时，只给该字段再等最多五秒。 */
    public enum Field { SETTINGS, NETWORK, PROBE }

    public interface Listener {
        /** 设置、网络属性或检测结果有新进度；这不要求重新开始打印整页。 */
        void onSnapshot(Snapshot snapshot);

        /** 真正的外部网络变化，经去重/合并后要求调用方重开一帧。 */
        void onInformationChanged();
    }

    /**
     * 仅包含首页已经展示的公开信息；不持有 ServerState、Network、私钥、PSK、token。
     * ready=true 表示该阶段已经结束，失败原因由对应 error/result 字段表达。
     */
    public static final class Snapshot {
        public final long generation;
        public final boolean settingsReady;
        public final boolean networkReady;
        public final boolean probeReady;
        public final SafeError settingsError;
        public final SafeError networkError;
        public final NetworkCheckResult networkCheck;
        public final RuntimeStatus.Phase phase;
        public final String safeCode;
        public final int enabledPeers;
        public final String networkType;
        public final String globalIpv6;
        public final String endpointHost;
        public final String endpoint;
        public final int listenPort;
        public final int mtu;
        public final boolean dnsEnabled;
        public final String ddnsProvider;
        public final String ddnsStatus;

        private Snapshot(final HomeInfoCollector owner) {
            generation = owner.generation;
            settingsReady = owner.settingsReady;
            networkReady = owner.networkReady;
            probeReady = owner.probeReady;
            settingsError = owner.settingsError;
            networkError = owner.networkError;
            networkCheck = owner.networkCheck;
            final RuntimeStatus runtime = RuntimeStatusStore.current();
            phase = runtime.phase();
            safeCode = runtime.safeCode();
            final PublicSettings settings = owner.settings;
            enabledPeers = settings == null ? runtime.enabledPeers() : settings.enabledPeers;
            networkType = owner.network == null ? "UNKNOWN" : owner.network.getNetworkType().name();
            globalIpv6 = ipv6(owner.network);
            endpointHost = settings == null ? "" : settings.endpointHost;
            listenPort = settings == null ? 0 : settings.listenPort;
            mtu = settings == null ? 0 : settings.mtu;
            dnsEnabled = settings != null && settings.dnsEnabled;
            ddnsProvider = settings == null ? "disabled" : settings.ddnsProvider;
            ddnsStatus = "disabled".equals(ddnsProvider) ? "DISABLED" : runtime.ddnsStatus();
            endpoint = endpoint(settings, globalIpv6);
        }
    }

    private final Context applicationContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(
            2, 2, 15L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2), runnable -> {
                final Thread thread = new Thread(runnable, "AtticusVPN-HomeInfo");
                thread.setDaemon(true);
                return thread;
            });
    private final Runnable[] itemDeadlines = new Runnable[Field.values().length];

    private Listener listener;
    private AndroidNetworkMonitor monitor;
    private NetworkSnapshot network;
    private PublicSettings settings;
    private boolean settingsReady;
    private boolean networkReady;
    private boolean probeReady;
    private SafeError settingsError = SafeError.NONE;
    private SafeError networkError = SafeError.NONE;
    private NetworkCheckResult networkCheck = NetworkCheckResult.CHECKING;
    private long generation;
    private long monitorGeneration;
    private long frameStarted;
    private long probeSerial;
    private boolean started;
    private boolean closed;
    private boolean frameFinished;
    private boolean monitorUnavailable;
    private String stableNetworkKey;
    private Future<?> settingsTask;
    private Future<?> availabilityTask;
    private Future<?> probeTask;
    private ServerNetworkProbe probe;
    private Runnable launchProbe;
    private Runnable changeNotification;
    private Runnable frameDeadline;
    private Snapshot current;

    public HomeInfoCollector(final Context context) {
        applicationContext = Objects.requireNonNull(context, "context").getApplicationContext();
        workers.allowCoreThreadTimeOut(true);
        current = new Snapshot(this);
    }

    /** 注册一次网络观察器；start 后调用 refresh 开始本帧采集。 */
    public void start(final Listener newListener) {
        requireMain();
        if (closed) throw new IllegalStateException("HOME_COLLECTOR_CLOSED");
        listener = Objects.requireNonNull(newListener, "listener");
        if (started) return;
        started = true;
        final long monitoring = ++monitorGeneration;
        try {
            monitor = new AndroidNetworkMonitor(applicationContext, new GlobalIpv6Selector());
            monitor.start(new AndroidNetworkMonitor.Listener() {
                @Override
                public void onNetworkSnapshot(final NetworkSnapshot value) {
                    main.post(() -> {
                        // 注销后系统线程已经投递的事件也可能稍后到达。Activity 快速返回时，
                        // 必须确认事件属于这一次注册，不能把旧观察器的网络复活到新页面。
                        if (isMonitoring(monitoring)) acceptNetwork(value);
                    });
                }

                @Override
                public void onNetworkLost(final long networkHandle) {
                    main.post(() -> {
                        if (!isMonitoring(monitoring) || network == null
                                || network.getNetworkHandle() != networkHandle) return;
                        acceptNetwork(null);
                    });
                }
            });
        } catch (final RuntimeException ignored) {
            // 缺权限或系统回调注册失败只发布固定错误，不回显系统异常信息。
            monitorUnavailable = true;
            if (monitor != null) monitor.stop();
            monitor = null;
        }
    }

    /**
     * 立即开始读取；1 秒开场停顿和逐字打印由终端视图负责。返回值标识唯一的一帧，
     * 接收者必须拒绝旧 generation 的快照。即使服务器停止也会进行绑定默认网络的检测。
     */
    public long refresh() {
        requireMain();
        if (!started || closed) throw new IllegalStateException("HOME_COLLECTOR_NOT_STARTED");
        cancelWork();
        if (changeNotification != null) main.removeCallbacks(changeNotification);
        changeNotification = null;
        generation++;
        final long frame = generation;
        frameStarted = SystemClock.elapsedRealtime();
        frameFinished = false;
        settings = null;
        settingsReady = false;
        settingsError = SafeError.NONE;
        networkReady = monitorUnavailable || meaningful(network);
        networkError = monitorUnavailable ? SafeError.NETWORK_UNAVAILABLE : SafeError.NONE;
        probeReady = false;
        networkCheck = NetworkCheckResult.CHECKING;
        publish();

        // 此总期限是兜底；正常情况下光标等候的各字段期限会更早终止未完成项。
        frameDeadline = () -> {
            if (!isCurrent(frame)) return;
            timeout(frame, Field.SETTINGS);
            timeout(frame, Field.NETWORK);
            timeout(frame, Field.PROBE);
        };
        main.postDelayed(frameDeadline, FRAME_MAX_MS);
        try {
            settingsTask = workers.submit(() -> collectSettings(frame));
        } catch (final RejectedExecutionException ignored) {
            settingsReady = true;
            settingsError = SafeError.SETTINGS_UNAVAILABLE;
            completeProbe(NetworkCheckResult.UNAVAILABLE);
        }
        if (!networkReady) {
            // 此查询不在 Android 的 NetworkCallback 内执行，只用于识别“没有默认网络”。
            // 有网络时仍等待观察器提供能力/地址/DNS，不使用可能过时的同步链路属性。
            try {
                availabilityTask = workers.submit(() -> checkAvailability(frame));
            } catch (final RejectedExecutionException ignored) {
                // 不创建无限线程；光标所设的五秒期限负责在繁忙时给出超时提示。
            }
        }
        scheduleProbe();
        return frame;
    }

    public Snapshot snapshot() {
        requireMain();
        return current;
    }

    public void awaitProbeDeadline(final long frame) {
        awaitDeadline(frame, Field.PROBE);
    }

    /** 同一帧/同一字段只设一次计时器，数据回调不会不断延长等待时间。 */
    public void awaitDeadline(final long frame, final Field field) {
        requireMain();
        Objects.requireNonNull(field, "field");
        if (!isCurrent(frame) || ready(field) || itemDeadlines[field.ordinal()] != null) return;
        final Runnable deadline = () -> timeout(frame, field);
        itemDeadlines[field.ordinal()] = deadline;
        main.postDelayed(deadline, ITEM_WAIT_MS);
    }

    /** 页面完成后取消仍未完成的工作；不产生新的快照或自动重试。 */
    public void finishFrame(final long frame) {
        requireMain();
        if (!isCurrent(frame)) return;
        frameFinished = true;
        // 中断当前帧的仓库等待不会中断 Application 自己的初始化任务；只移除本采集器
        // 的等待、检测与字段期限。默认网络观察器和下一帧变化通知继续保持注册。
        cancelWork();
    }

    /** 可重复停止；注销默认网络观察器，并使全部迟到结果失效。 */
    public void stop() {
        requireMain();
        if (!started) return;
        started = false;
        ++monitorGeneration;
        generation++;
        frameFinished = true;
        cancelWork();
        if (changeNotification != null) main.removeCallbacks(changeNotification);
        changeNotification = null;
        if (monitor != null) monitor.stop();
        monitor = null;
        network = null;
        stableNetworkKey = null;
        monitorUnavailable = false;
        listener = null;
    }

    @Override
    public void close() {
        requireMain();
        if (closed) return;
        stop();
        closed = true;
        workers.shutdownNow();
    }

    private void collectSettings(final long frame) {
        try {
            final AtticusServerApplication application =
                    (AtticusServerApplication) applicationContext;
            final EncryptedStateRepository repository = application.awaitRepository();
            final PublicSettings result;
            synchronized (repository) {
                result = new PublicSettings(repository.state());
            }
            main.post(() -> {
                if (!isCurrent(frame) || settingsReady) return;
                settings = result;
                settingsReady = true;
                settingsError = SafeError.NONE;
                clearDeadline(Field.SETTINGS);
                publish();
                scheduleProbe();
            });
        } catch (final Exception ignored) {
            main.post(() -> {
                if (!isCurrent(frame) || settingsReady) return;
                settingsReady = true;
                settingsError = SafeError.SETTINGS_UNAVAILABLE;
                clearDeadline(Field.SETTINGS);
                publish();
                completeProbe(NetworkCheckResult.UNAVAILABLE);
            });
        }
    }

    private void checkAvailability(final long frame) {
        try {
            final ConnectivityManager manager = (ConnectivityManager)
                    applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            final boolean unavailable = manager == null || manager.getActiveNetwork() == null;
            if (unavailable) main.post(() -> {
                if (!isCurrent(frame) || network != null || networkReady) return;
                networkReady = true;
                networkError = SafeError.NETWORK_UNAVAILABLE;
                clearDeadline(Field.NETWORK);
                publish();
                completeProbe(NetworkCheckResult.UNAVAILABLE);
            });
        } catch (final RuntimeException ignored) {
            // 观察器可能稍后返回有效信息；不因一次查询异常覆盖其结果。
        }
    }

    private void acceptNetwork(final NetworkSnapshot value) {
        if (!started || closed) return;
        final String key = networkKey(value);
        final boolean externallyChanged = stableNetworkKey != null
                && !stableNetworkKey.equals(key);
        final boolean recovered = networkError != SafeError.NONE && meaningful(value);
        final boolean readyNow = value == null || meaningful(value);
        network = value;
        if (readyNow) {
            stableNetworkKey = key;
            networkReady = true;
            networkError = value == null ? SafeError.NETWORK_UNAVAILABLE : SafeError.NONE;
            clearDeadline(Field.NETWORK);
        } else {
            // 切网最先到来的 onAvailable 尚无能力/链路属性，不能把旧网络的就绪标记
            // 留给新网络。光标应等待随后到来的能力信息，而不是显示旧出口已通过。
            networkReady = false;
            networkError = SafeError.NONE;
        }
        if (externallyChanged || recovered) {
            cancelProbe();
            probeReady = false;
            networkCheck = NetworkCheckResult.CHECKING;
        }
        // 已完成的帧不再接收新进度；网络观察仍更新内部状态并发送变化事件，让调用方
        // 启动下一帧。这样迟到的设置/检测结果和网络回调都不会改写已经打印完的内容。
        if (generation > 0L && !frameFinished) publish();
        // 初始 onAvailable/capabilities/linkProperties 会连续到达；合并为同一帧，避免
        // 每个中间状态都重播终端。之后只对网络句柄、传输、GUA 或 DNS 的真实变化重播。
        if ((externallyChanged || recovered) && readyNow
                && SystemClock.elapsedRealtime() - frameStarted >= 1_000L) {
            if (changeNotification != null) main.removeCallbacks(changeNotification);
            changeNotification = () -> {
                changeNotification = null;
                if (started && !closed && listener != null) listener.onInformationChanged();
            };
            main.postDelayed(changeNotification, CHANGE_DEBOUNCE_MS);
        } else {
            // 初始链路属性补全时尚未开帧，短暂合并后才选定检测网络和 DNS。
            if (!probeReady && probe == null) scheduleProbe();
        }
    }

    private void scheduleProbe() {
        if (!started || closed || frameFinished || generation == 0L || probeReady || probe != null
                || !settingsReady || !networkReady) return;
        if (settings == null || networkError != SafeError.NONE || network == null
                || !network.getAndroidNetwork().isPresent()
                || RuntimeNetworkPolicy.isRejectedDefaultType(network.getNetworkType())) {
            completeProbe(NetworkCheckResult.UNAVAILABLE);
            return;
        }
        if (launchProbe != null) main.removeCallbacks(launchProbe);
        final long frame = generation;
        launchProbe = () -> {
            launchProbe = null;
            if (!isCurrent(frame) || probeReady || probe != null) return;
            beginProbe(frame);
        };
        main.postDelayed(launchProbe, NETWORK_SETTLE_MS);
    }

    private void beginProbe(final long frame) {
        final NetworkSnapshot target = network;
        final PublicSettings selectedSettings = settings;
        if (target == null || selectedSettings == null || !target.getAndroidNetwork().isPresent()
                || RuntimeNetworkPolicy.isRejectedDefaultType(target.getNetworkType())) {
            // 与服务器现有策略一致：默认网络是其他 VPN 时不把它的出口成功当作
            // 直连物理网络成功。仍保留 VPN 类型和地址信息，交由界面给出固定修复提示。
            completeProbe(NetworkCheckResult.UNAVAILABLE);
            return;
        }
        final Network androidNetwork = target.getAndroidNetwork().get();
        final ServerNetworkProbe selectedProbe = new ServerNetworkProbe();
        final long serial = ++probeSerial;
        probe = selectedProbe;
        try {
            probeTask = workers.submit(() -> {
                NetworkCheckResult result;
                try {
                    // 仅为既有解析策略提供最少的非秘密 DNS 设置；绝不复制实际密钥/凭据。
                    final ServerState dnsSettings = new ServerState();
                    dnsSettings.dnsEnabled = selectedSettings.dnsEnabled;
                    dnsSettings.dnsMode = selectedSettings.dnsMode;
                    dnsSettings.customDns = selectedSettings.customDns;
                    String upstreams;
                    try {
                        upstreams = DnsUpstreamResolver.resolve(dnsSettings, target);
                    } catch (final RuntimeException ignored) {
                        // 仍检测 HTTPS；站点成功时 null 仅生成独立 DNS 警告，不误报手机断网。
                        upstreams = selectedSettings.dnsEnabled ? null : "!disabled";
                    }
                    result = selectedProbe.check(androidNetwork, upstreams);
                } catch (final RuntimeException ignored) {
                    result = NetworkCheckResult.UNAVAILABLE;
                } finally {
                    selectedProbe.cancel();
                }
                final NetworkCheckResult answer = result;
                main.post(() -> {
                    if (!isCurrent(frame) || probeSerial != serial || probe != selectedProbe
                            || probeReady) return;
                    probe = null;
                    probeTask = null;
                    completeProbe(answer);
                });
            });
        } catch (final RejectedExecutionException ignored) {
            cancelProbe();
            completeProbe(NetworkCheckResult.UNAVAILABLE);
        }
    }

    private void timeout(final long frame, final Field field) {
        if (!isCurrent(frame) || ready(field)) return;
        clearDeadline(field);
        switch (field) {
            case SETTINGS:
                settingsReady = true;
                settingsError = SafeError.INFORMATION_TIMEOUT;
                cancel(settingsTask);
                settingsTask = null;
                publish();
                completeProbe(NetworkCheckResult.UNAVAILABLE);
                break;
            case NETWORK:
                networkReady = true;
                networkError = SafeError.INFORMATION_TIMEOUT;
                publish();
                completeProbe(NetworkCheckResult.UNAVAILABLE);
                break;
            case PROBE:
                cancelProbe();
                completeProbe(NetworkCheckResult.TIMEOUT);
                break;
            default: break;
        }
    }

    private boolean ready(final Field field) {
        switch (field) {
            case SETTINGS: return settingsReady;
            case NETWORK: return networkReady;
            case PROBE: return probeReady;
            default: return true;
        }
    }

    private void completeProbe(final NetworkCheckResult result) {
        if (probeReady) return;
        probeReady = true;
        networkCheck = result;
        clearDeadline(Field.PROBE);
        publish();
    }

    private void publish() {
        if (!started || closed) return;
        current = new Snapshot(this);
        if (listener != null) listener.onSnapshot(current);
    }

    private void clearDeadline(final Field field) {
        final Runnable deadline = itemDeadlines[field.ordinal()];
        if (deadline != null) main.removeCallbacks(deadline);
        itemDeadlines[field.ordinal()] = null;
    }

    private void cancelWork() {
        for (final Field field : Field.values()) clearDeadline(field);
        if (frameDeadline != null) main.removeCallbacks(frameDeadline);
        frameDeadline = null;
        cancel(settingsTask);
        cancel(availabilityTask);
        settingsTask = null;
        availabilityTask = null;
        cancelProbe();
        // Future.cancel 会留下已取消的队列项；主动清理保证两格队列不被旧帧占满。
        workers.purge();
    }

    private void cancelProbe() {
        ++probeSerial;
        if (launchProbe != null) main.removeCallbacks(launchProbe);
        launchProbe = null;
        if (probe != null) probe.cancel();
        probe = null;
        cancel(probeTask);
        probeTask = null;
    }

    private static void cancel(final Future<?> task) {
        if (task != null) task.cancel(true);
    }

    private boolean isCurrent(final long frame) {
        return started && !closed && !frameFinished && generation == frame;
    }

    private boolean isMonitoring(final long monitoring) {
        return started && !closed && monitorGeneration == monitoring;
    }

    private static boolean meaningful(final NetworkSnapshot value) {
        return value != null && value.getNetworkType() != NetworkSnapshot.NetworkType.UNKNOWN;
    }

    private static String ipv6(final NetworkSnapshot value) {
        if (value == null || !value.getEndpointCandidate().isPresent()) return "";
        final String address = value.getEndpointCandidate().get().getAddress().getHostAddress();
        final int zone = address.indexOf('%');
        return zone < 0 ? address : address.substring(0, zone);
    }

    private static String endpoint(final PublicSettings settings, final String globalIpv6) {
        if (settings == null) return "";
        try {
            final ServerState publicState = new ServerState();
            publicState.endpointHost = settings.endpointHost;
            publicState.ddnsProvider = settings.ddnsProvider;
            publicState.ddnsHostname = settings.ddnsHostname;
            return EndpointFormatter.format(
                    AppSettingsPolicy.resolveEndpointHost(publicState, globalIpv6), settings.listenPort);
        } catch (final RuntimeException ignored) {
            return "";
        }
    }

    private static String networkKey(final NetworkSnapshot value) {
        if (value == null) return "NO_NETWORK";
        final StringBuilder key = new StringBuilder()
                .append(value.getNetworkHandle()).append('|')
                .append(value.getNetworkType()).append('|').append(ipv6(value));
        for (final InetAddress dns : value.getDnsServers()) key.append('|').append(dns.getHostAddress());
        return key.toString();
    }

    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("HOME_COLLECTOR_REQUIRES_MAIN_THREAD");
        }
    }

    /** 仓库锁内复制字段后立即放锁；不携带服务器/Peer 密钥和 DDNS 身份信息。 */
    private static final class PublicSettings {
        final String endpointHost;
        final int listenPort;
        final int mtu;
        final boolean dnsEnabled;
        final String dnsMode;
        final String customDns;
        final String ddnsProvider;
        final String ddnsHostname;
        final int enabledPeers;

        PublicSettings(final ServerState state) {
            endpointHost = state.endpointHost == null ? "" : state.endpointHost;
            listenPort = state.listenPort;
            mtu = state.mtu;
            dnsEnabled = state.dnsEnabled;
            dnsMode = state.dnsMode;
            customDns = state.customDns;
            ddnsProvider = AppSettingsPolicy.normalizeProvider(state.ddnsProvider);
            ddnsHostname = state.ddnsHostname == null ? "" : state.ddnsHostname;
            int count = 0;
            for (final StoredPeer peer : state.peers()) if (peer.enabled()) count++;
            enabledPeers = count;
        }
    }
}
