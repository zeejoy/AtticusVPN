/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
/**
 * Apple 原生 IKEv2 实验性配置构建基础（不构成可用服务或真实设备通过的声明）。
 *
 * <p>2026-09-16 核对的官方字段来源：</p>
 * <ul>
 * <li>https://developer.apple.com/documentation/devicemanagement/vpn/ikev2-data.dictionary</li>
 * <li>https://developer.apple.com/documentation/devicemanagement/vpn/dns-data.dictionary</li>
 * <li>https://developer.apple.com/documentation/devicemanagement/certificatepkcs12</li>
 * <li>https://developer.apple.com/documentation/devicemanagement/certificateroot</li>
 * <li>https://github.com/apple/device-management/blob/release/mdm/profiles/com.apple.vpn.managed.yaml</li>
 * <li>https://docs.strongswan.org/docs/latest/interop/ios.html</li>
 * </ul>
 *
 * <p>目前配置面向 iOS/iPadOS 14.2+、macOS 11+。IncludeAllNetworks 放在 IKEv2 字典；
 * Apple 仍保留 DHCP/认证门户/部分系统流量等例外，因此不是“绝不泄漏”的承诺。
 * IPv4.OverridePrimary 在 Apple schema 仅用于 Cisco IPsec/L2TP，不能伪称它控制 IKEv2；
 * 也不创建官方未定义的 IPv6 字典。UseConfigurationAttributeInternalIPSubnet 允许
 * 协商 IPv4/IPv6 子网，真正的双栈地址、DNS可达性、0.0.0.0/0 与 ::/0 traffic selectors、
 * 路由/转发/回程仍必须在 strongSwan 与内核数据面实现并真机验证。</p>
 *
 * <p>CA 应分别直接签 server 与 client，不能让 server 证书签 client。当前渲染器校验
 * CA 自签、有效期/签发用途和独立 RSA client 身份容器及 DNS SAN；CA 来源信任、
 * server 证书 SAN 匹配 RemoteIdentifier/地址、serverAuth EKU、每设备密钥唯一性与
 * 吊销/过期状态由上层证书模块负责。daemon 使用 pubkey 客户端认证并设置 send_cert=always。
 * MOBIKE/DPD 字段仅提出标准客户端选项，不证明网络变化恢复成功。
 * DNS 使用 VPN 内 Cleartext，仅表示隧道内传统 DNS，不是额外 TLS 或隐蔽机制。</p>
 *
 * <p>PKCS#12 密码不嵌入描述文件，安装时应另行输入。profile 未做 CMS 签名/加密；
 * 保持设备用户可移除。生成器不实施导出/分享。业务层没有真实证书、daemon 或已验证
 * 数据面时必须保持导出入口禁用，不得用测试材料生成可用功能演示。</p>
 */
package dev.lan.atticusvpn.server.apple;
