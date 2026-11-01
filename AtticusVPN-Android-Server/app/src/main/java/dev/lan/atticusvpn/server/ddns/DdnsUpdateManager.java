/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.ddns;

import dev.lan.atticusvpn.server.network.AndroidNetworkMonitor;
import dev.lan.atticusvpn.server.network.GlobalIpv6Selector;
import dev.lan.atticusvpn.server.network.NetworkSnapshot;

import java.net.Inet6Address;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把 Android 默认网络事件转换成去抖的 DDNS 更新，并执行低频一致性更新。
 *
 * <p>地址变化会在短暂去抖后触发；连续事件之间至少间隔一分钟，避免网络切换时反复请求。
 * 此外默认每六小时重新提交当前地址，以修复服务端记录被外部修改或过期的情况。所有 HTTP
 * 工作都在单线程后台执行，网络回调线程不会被阻塞。</p>
 *
 * <p>本类只接受 {@link GlobalIpv6Selector} 已选出的健康 GUA。日志只包含固定 Provider ID、
 * 触发类型和结果枚举，不记录请求 URL、认证头、正文、token 或异常原文。</p>
 */
public final class DdnsUpdateManager implements AndroidNetworkMonitor.Listener, AutoCloseable {
    /** 默认地址事件去抖时间：5 秒。 */
    public static final long DEFAULT_EVENT_DEBOUNCE_MILLIS = 5_000L;
    /** 默认两次事件更新的最小间隔：1 分钟。 */
    public static final long DEFAULT_MIN_EVENT_INTERVAL_MILLIS = 60_000L;
    /** 默认低频一致性间隔：6 小时。 */
    public static final long DEFAULT_CONSISTENCY_INTERVAL_MILLIS = 6L * 60L * 60L * 1000L;
    /** 允许的最小一致性间隔：15 分钟。 */
    public static final long MIN_CONSISTENCY_INTERVAL_MILLIS = 15L * 60L * 1000L;
    /** DNS 传播确认首次重试等待：1 分钟。 */
    public static final long DEFAULT_VERIFICATION_RETRY_MILLIS = 60_000L;
    /** 最多执行三次后台传播确认，之后保留待确认状态等待下一致性周期。 */
    public static final int MAX_VERIFICATION_ATTEMPTS = 3;
    /** Provider 暂时失败后的有限重试：1、2、4 分钟。 */
    private static final long[] DEFAULT_PROVIDER_RETRY_DELAYS_MILLIS = {
            60_000L, 120_000L, 240_000L
    };
    /** No-IP 的 911 规则要求至少等待 30 分钟；其他暂时错误也采用同一保守下限。 */
    private static final long[] NOIP_PROVIDER_RETRY_DELAYS_MILLIS = {
            30L * 60L * 1000L, 60L * 60L * 1000L, 120L * 60L * 1000L
    };

    /** 接收后台 DDNS 结果；回调不在主线程执行。 */
    public interface Listener {
        /**
         * Provider 更新或 AAAA 解析确认结束时触发。
         *
         * @param result 已脱敏结果
         */
        void onDdnsResult(DdnsUpdateResult result);
    }

    /** 为调度测试隔离单调时钟。 */
    interface MonotonicClock {
        /** 返回单调递增的毫秒时间，不表示墙上时钟。 */
        long nowMillis();
    }

    private final AndroidNetworkMonitor networkMonitor;
    private final DdnsProvider provider;
    private final String providerId;
    private final String hostname;
    private final SafeDdnsLogger logger;
    private final DdnsAaaaVerifier verifier;
    private final ScheduledExecutorService executor;
    private final MonotonicClock clock;
    private final long eventDebounceMillis;
    private final long minEventIntervalMillis;
    private final long consistencyIntervalMillis;
    private final Object stateLock = new Object();
    private final AtomicBoolean updateInProgress = new AtomicBoolean(false);

    private Listener listener;
    private boolean started;
    private boolean closed;
    private Inet6Address latestAddress;
    private String latestAddressText;
    private long latestNetworkHandle = Long.MIN_VALUE;
    private long lastEventAttemptMillis = Long.MIN_VALUE;
    private ScheduledFuture<?> pendingEvent;
    private ScheduledFuture<?> pendingVerification;
    private ScheduledFuture<?> pendingProviderRetry;
    private ScheduledFuture<?> consistencyTask;
    private long[] providerRetryDelaysMillis = DEFAULT_PROVIDER_RETRY_DELAYS_MILLIS.clone();

    /**
     * 使用安全默认频率创建管理器。
     *
     * @param networkMonitor Android 默认网络观察器
     * @param provider 已配置的 DDNS Provider
     * @param hostname 要发布的完整域名
     * @param logger 安全日志器；可传 {@link SafeDdnsLogger#noOp()}
     */
    public DdnsUpdateManager(
            AndroidNetworkMonitor networkMonitor,
            DdnsProvider provider,
            String hostname,
            SafeDdnsLogger logger) {
        this(Objects.requireNonNull(networkMonitor, "networkMonitor"), provider, hostname, logger,
                new DdnsAaaaVerifier(new SystemAaaaRecordResolver()),
                createExecutor(), new SystemMonotonicClock(),
                DEFAULT_EVENT_DEBOUNCE_MILLIS,
                DEFAULT_MIN_EVENT_INTERVAL_MILLIS,
                DEFAULT_CONSISTENCY_INTERVAL_MILLIS);
    }

    /**
     * 使用自定义但有安全下限的频率创建管理器。
     *
     * @param networkMonitor Android 默认网络观察器
     * @param provider 已配置的 DDNS Provider
     * @param hostname 要发布的完整域名
     * @param logger 安全日志器
     * @param eventDebounceMillis 地址事件去抖毫秒数，范围 0..60 秒
     * @param minEventIntervalMillis 事件更新最小间隔，至少 10 秒
     * @param consistencyIntervalMillis 一致性更新间隔，至少 15 分钟
     */
    public DdnsUpdateManager(
            AndroidNetworkMonitor networkMonitor,
            DdnsProvider provider,
            String hostname,
            SafeDdnsLogger logger,
            long eventDebounceMillis,
            long minEventIntervalMillis,
            long consistencyIntervalMillis) {
        this(Objects.requireNonNull(networkMonitor, "networkMonitor"), provider, hostname, logger,
                new DdnsAaaaVerifier(new SystemAaaaRecordResolver()),
                createExecutor(), new SystemMonotonicClock(),
                eventDebounceMillis, minEventIntervalMillis, consistencyIntervalMillis);
    }

    /** 注入调度器和时钟的包内构造器，仅供确定性测试使用。 */
    DdnsUpdateManager(
            AndroidNetworkMonitor networkMonitor,
            DdnsProvider provider,
            String hostname,
            SafeDdnsLogger logger,
            DdnsAaaaVerifier verifier,
            ScheduledExecutorService executor,
            MonotonicClock clock,
            long eventDebounceMillis,
            long minEventIntervalMillis,
            long consistencyIntervalMillis) {
        this.networkMonitor = networkMonitor;
        this.provider = Objects.requireNonNull(provider, "provider");
        this.providerId = DdnsProviderSupport.requireProviderId(provider.getId());
        this.hostname = DdnsUpdateRequest.normalizeHostname(hostname);
        this.logger = Objects.requireNonNull(logger, "logger");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (eventDebounceMillis < 0 || eventDebounceMillis > 60_000L) {
            throw new IllegalArgumentException("地址事件去抖时间必须位于 0..60000 毫秒。");
        }
        if (minEventIntervalMillis < 10_000L) {
            throw new IllegalArgumentException("事件更新最小间隔不得低于 10 秒。");
        }
        if (consistencyIntervalMillis < MIN_CONSISTENCY_INTERVAL_MILLIS) {
            throw new IllegalArgumentException("一致性更新间隔不得低于 15 分钟。");
        }
        this.eventDebounceMillis = eventDebounceMillis;
        this.minEventIntervalMillis = minEventIntervalMillis;
        this.consistencyIntervalMillis = consistencyIntervalMillis;
    }

    /** 仅供包内确定性测试缩短重试时间；启动后不可更改。 */
    void setProviderRetryDelaysForTest(final long... delaysMillis) {
        Objects.requireNonNull(delaysMillis, "delaysMillis");
        if (delaysMillis.length == 0 || delaysMillis.length > 8) {
            throw new IllegalArgumentException("测试重试次数无效。");
        }
        for (final long delay : delaysMillis) {
            if (delay < 1L) {
                throw new IllegalArgumentException("测试重试间隔必须为正数。");
            }
        }
        synchronized (stateLock) {
            if (started || closed) {
                throw new IllegalStateException("只能在启动前设置测试重试间隔。");
            }
            providerRetryDelaysMillis = delaysMillis.clone();
        }
    }

    /**
     * 启动网络观察和一致性调度。本实例只能启动一次。
     *
     * @param listener 可为 {@code null}；用于接收脱敏结果
     */
    public void start(Listener listener) {
        synchronized (stateLock) {
            if (closed) {
                throw new IllegalStateException("DdnsUpdateManager 已关闭。");
            }
            if (started) {
                throw new IllegalStateException("DdnsUpdateManager 已启动。");
            }
            started = true;
            this.listener = listener;
            consistencyTask = executor.scheduleWithFixedDelay(
                    new Runnable() {
                        /** 执行一次低频一致性更新。 */
                        @Override
                        public void run() {
                            runConsistencyUpdate();
                        }
                    },
                    consistencyIntervalMillis,
                    consistencyIntervalMillis,
                    TimeUnit.MILLISECONDS);
        }

        try {
            if (networkMonitor != null) {
                networkMonitor.start(this);
            }
            logger.info("DDNS 管理器已启动 provider=" + providerId);
        } catch (RuntimeException error) {
            close();
            throw error;
        }
    }

    /**
     * 接收默认网络快照；仅在网络已验证且存在健康 GUA 时安排事件更新。
     *
     * @param snapshot 网络观察器输出的不可变快照
     */
    @Override
    public void onNetworkSnapshot(NetworkSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        synchronized (stateLock) {
            if (!started || closed) {
                return;
            }
            latestNetworkHandle = snapshot.getNetworkHandle();
        }
        if (snapshot.getNetworkType() == NetworkSnapshot.NetworkType.VPN) {
            // 与 Server runtime 使用同一安全边界：不能通过另一个默认 VPN 发布地址或凭据。
            clearLatestAddress();
            logger.info("默认网络是 VPN；DDNS 等待物理网络。");
            return;
        }
        if (!snapshot.isValidated() || !snapshot.getEndpointCandidate().isPresent()) {
            clearLatestAddress();
            logger.info("DDNS 等待已验证网络和公网 IPv6。");
            return;
        }
        Inet6Address selected = snapshot.getEndpointCandidate().get().getAddress();
        observeEndpointForNetwork(snapshot.getNetworkHandle(), selected);
    }

    /** 默认网络丢失时清除待发布地址并取消尚未开始的事件任务。 */
    @Override
    public void onNetworkLost(long networkHandle) {
        synchronized (stateLock) {
            if (networkHandle != latestNetworkHandle) {
                // 新网络快照已经到达时，忽略迟到的旧网络丢失事件。
                return;
            }
            latestNetworkHandle = Long.MIN_VALUE;
            clearLatestAddressLocked();
        }
        logger.info("默认网络已丢失；DDNS 等待新网络。");
    }

    /**
     * 立即安排一次一致性检查；实际 HTTP 仍在后台串行执行。
     *
     * <p>如果当前没有健康 GUA，将向监听器报告 SKIPPED。</p>
     */
    public void requestConsistencyCheck() {
        synchronized (stateLock) {
            ensureRunning();
            executor.execute(new Runnable() {
                /** 执行手动一致性检查。 */
                @Override
                public void run() {
                    runConsistencyUpdate();
                }
            });
        }
    }

    /**
     * 停止回调、取消调度并终止后台线程；可重复调用，停止后不能重新启动。
     */
    public void stop() {
        close();
    }

    /** 释放所有回调和调度资源；可重复调用。 */
    @Override
    public void close() {
        boolean stopMonitor;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
            stopMonitor = started;
            started = false;
            listener = null;
            latestAddress = null;
            latestAddressText = null;
            latestNetworkHandle = Long.MIN_VALUE;
            if (pendingEvent != null) {
                pendingEvent.cancel(false);
                pendingEvent = null;
            }
            if (pendingVerification != null) {
                pendingVerification.cancel(false);
                pendingVerification = null;
            }
            if (pendingProviderRetry != null) {
                pendingProviderRetry.cancel(false);
                pendingProviderRetry = null;
            }
            if (consistencyTask != null) {
                consistencyTask.cancel(false);
                consistencyTask = null;
            }
        }
        if (stopMonitor && networkMonitor != null) {
            networkMonitor.stop();
        }
        executor.shutdownNow();
        logger.info("DDNS 管理器已停止 provider=" + providerId);
    }

    /**
     * 接收选中的 Endpoint，并在地址真正变化时重新安排去抖任务。
     * 包内可见以便用纯 Java 地址对象测试调度策略。
     */
    void observeEndpoint(Inet6Address address) {
        observeEndpointInternal(Long.MIN_VALUE, address, false);
    }

    /** 只在该网络仍是最新快照时接受地址，防止迟到回调恢复旧 GUA。 */
    private void observeEndpointForNetwork(long networkHandle, Inet6Address address) {
        observeEndpointInternal(networkHandle, address, true);
    }

    /** 实现测试入口与带网络代次入口共用的地址更新和调度。 */
    private void observeEndpointInternal(
            long networkHandle,
            Inet6Address address,
            boolean requireCurrentNetwork) {
        Objects.requireNonNull(address, "address");
        if (GlobalIpv6Selector.classify(address)
                != GlobalIpv6Selector.AddressScope.GLOBAL_UNICAST) {
            throw new IllegalArgumentException("DDNS Endpoint 必须是公网 IPv6 GUA。");
        }
        String addressText = addressText(address);
        synchronized (stateLock) {
            if (!started || closed) {
                return;
            }
            if (requireCurrentNetwork && latestNetworkHandle != networkHandle) {
                return;
            }
            if (addressText.equals(latestAddressText)) {
                return;
            }
            latestAddress = address;
            latestAddressText = addressText;
            if (pendingEvent != null) {
                pendingEvent.cancel(false);
            }
            if (pendingVerification != null) {
                pendingVerification.cancel(false);
                pendingVerification = null;
            }
            if (pendingProviderRetry != null) {
                pendingProviderRetry.cancel(false);
                pendingProviderRetry = null;
            }
            long now = clock.nowMillis();
            long spacingDelay = lastEventAttemptMillis == Long.MIN_VALUE
                    ? 0L
                    : Math.max(0L, minEventIntervalMillis - Math.max(0L, now - lastEventAttemptMillis));
            long delay = Math.max(eventDebounceMillis, spacingDelay);
            pendingEvent = executor.schedule(new Runnable() {
                /** 在去抖和限频后提交最新地址。 */
                @Override
                public void run() {
                    runEventUpdate();
                }
            }, delay, TimeUnit.MILLISECONDS);
        }
        logger.info("检测到新的公网 IPv6；已安排 DDNS 更新。");
    }

    /** 提取最新版地址并执行事件更新。 */
    private void runEventUpdate() {
        Inet6Address address;
        synchronized (stateLock) {
            pendingEvent = null;
            if (!started || closed || latestAddress == null) {
                return;
            }
            address = latestAddress;
            lastEventAttemptMillis = clock.nowMillis();
        }
        runProvider(address, DdnsUpdateRequest.Trigger.EVENT);
    }

    /** 对当前地址执行低频一致性更新；没有地址时报告 SKIPPED。 */
    private void runConsistencyUpdate() {
        Inet6Address address;
        synchronized (stateLock) {
            if (!started || closed) {
                return;
            }
            address = latestAddress;
        }
        if (address == null) {
            publishResult(DdnsUpdateResult.local(
                    providerId,
                    DdnsUpdateResult.Status.SKIPPED,
                    "当前没有可发布的公网 IPv6；已跳过 DDNS 更新。"));
            return;
        }
        runProvider(address, DdnsUpdateRequest.Trigger.CONSISTENCY);
    }

    /** 串行调用 Provider，随后通过独立 AAAA 解析确认公开结果。 */
    private void runProvider(Inet6Address address, DdnsUpdateRequest.Trigger trigger) {
        runProvider(address, trigger, 0);
    }

    /** 执行初次提交或指定次数的 Provider 重试。 */
    private void runProvider(
            Inet6Address address,
            DdnsUpdateRequest.Trigger trigger,
            int completedRetryAttempts) {
        if (!updateInProgress.compareAndSet(false, true)) {
            logger.info("已有 DDNS 更新进行中；本次触发已合并。");
            return;
        }
        DdnsUpdateRequest request = null;
        DdnsUpdateResult providerResult = null;
        DdnsUpdateResult result;
        try {
            request = new DdnsUpdateRequest(hostname, address, trigger);
            providerResult = provider.update(request);
            if (providerResult == null) {
                providerResult = DdnsUpdateResult.local(
                        providerId,
                        DdnsUpdateResult.Status.RETRYABLE_ERROR,
                        "DDNS Provider 未返回结果；可稍后重试。");
            }
            result = verifier.verify(request, providerResult);
        } catch (RuntimeException error) {
            // 不拼接 error.getMessage()，因为第三方实现的异常可能包含 URL 或凭据。
            providerResult = DdnsUpdateResult.local(
                    providerId,
                    DdnsUpdateResult.Status.RETRYABLE_ERROR,
                    "DDNS Provider 执行失败；详细异常已为保护凭据而隐藏。");
            result = providerResult;
        } finally {
            updateInProgress.set(false);
        }
        if (!isCurrentAddress(address)) {
            logger.info("网络地址已变化；已丢弃过期的 DDNS 结果。");
            return;
        }
        publishResult(result);
        if (providerResult != null
                && providerResult.getStatus() == DdnsUpdateResult.Status.RETRYABLE_ERROR) {
            scheduleProviderRetry(address, trigger, completedRetryAttempts + 1);
            return;
        }
        cancelProviderRetryForCurrentAddress(address);
        if (request != null && providerResult != null
                && wasProviderAccepted(providerResult)
                && shouldRetryVerification(result)) {
            scheduleVerificationRetry(request, providerResult, 1);
        }
    }

    /**
     * 为同一个最新地址安排有限 Provider 重试。地址变化、成功、永久错误或 close 都会取消。
     */
    private void scheduleProviderRetry(
            final Inet6Address address,
            final DdnsUpdateRequest.Trigger trigger,
            final int attempt) {
        final long[] delays = "noip".equals(providerId)
                ? NOIP_PROVIDER_RETRY_DELAYS_MILLIS
                : providerRetryDelaysMillis;
        if (attempt < 1 || attempt > delays.length) {
            logger.warn("DDNS Provider 已达到有限重试上限；等待下一次一致性检查。");
            return;
        }
        synchronized (stateLock) {
            if (!started || closed || !isCurrentAddressLocked(address)) {
                return;
            }
            if (pendingProviderRetry != null) {
                pendingProviderRetry.cancel(false);
            }
            pendingProviderRetry = executor.schedule(new Runnable() {
                @Override
                public void run() {
                    synchronized (stateLock) {
                        pendingProviderRetry = null;
                        if (!started || closed || !isCurrentAddressLocked(address)) {
                            return;
                        }
                    }
                    runProvider(address, trigger, attempt);
                }
            }, delays[attempt - 1], TimeUnit.MILLISECONDS);
        }
        logger.info("DDNS Provider 暂时失败；已安排有限退避重试。");
    }

    /** 成功/永久失败会撤销同地址仍排队的旧重试。 */
    private void cancelProviderRetryForCurrentAddress(final Inet6Address address) {
        synchronized (stateLock) {
            if (isCurrentAddressLocked(address) && pendingProviderRetry != null) {
                pendingProviderRetry.cancel(false);
                pendingProviderRetry = null;
            }
        }
    }

    /**
     * 在 DNS 传播窗口内安排有限次数的独立查询，不重复提交 Provider 更新。
     */
    private void scheduleVerificationRetry(
            final DdnsUpdateRequest request,
            final DdnsUpdateResult providerResult,
            final int attempt) {
        synchronized (stateLock) {
            if (!started || closed || !isCurrentAddressLocked(request.getAddress())) {
                return;
            }
            if (pendingVerification != null) {
                pendingVerification.cancel(false);
            }
            long delay = DEFAULT_VERIFICATION_RETRY_MILLIS * attempt;
            pendingVerification = executor.schedule(new Runnable() {
                /** 再次解析 AAAA，仍未传播时按递增等待继续确认。 */
                @Override
                public void run() {
                    runVerificationRetry(request, providerResult, attempt);
                }
            }, delay, TimeUnit.MILLISECONDS);
        }
        logger.info("AAAA 尚未确认；已安排 DNS 传播复查。");
    }

    /** 执行一次传播复查，并在达到上限前继续有限重试。 */
    private void runVerificationRetry(
            DdnsUpdateRequest request,
            DdnsUpdateResult providerResult,
            int attempt) {
        synchronized (stateLock) {
            pendingVerification = null;
            if (!started || closed || !isCurrentAddressLocked(request.getAddress())) {
                return;
            }
        }
        DdnsUpdateResult result = verifier.verify(request, providerResult);
        if (!isCurrentAddress(request.getAddress())) {
            return;
        }
        publishResult(result);
        if (shouldRetryVerification(result) && attempt < MAX_VERIFICATION_ATTEMPTS) {
            scheduleVerificationRetry(request, providerResult, attempt + 1);
        }
    }

    /** 判断验证结果是否仍可能因 DNS 传播或暂时查询错误而在稍后成功。 */
    private static boolean shouldRetryVerification(DdnsUpdateResult result) {
        return result.getStatus() == DdnsUpdateResult.Status.PENDING_VERIFICATION
                || result.getStatus() == DdnsUpdateResult.Status.RETRYABLE_ERROR;
    }

    /** 只有 Provider 明确接受写入或报告无需变更时才进行 DNS 传播复查。 */
    private static boolean wasProviderAccepted(DdnsUpdateResult result) {
        return result.getStatus() == DdnsUpdateResult.Status.UPDATED
                || result.getStatus() == DdnsUpdateResult.Status.UNCHANGED;
    }

    /** 向安全日志和可选监听器发布结果，不输出 Provider 消息中的任意远端正文。 */
    private void publishResult(DdnsUpdateResult result) {
        logger.info("DDNS 更新结果 provider=" + providerId
                + " status=" + result.getStatus()
                + " http=" + result.getHttpStatus());
        Listener target;
        synchronized (stateLock) {
            if (!started || closed) {
                return;
            }
            target = listener;
        }
        if (target != null) {
            try {
                target.onDdnsResult(result);
            } catch (RuntimeException error) {
                // 监听器属于 UI/调用方边界；隐藏异常正文并保护后续一致性调度。
                logger.warn("DDNS 结果监听器执行失败；异常详情已隐藏。");
            }
        }
    }

    /** 清除地址并取消未执行的事件更新。 */
    private void clearLatestAddress() {
        synchronized (stateLock) {
            clearLatestAddressLocked();
        }
    }

    /** 持有 stateLock 时清除地址并取消与旧地址绑定的任务。 */
    private void clearLatestAddressLocked() {
        latestAddress = null;
        latestAddressText = null;
        if (pendingEvent != null) {
            pendingEvent.cancel(false);
            pendingEvent = null;
        }
        if (pendingVerification != null) {
            pendingVerification.cancel(false);
            pendingVerification = null;
        }
        if (pendingProviderRetry != null) {
            pendingProviderRetry.cancel(false);
            pendingProviderRetry = null;
        }
    }

    /** 线程安全地判断结果对应的地址是否仍是当前 Endpoint。 */
    private boolean isCurrentAddress(Inet6Address address) {
        synchronized (stateLock) {
            return started && !closed && isCurrentAddressLocked(address);
        }
    }

    /** 在持有 stateLock 时按规范文本比较当前地址。 */
    private boolean isCurrentAddressLocked(Inet6Address address) {
        return latestAddressText != null && latestAddressText.equals(addressText(address));
    }

    /** 在需要已启动状态的公开操作前给出明确错误。 */
    private void ensureRunning() {
        if (!started || closed) {
            throw new IllegalStateException("DdnsUpdateManager 尚未启动或已经关闭。");
        }
    }

    /** 返回不含 zone-id 的 IPv6 文本，用于确定地址是否变化。 */
    private static String addressText(Inet6Address address) {
        String value = address.getHostAddress();
        int zone = value.indexOf('%');
        return zone < 0 ? value : value.substring(0, zone);
    }

    /** 创建单线程、低优先级意义上的命名后台执行器。 */
    private static ScheduledExecutorService createExecutor() {
        return Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            /** 创建不阻止进程退出的 DDNS 后台线程。 */
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "AtticusVPN-DDNS");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    /** 使用 System.nanoTime 提供不受系统时间调整影响的单调毫秒时间。 */
    private static final class SystemMonotonicClock implements MonotonicClock {
        /** 返回单调毫秒时间。 */
        @Override
        public long nowMillis() {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        }
    }
}
