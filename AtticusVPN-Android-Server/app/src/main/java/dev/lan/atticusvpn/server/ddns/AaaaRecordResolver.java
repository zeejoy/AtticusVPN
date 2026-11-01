/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.ddns;

import java.io.IOException;
import java.net.Inet6Address;
import java.util.List;

/**
 * 查询域名 AAAA 记录的可替换边界。
 *
 * <p>生产实现使用 Android/Java 系统解析器，测试可注入固定结果。该接口只处理域名和公网
 * 地址，不接收 DDNS token 或其他凭据。</p>
 */
public interface AaaaRecordResolver {

    /**
     * 解析域名当前可见的全部 IPv6 地址。
     *
     * @param hostname 已规范化的完整域名
     * @return 不含 IPv4 地址的列表，可为空但不能为 {@code null}
     * @throws IOException DNS 查询失败
     */
    List<Inet6Address> resolve(String hostname) throws IOException;
}
