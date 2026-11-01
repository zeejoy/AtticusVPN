/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.core;

/** 客户端路由模式；是否真的可用还必须由 Android runtime 的能力检查决定。 */
public enum TunnelMode {
    FULL,
    LAN
}
