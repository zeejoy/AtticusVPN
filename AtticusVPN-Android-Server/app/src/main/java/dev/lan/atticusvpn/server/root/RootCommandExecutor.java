/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.root;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 唯一的 su 进程入口：调用方只能选择固定的只读命令，不能传入 Shell 字符串。
 * 不读取或返回其他 VPN 的 XFRM state 内容，其中可能含对称密钥。
 * Root 申请只允许由明确用户操作触发；本模块绝不安装、下载或取得 Root。
 */
public final class RootCommandExecutor implements RootManager.CommandRunner {
    private static final String ROOT_MARKER = "ATTICUS_UID_ZERO_VERIFIED\n";
    private static final String ROOT_GUARD = "if [ \"$(/system/bin/id -u)\" != 0 ]; then exit 77; fi; "
            + "printf 'ATTICUS_UID_ZERO_VERIFIED\\n'; ";
    public enum Command {
        UID("/system/bin/id -u", 30_000),
        XFRM_STAT("/system/bin/grep -q '^XfrmInError[[:space:]]' /proc/net/xfrm_stat", 4_000),
        XFRM_STATE("/system/bin/ip xfrm state list >/dev/null", 4_000),
        XFRM_POLICY("/system/bin/ip xfrm policy list >/dev/null", 4_000),
        SELINUX("/system/bin/getenforce", 4_000),
        IPV4_FORWARDING("/system/bin/cat /proc/sys/net/ipv4/ip_forward", 4_000),
        IPV6_FORWARDING("/system/bin/cat /proc/sys/net/ipv6/conf/all/forwarding", 4_000),
        UDP_PORTS("/system/bin/cat /proc/net/udp /proc/net/udp6", 4_000);

        private final String script;
        private final int timeoutMs;
        Command(String script, int timeoutMs) { this.script = script; this.timeoutMs = timeoutMs; }
    }

    public enum Status { OK, NOT_FOUND, DENIED, FAILED, TIMEOUT, INTERRUPTED, OUTPUT_INVALID, IDENTITY_UNVERIFIED }

    /** Result 只提供白名单标量与固定错误，不暴露 stdout/stderr 或异常路径。 */
    public static final class Result {
        private final Status status;
        private final int exitCode;
        private final String value;
        private Result(Status status, int exitCode, String value) {
            this.status = status; this.exitCode = exitCode; this.value = value;
        }
        public static Result failure(Status status) { return new Result(status, -1, ""); }
        public static Result success(String value) { return new Result(Status.OK, 0, value); }
        public Status status() { return status; }
        public int exitCode() { return exitCode; }
        public String value() { return value; }
        public boolean succeeded() { return status == Status.OK; }
        public String message() {
            switch (status) {
                case OK: return "只读命令完成";
                case NOT_FOUND: return "未发现可执行 su";
                case DENIED: return "Root 请求被拒绝或被策略禁止";
                case TIMEOUT: return "检查超时；可在授权界面处理后重试";
                case INTERRUPTED: return "检查已取消";
                case OUTPUT_INVALID: return "检查结果格式异常或超出安全上限";
                case IDENTITY_UNVERIFIED: return "本次命令未验证到 UID 0；请重新检查 Root";
                default: return "命令执行失败；可能为命令缺失、权限或内核限制";
            }
        }
    }

    /** 只查既有系统路径，不执行 su、包管理器或 Root provider 专用 API。 */
    public static String findExecutableSu() {
        final String[] paths = {"/system/bin/su", "/system/xbin/su", "/sbin/su",
                "/su/bin/su", "/debug_ramdisk/su", "/system/bin/.ext/su"};
        for (String path : paths) {
            try {
                final File candidate = new File(path);
                if (candidate.isFile() && candidate.canExecute()) return path;
            } catch (SecurityException ignored) { /* 隐藏或禁止读取不是已授权证据。 */ }
        }
        return null;
    }

    @Override public Result execute(Command command) {
        if (command == null) throw new IllegalArgumentException("command required");
        final String su = findExecutableSu();
        if (su == null) return Result.failure(Status.NOT_FOUND);
        Process process = null;
        BoundedCollector stdout = null;
        BoundedCollector stderr = null;
        try {
            // 每个新 su 进程重新确认身份，避免授权被撤销后继续相信缓存的 GRANTED。
            final String script = command == Command.UID ? command.script : ROOT_GUARD + command.script;
            process = new ProcessBuilder(su, "-c", script).start();
            // 两路持续排空，防止子进程因管道满而死锁；超过上限不保留后续内容。
            stdout = new BoundedCollector(process.getInputStream());
            stderr = new BoundedCollector(process.getErrorStream());
            stdout.start(); stderr.start();
            closeQuietly(process.getOutputStream());
            final long deadline = System.nanoTime() + command.timeoutMs * 1_000_000L;
            int exit;
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                try { exit = process.exitValue(); break; }
                catch (IllegalThreadStateException running) {
                    if (System.nanoTime() >= deadline) return Result.failure(Status.TIMEOUT);
                    // 兼容 minSdk 24，不依赖较新 Android 的 Process.waitFor(timeout)。
                    Thread.sleep(25);
                }
            }
            stdout.join(300); stderr.join(300);
            if (stdout.isAlive() || stderr.isAlive() || stdout.truncated || stderr.truncated
                    || stdout.failed || stderr.failed) return Result.failure(Status.OUTPUT_INVALID);
            return interpret(command, exit, stdout.text(), stderr.text());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Result.failure(Status.INTERRUPTED);
        } catch (IOException | SecurityException failure) {
            return Result.failure(Status.IDENTITY_UNVERIFIED);
        } finally {
            // 超时、拒绝、取消均释放进程和管道；不把原始输出送入 Logcat。
            if (process != null) {
                process.destroy();
                closeQuietly(process.getInputStream()); closeQuietly(process.getErrorStream());
                closeQuietly(process.getOutputStream());
            }
            if (stdout != null) stdout.interrupt();
            if (stderr != null) stderr.interrupt();
        }
    }

    static Result interpret(Command command, int exit, String out, String err) {
        // marker 之前的拒绝来自 su/身份阶段；之后的拒绝可能只是 XFRM 文件/工具权限。
        // 不把后者误报为用户拒绝 Root，也绝不让未通过本次 uid 校验的命令继续检查。
        final boolean identityVerified = command != Command.UID && out.startsWith(ROOT_MARKER);
        if (command != Command.UID && !identityVerified) {
            if (exit != 0 && explicitlyDenied(err)) return new Result(Status.DENIED, exit, "");
            return new Result(Status.IDENTITY_UNVERIFIED, exit, "");
        }
        if (exit != 0) {
            final boolean denied = command == Command.UID && explicitlyDenied(err);
            return new Result(denied ? Status.DENIED : Status.FAILED, exit, "");
        }
        final String commandOutput = identityVerified ? out.substring(ROOT_MARKER.length()) : out;
        final String value = commandOutput.trim();
        switch (command) {
            case UID:
                // 只接受单个十进制 UID；"uid=0"、"0 extra"、混入警告均不获得授权。
                return value.matches("[0-9]{1,10}") ? Result.success(value)
                        : Result.failure(Status.OUTPUT_INVALID);
            case SELINUX:
                return value.equals("Enforcing") || value.equals("Permissive") || value.equals("Disabled")
                        ? Result.success(value) : Result.failure(Status.OUTPUT_INVALID);
            case IPV4_FORWARDING: case IPV6_FORWARDING:
                return value.equals("0") || value.equals("1") ? Result.success(value)
                        : Result.failure(Status.OUTPUT_INVALID);
            case UDP_PORTS: return parseUdpPorts(commandOutput);
            default: return Result.success("READ_CHECK_PASSED");
        }
    }

    private static boolean explicitlyDenied(String error) {
        final String lower = error.toLowerCase(Locale.ROOT);
        return lower.contains("permission denied") || lower.contains("access denied")
                || lower.contains("not allowed") || lower.trim().equals("denied");
    }

    /** 仅报告目标端口，丢弃其他程序地址/端口；快照空闲不等于实际 bind 能成功。 */
    static Result parseUdpPorts(String output) {
        int headers = 0;
        boolean port500 = false, port4500 = false;
        for (String line : output.split("\\r?\\n")) {
            if (line.contains("local_address") && (line.contains("rem_address")
                    || line.contains("remote_address"))) { headers++; continue; }
            if (line.trim().isEmpty()) continue;
            final String[] fields = line.trim().split("\\s+");
            if (fields.length < 3 || !fields[0].matches("[0-9]+:"))
                return Result.failure(Status.OUTPUT_INVALID);
            final String[] endpoint = fields[1].split(":");
            if (endpoint.length != 2 || !endpoint[0].matches("[0-9a-fA-F]{8}|[0-9a-fA-F]{32}")
                    || !endpoint[1].matches("[0-9a-fA-F]{4}"))
                return Result.failure(Status.OUTPUT_INVALID);
            final int port = Integer.parseInt(endpoint[1], 16);
            port500 |= port == 500; port4500 |= port == 4500;
        }
        if (headers != 2) return Result.failure(Status.OUTPUT_INVALID);
        return Result.success(port500 && port4500 ? "BUSY_500_4500"
                : port500 ? "BUSY_500" : port4500 ? "BUSY_4500" : "NO_CONFLICT_OBSERVED");
    }

    private static void closeQuietly(Closeable stream) {
        try { stream.close(); } catch (IOException ignored) { /* 关闭失败不泄漏内容。 */ }
    }

    private static final class BoundedCollector extends Thread {
        private static final int LIMIT = 16_384;
        private final InputStream stream;
        private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        private volatile boolean truncated, failed;
        BoundedCollector(InputStream stream) {
            super("atticus-root-output"); this.stream = stream; setDaemon(true);
        }
        @Override public void run() {
            final byte[] chunk = new byte[512];
            try {
                int length;
                while ((length = stream.read(chunk)) != -1) {
                    final int copy = Math.min(length, LIMIT - captured.size());
                    captured.write(chunk, 0, copy);
                    truncated |= copy < length;
                }
            } catch (IOException ignored) { failed = true; }
            finally { closeQuietly(stream); }
        }
        String text() { return new String(captured.toByteArray(), StandardCharsets.UTF_8); }
    }
}
