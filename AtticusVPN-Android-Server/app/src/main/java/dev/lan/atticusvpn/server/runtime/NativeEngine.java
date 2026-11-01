/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.runtime;

/**
 * native-engine 的最小 Java 边界。
 *
 * <p>接口让 Service 可在 AAR 尚未生成时继续编译，也让本地单元测试能注入假引擎。
 * UAPI 含服务器私钥与 PSK，任何实现都不得把参数或底层异常文本写入日志。</p>
 */
public interface NativeEngine {
    /** 返回本机构建版本，失败时抛固定错误码。 */
    String version() throws NativeEngineException;

    /** 选择 Android 默认物理网络；0 表示让系统选择。 */
    void setNetworkHandle(long networkHandle) throws NativeEngineException;

    /** 启动用户态 WireGuard 数据面。 */
    void start(String serverUapi, String upstreamDnsCsv, int mtu) throws NativeEngineException;

    /** 返回 WireGuard IPv4/IPv6 UDP listener 的借用 fd，不可由 Java 关闭。 */
    int socketFd4() throws NativeEngineException;
    int socketFd6() throws NativeEngineException;

    /** 停止数据面；可重复调用。 */
    void stop() throws NativeEngineException;

    /** 返回 native 已脱敏状态 JSON；调用方仍不得直接写 Logcat。 */
    String status() throws NativeEngineException;
}
