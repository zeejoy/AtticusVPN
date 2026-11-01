/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.ddns;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 通过 Android/Java 系统 DNS 解析器查询 AAAA 记录。
 *
 * <p>系统解析器会遵循当前默认网络和平台 DNS 缓存。因此刚更新 DDNS 时看到旧值是正常的，
 * 调用方必须把不匹配视为“待传播”，不能视为已确认。</p>
 */
public final class SystemAaaaRecordResolver implements AaaaRecordResolver {

    /** 查询系统解析结果并只保留 IPv6 地址。 */
    @Override
    public List<Inet6Address> resolve(String hostname) throws IOException {
        String normalized = DdnsUpdateRequest.normalizeHostname(hostname);
        InetAddress[] addresses = InetAddress.getAllByName(normalized);
        List<Inet6Address> result = new ArrayList<>();
        for (InetAddress address : addresses) {
            if (address instanceof Inet6Address) {
                result.add((Inet6Address) address);
            }
        }
        return Collections.unmodifiableList(result);
    }
}
