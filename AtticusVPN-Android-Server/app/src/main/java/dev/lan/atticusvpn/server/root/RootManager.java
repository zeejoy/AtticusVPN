/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.root;

/**
 * 授权状态不落盘：重启后不能拿上次的成功假装当前仍有权限。
 * 所有交互式 su 操作由 UI 的明确点击驱动，启动探测只读文件元数据。
 */
public final class RootManager {
    public interface CommandRunner { RootCommandExecutor.Result execute(RootCommandExecutor.Command command); }
    public interface SuLocator { boolean exists(); }
    private final CommandRunner runner;
    private final SuLocator locator;
    private volatile RootState state = RootState.ROOT_UNKNOWN;

    public RootManager() { this(new RootCommandExecutor(), () -> RootCommandExecutor.findExecutableSu() != null); }
    /** 测试注入只用于离线逻辑验证；生产构造始终使用真实执行器。 */
    public RootManager(CommandRunner runner, SuLocator locator) {
        this.runner = java.util.Objects.requireNonNull(runner);
        this.locator = java.util.Objects.requireNonNull(locator);
    }
    public RootState state() { return state; }
    public String provider() { return "Unknown Root Provider"; }
    public synchronized RootState discoverWithoutPrompt() {
        try { state = locator.exists() ? RootState.ROOT_UNKNOWN : RootState.ROOT_NOT_FOUND; }
        catch (RuntimeException failure) { state = RootState.ROOT_ERROR; }
        return state;
    }
    /** 必须在用户点击后从后台线程调用；勿由开机、定时器、网络回调触发。 */
    public synchronized RootState requestAuthorization() {
        final RootCommandExecutor.Result result;
        try { result = runner.execute(RootCommandExecutor.Command.UID); }
        catch (RuntimeException failure) { state = RootState.ROOT_ERROR; return state; }
        if (result.succeeded() && result.value().equals("0")) state = RootState.ROOT_GRANTED;
        else if (result.status() == RootCommandExecutor.Status.NOT_FOUND) state = RootState.ROOT_NOT_FOUND;
        else if (result.status() == RootCommandExecutor.Status.DENIED) state = RootState.ROOT_PERMISSION_DENIED;
        else state = RootState.ROOT_ERROR;
        return state;
    }
    /**
     * 只在同一次用户主动能力检查中使用。即使上次已授权也不应后台调用，
     * 因为某些 su 实现对每个新进程重新询问授权。
     */
    public synchronized RootCommandExecutor.Result executeReadOnly(RootCommandExecutor.Command command) {
        if (state != RootState.ROOT_GRANTED) return RootCommandExecutor.Result.failure(RootCommandExecutor.Status.DENIED);
        if (command == RootCommandExecutor.Command.UID) throw new IllegalArgumentException("Use requestAuthorization");
        final RootCommandExecutor.Result result;
        try { result = runner.execute(command); }
        catch (RuntimeException failure) {
            state = RootState.ROOT_ERROR;
            return RootCommandExecutor.Result.failure(RootCommandExecutor.Status.IDENTITY_UNVERIFIED);
        }
        if (result.status() == RootCommandExecutor.Status.DENIED) state = RootState.ROOT_PERMISSION_DENIED;
        else if (result.status() == RootCommandExecutor.Status.NOT_FOUND) state = RootState.ROOT_NOT_FOUND;
        else if (result.status() == RootCommandExecutor.Status.INTERRUPTED) state = RootState.ROOT_UNKNOWN;
        else if (result.status() == RootCommandExecutor.Status.IDENTITY_UNVERIFIED
                || result.status() == RootCommandExecutor.Status.TIMEOUT
                || result.status() == RootCommandExecutor.Status.OUTPUT_INVALID) state = RootState.ROOT_ERROR;
        // FAILED 表示当前进程已通过 UID 0 校验，但某个只读目标不可用；不把它当撤销。
        return result;
    }
}
