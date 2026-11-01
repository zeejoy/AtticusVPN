/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * AtticusVPN Android Server 的纯 Java 核心。
 *
 * <p>本包只负责确定性的模型、策略、配置渲染和生命周期规则，不访问 Android
 * API、网络、文件系统或密码学提供程序。Android 模块必须通过适配器提供密钥
 * 生成、Keystore、SAF 导出和 WireGuard backend。</p>
 */
package dev.lan.atticusvpn.server.core;
