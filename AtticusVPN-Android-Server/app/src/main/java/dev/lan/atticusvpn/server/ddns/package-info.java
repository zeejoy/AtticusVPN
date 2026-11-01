/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
/**
 * AtticusVPN Server 的动态 DNS 更新模块。
 *
 * <p>该包只负责把已选定的公网 IPv6 地址提交给用户配置的 DDNS 服务，不生成密钥，
 * 也不把令牌、Authorization 头或完整请求 URL 写入日志。</p>
 */
package dev.lan.atticusvpn.server.ddns;
