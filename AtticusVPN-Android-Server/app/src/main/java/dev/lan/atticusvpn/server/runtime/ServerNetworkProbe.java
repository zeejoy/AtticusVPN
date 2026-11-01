/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.runtime;

import android.net.Network;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import dev.lan.atticusvpn.server.BuildConfig;

/**
 * 只检查服务器绑定的物理网络出口，不会标记远端 Peer 已能上网。
 * 域名解析和 HTTPS 都绑定选中的物理 Network，连接只选 IPv4，避免原生 IPv6 误判。
 * 四个独立站点同时检测，任一通过即确认出口；DNS 是单独的诊断提示，不否定 HTTPS 成功。
 * 使用进程共享的有界线程池，不为每轮新建线程；取消会关闭本轮的全部 HTTP / DNS 连接。
 * 系统域名解析在旧版 Android 上不可强制中断，因此也必须受共享线程池的数量上限约束。
 * 不保存响应、IP 信息或请求日志，不上传 WireGuard / DDNS 凭据。
 */
public final class ServerNetworkProbe {
    private static final int CHECK_TIMEOUT_MS = 3500;
    private static final int DNS_TIMEOUT_MS = 800;
    private static final int DNS_GRACE_MS = 250;
    private static final String[] PROBES = {
            "www.cloudflare.com", "www.microsoft.com", "www.baidu.com", "www.bilibili.com"
    };
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();
    // 同时容纳主页与运行服务两轮检查的 4 个 HTTPS + 2 个 DNS；排队数量也有上限。
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(
            12, 12, 15L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(24), runnable -> {
                final Thread thread = new Thread(runnable,
                        "AtticusSiteProbe-" + THREAD_SEQUENCE.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    static { WORKERS.allowCoreThreadTimeOut(true); }

    /** 可注入离线传输，测试不依赖真实 Android Network、公共网站或 DNS。 */
    interface Transport {
        boolean https(Network network, String host, Session session);
        boolean dns(Network network, String server, Session session);
    }

    private static final Transport PLATFORM = new Transport() {
        @Override public boolean https(final Network network, final String host, final Session session) {
            return ServerNetworkProbe.https(network, host, session);
        }
        @Override public boolean dns(final Network network, final String server, final Session session) {
            return ServerNetworkProbe.dns(network, server, session);
        }
    };

    private final Transport transport;
    private final ExecutorService workers;
    private final long checkTimeoutMs;
    private final long dnsGraceMs;
    private volatile boolean cancelled;
    private Session activeSession;
    private boolean started;

    public ServerNetworkProbe() { this(PLATFORM, WORKERS, CHECK_TIMEOUT_MS, DNS_GRACE_MS); }

    ServerNetworkProbe(final Transport transport, final ExecutorService workers,
                       final long checkTimeoutMs, final long dnsGraceMs) {
        if (transport == null || workers == null || checkTimeoutMs <= 0 || dnsGraceMs < 0) {
            throw new IllegalArgumentException("probe configuration");
        }
        this.transport = transport;
        this.workers = workers;
        this.checkTimeoutMs = checkTimeoutMs;
        this.dnsGraceMs = dnsGraceMs;
    }

    public NetworkCheckResult check(final Network network, final String upstreams) {
        final Session session;
        synchronized (this) {
            if (cancelled) return NetworkCheckResult.CANCELLED;
            // 一个实例属于一轮检查，防止同实例并发调用混淆取消和连接归属。
            if (started) return NetworkCheckResult.UNAVAILABLE;
            started = true;
            session = new Session(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(checkTimeoutMs));
            activeSession = session;
        }
        final boolean dnsEnabled = !"!disabled".equals(upstreams);
        final BlockingQueue<Future<Outcome>> completed = new LinkedBlockingQueue<>();
        boolean https = false;
        boolean dns = false;
        int pendingHttps = 0;
        int pendingDns = 0;
        long graceDeadline = Long.MAX_VALUE;
        try {
            // 不按站点逐一等待；前两个站点超时不能阻止百度或 Bilibili 先返回成功。
            for (final String host : PROBES) {
                if (submit(session, completed, true, () -> new Outcome(true,
                        transport.https(network, host, session)))) pendingHttps++;
            }
            if (dnsEnabled && upstreams != null) {
                final String[] servers = upstreams.split(",");
                for (int index = 0; index < Math.min(2, servers.length); index++) {
                    final String server = servers[index].trim();
                    if (!server.isEmpty() && submit(session, completed, false,
                            () -> new Outcome(false, transport.dns(network, server, session)))) pendingDns++;
                }
            }
            if (pendingHttps == 0) return cancelled ? NetworkCheckResult.CANCELLED
                    : NetworkCheckResult.UNAVAILABLE;
            while (pendingHttps + pendingDns > 0) {
                if (cancelled || Thread.currentThread().isInterrupted()) return NetworkCheckResult.CANCELLED;
                final long remaining = Math.min(session.deadlineNanos, graceDeadline) - System.nanoTime();
                if (remaining <= 0) break;
                final Future<Outcome> future = completed.poll(remaining, TimeUnit.NANOSECONDS);
                if (future == null) break;
                final Outcome outcome;
                try { outcome = future.get(); }
                catch (final CancellationException | ExecutionException ignored) { continue; }
                if (outcome.https) {
                    pendingHttps--;
                    if (outcome.passed && !https) {
                        https = true;
                        // 已确认网站可用后最多等很短的 DNS 宽限；不能被 DNS 反过来拖成超时。
                        graceDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(dnsGraceMs);
                    }
                } else {
                    pendingDns--;
                    dns |= outcome.passed;
                }
                if (https && (!dnsEnabled || dns || pendingDns == 0)) break;
                if (!https && pendingHttps == 0) break;
            }
            return cancelled ? NetworkCheckResult.CANCELLED
                    : NetworkCheckResult.classify(https, dns, dnsEnabled);
        } catch (final InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return NetworkCheckResult.CANCELLED;
        } finally {
            session.close();
            synchronized (this) { if (activeSession == session) activeSession = null; }
            if (workers instanceof ThreadPoolExecutor) ((ThreadPoolExecutor) workers).purge();
        }
    }

    private boolean submit(final Session session, final BlockingQueue<Future<Outcome>> completed,
                           final boolean https,
                           final java.util.concurrent.Callable<Outcome> operation) {
        final FutureTask<Outcome> future = new FutureTask<Outcome>(() -> {
            try { return operation.call(); }
            catch (final InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return new Outcome(https, false);
            } catch (final Exception ignored) {
                // 一个站点异常也必须产出失败结果并扣减计数，不能平白等待整轮期限。
                return new Outcome(https, false);
            }
        }) {
            @Override protected void done() { completed.offer(this); }
        };
        session.track(future);
        if (session.isCancelled()) return false;
        try { workers.execute(future); return true; }
        catch (final RejectedExecutionException ignored) { future.cancel(true); return false; }
    }

    static String[] probeHosts() { return PROBES.clone(); }

    static byte[] httpRequest(final String host) {
        // 固定 GET 避免网站仅拒绝 HEAD 的误报；仅读状态行，不存储正文或模拟浏览器身份。
        return ("GET / HTTP/1.1\r\nHost: " + host
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
    }

    /** 平台 CA、HTTPS 主机名和标准 SNI；不使用宽松信任管理器，不跟随重定向。 */
    private static boolean https(final Network network, final String host, final Session session) {
        String stage = "DNS";
        try {
            final InetAddress address = firstIpv4(network.getAllByName(host));
            if (address == null || session.isCancelled()) return false;
            stage = "TCP";
            try (Socket raw = network.getSocketFactory().createSocket()) {
                session.track(raw);
                raw.connect(new InetSocketAddress(address, 443), session.timeout(CHECK_TIMEOUT_MS));
                raw.setSoTimeout(session.timeout(CHECK_TIMEOUT_MS));
                stage = "TLS";
                final SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                try (SSLSocket tls = (SSLSocket) factory.createSocket(raw, host, 443, true)) {
                    session.track(tls);
                    tls.setSoTimeout(session.timeout(CHECK_TIMEOUT_MS));
                    final SSLParameters parameters = tls.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    parameters.setServerNames(Collections.singletonList(new SNIHostName(host)));
                    tls.setSSLParameters(parameters);
                    tls.startHandshake();
                    if (session.isCancelled()) return false;
                    stage = "HTTP";
                    tls.setSoTimeout(session.timeout(CHECK_TIMEOUT_MS));
                    tls.getOutputStream().write(httpRequest(host));
                    tls.getOutputStream().flush();
                    return successfulStatus(readStatusLine(tls.getInputStream()));
                }
            }
        } catch (final IOException | RuntimeException error) {
            // 仅调试版记录阶段和异常类型，不记录消息、堆栈、地址或请求正文。
            if (BuildConfig.DEBUG && !session.isCancelled()) {
                Log.d("AtticusNetCheck", "HTTPS stage=" + stage
                        + " error=" + error.getClass().getSimpleName());
            }
            return false;
        }
    }

    /** 测试只确认 IPv4 出口，不回退 IPv6，也不在未指定 Network 上重新解析。 */
    static InetAddress firstIpv4(final InetAddress[] addresses) {
        for (final InetAddress address : addresses) {
            if (address instanceof Inet4Address) return address;
        }
        return null;
    }

    static boolean successfulStatus(final String status) {
        return status != null && status.matches("HTTP/1\\.[01] [23][0-9]{2}( .*)?");
    }

    /** 有界读取首行，拒绝超长、非 ASCII、未结束或格式错误的结果。 */
    static String readStatusLine(final InputStream input) throws IOException {
        final StringBuilder line = new StringBuilder();
        for (int i = 0; i < 512; i++) {
            final int value = input.read();
            if (value < 0 || value > 127) return null;
            if (value == '\n') return line.toString().replace("\r", "");
            line.append((char) value);
        }
        return null;
    }

    /** 数字上游不依赖系统 DNS。connected UDP 会过滤其他来源，响应还必须匹配事务和问题。 */
    private static boolean dns(final Network network, final String server, final Session session) {
        try (DatagramSocket socket = new DatagramSocket(null)) {
            session.track(socket);
            network.bindSocket(socket);
            socket.setSoTimeout(session.timeout(DNS_TIMEOUT_MS));
            socket.connect(new InetSocketAddress(InetAddress.getByName(server), 53));
            final byte[] query = query(new SecureRandom().nextInt(65536));
            socket.send(new DatagramPacket(query, query.length));
            final byte[] buffer = new byte[4096];
            final DatagramPacket answer = new DatagramPacket(buffer, buffer.length);
            socket.receive(answer);
            return acceptsDnsAnswer(query, Arrays.copyOf(buffer, answer.getLength()));
        } catch (final IOException | RuntimeException ignored) {
            return false;
        }
    }

    /** 固定公开域名的 A 查询；只用于检测，不读取用户访问记录。 */
    static byte[] query(final int id) {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(id >>> 8); output.write(id & 255);
        output.write(1); output.write(0); // RD
        output.write(0); output.write(1); // QDCOUNT
        for (int i = 0; i < 6; i++) output.write(0);
        for (final String label : new String[]{"one", "one", "one", "one"}) {
            final byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
            output.write(bytes.length); output.write(bytes, 0, bytes.length);
        }
        output.write(0); output.write(0); output.write(1); output.write(0); output.write(1);
        return output.toByteArray();
    }

    static boolean acceptsDnsAnswer(final byte[] query, final byte[] response) {
        if (response.length < query.length || response[0] != query[0] || response[1] != query[1]
                || (response[2] & 0x80) == 0 || (response[2] & 0x7a) != 0
                || (response[3] & 0x0f) != 0 || response[4] != 0 || response[5] != 1
                || (response[6] == 0 && response[7] == 0)) return false;
        for (int i = 12; i < query.length; i++) if (query[i] != response[i]) return false;
        // 要求响应中确实存在完整 A/IN 记录；拒绝伪造 ANCOUNT 的空响应。
        int offset = query.length;
        final int count = ((response[6] & 255) << 8) | (response[7] & 255);
        for (int record = 0; record < count && record < 128; record++) {
            final DnsName owner = readName(response, offset);
            if (owner == null) return false;
            offset = owner.next;
            if (offset + 10 > response.length) return false;
            final int type = unsignedShort(response, offset);
            final int klass = unsignedShort(response, offset + 2);
            final int size = unsignedShort(response, offset + 8);
            offset += 10;
            if (offset + size > response.length) return false;
            if (type == 1 && klass == 1 && size == 4 && "one.one.one.one".equals(owner.text)) return true;
            offset += size;
        }
        return false;
    }

    /** 有界解析压缩名称，拒绝越界、自指针和循环，避免把畸形/不相关应答当作 DNS 成功。 */
    private static DnsName readName(final byte[] data, int offset) {
        final boolean[] visited = new boolean[data.length];
        final StringBuilder name = new StringBuilder();
        int next = -1;
        for (int steps = 0; steps < 128 && offset < data.length; steps++) {
            if (visited[offset]) return null;
            visited[offset] = true;
            final int length = data[offset++] & 255;
            if (length == 0) return new DnsName(name.toString().toLowerCase(Locale.ROOT),
                    next < 0 ? offset : next);
            if ((length & 0xc0) == 0xc0) {
                if (offset >= data.length) return null;
                final int target = ((length & 0x3f) << 8) | (data[offset++] & 255);
                if (target < 12 || target >= data.length) return null;
                if (next < 0) next = offset;
                offset = target;
            } else {
                if (length > 63 || offset + length > data.length) return null;
                if (name.length() > 0) name.append('.');
                name.append(new String(data, offset, length, StandardCharsets.US_ASCII));
                if (name.length() > 253) return null;
                offset += length;
            }
        }
        return null;
    }

    private static final class DnsName {
        final String text;
        final int next;
        DnsName(final String text, final int next) { this.text = text; this.next = next; }
    }

    private static int unsignedShort(final byte[] data, final int offset) {
        return ((data[offset] & 255) << 8) | (data[offset + 1] & 255);
    }

    private static final class Outcome {
        final boolean https;
        final boolean passed;
        Outcome(final boolean https, final boolean passed) { this.https = https; this.passed = passed; }
    }

    /** 一轮检查的所有连接归属同一个会话；迟到创建的连接也必须立即关闭。 */
    static final class Session implements AutoCloseable {
        final long deadlineNanos;
        private final Set<Socket> http = new HashSet<>();
        private final Set<DatagramSocket> dns = new HashSet<>();
        private final List<Future<?>> futures = new ArrayList<>();
        private volatile boolean closed;

        Session(final long deadlineNanos) { this.deadlineNanos = deadlineNanos; }

        boolean isCancelled() { return closed || Thread.currentThread().isInterrupted(); }

        int timeout(final int maximumMs) throws IOException {
            final long remainingNanos = deadlineNanos - System.nanoTime();
            if (isCancelled() || remainingNanos <= 0) throw new IOException("probe deadline");
            final long millis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
            return (int) Math.max(1L, Math.min(maximumMs, millis));
        }

        synchronized void track(final Future<?> future) {
            if (closed) future.cancel(true);
            else futures.add(future);
        }

        synchronized void track(final Socket socket) throws IOException {
            if (closed) {
                socket.close();
                throw new IOException("cancelled");
            }
            http.add(socket);
        }

        synchronized void track(final DatagramSocket socket) throws IOException {
            if (closed) {
                socket.close();
                throw new IOException("cancelled");
            }
            dns.add(socket);
        }

        @Override public void close() {
            final List<Future<?>> jobs;
            final Set<Socket> httpSockets;
            final Set<DatagramSocket> dnsSockets;
            synchronized (this) {
                if (closed) return;
                closed = true;
                jobs = new ArrayList<>(futures);
                httpSockets = new HashSet<>(http);
                dnsSockets = new HashSet<>(dns);
                futures.clear(); http.clear(); dns.clear();
            }
            for (final Future<?> job : jobs) job.cancel(true);
            for (final DatagramSocket socket : dnsSockets) socket.close();
            for (final Socket socket : httpSockets) {
                try { socket.close(); } catch (final IOException ignored) { }
            }
        }
    }

    public void cancel() {
        final Session session;
        synchronized (this) { cancelled = true; session = activeSession; }
        if (session != null) session.close();
    }
}
