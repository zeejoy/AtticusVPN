/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.core;

/** Peer 在服务器持久状态中的生命周期。 */
public enum PeerState {
    /** 服务器仍保存首次导出所需的客户端私钥。 */
    ACTIVE,
    /** 客户端私钥和可导出配置已删除，但服务器授权仍然有效。 */
    SEALED
}
