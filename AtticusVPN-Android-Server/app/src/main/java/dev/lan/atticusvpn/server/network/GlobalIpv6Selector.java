/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.network;

import java.net.Inet6Address;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 识别 IPv6 地址作用域并为公网 Endpoint 选择一个确定地址。
 *
 * <p>选择策略有意保守：只允许 {@code 2000::/3} 全局单播地址（GUA），排除 ULA、
 * link-local、loopback、multicast、deprecated、普通 tentative 和 DAD 失败地址。
 * 与 Android LinkAddress.isGlobalPreferred 的状态位语义一致，允许 RFC 4429 的
 * optimistic tentative 地址作后备；已完成 DAD 的 GUA 优先，再优先稳定而非临时 GUA。
 * 本类不依赖 Android API，可直接进行普通 JVM 单元测试。</p>
 */
public final class GlobalIpv6Selector {

    /** IPv6 地址的作用域分类。 */
    public enum AddressScope {
        /** {@code 2000::/3}，可作为互联网 Endpoint 的全局单播地址。 */
        GLOBAL_UNICAST,
        /** {@code fc00::/7}，仅用于私网，不应发布到公共 DDNS。 */
        UNIQUE_LOCAL,
        /** {@code fe80::/10}，只能在本地链路使用。 */
        LINK_LOCAL,
        /** 本机回环地址 {@code ::1}。 */
        LOOPBACK,
        /** IPv6 组播地址。 */
        MULTICAST,
        /** 未指定地址 {@code ::}。 */
        UNSPECIFIED,
        /** 其他未被本策略视为公共 Endpoint 的 IPv6 地址。 */
        OTHER
    }

    /**
     * 带 Android 地址状态元数据的 IPv6 候选项。
     *
     * <p>{@code temporary} 表示隐私/临时地址；{@code deprecated} 表示首选生命周期已结束；
     * {@code tentative} 表示重复地址检测尚未完成；{@code dadFailed} 表示检测失败。</p>
     */
    public static final class Candidate {
        private final Inet6Address address;
        private final int prefixLength;
        private final boolean temporary;
        private final boolean deprecated;
        private final boolean tentative;
        private final boolean dadFailed;
        private final boolean optimistic;
        private final AddressScope scope;

        /**
         * 创建候选地址。
         *
         * @param address IPv6 地址
         * @param prefixLength 前缀长度，范围 0..128
         * @param temporary 是否为临时/隐私地址
         * @param deprecated 是否已弃用
         * @param tentative 是否仍在重复地址检测中
         * @param dadFailed 重复地址检测是否失败
         */
        public Candidate(
                Inet6Address address,
                int prefixLength,
                boolean temporary,
                boolean deprecated,
                boolean tentative,
                boolean dadFailed) {
            this(address, prefixLength, temporary, deprecated, tentative, dadFailed, false);
        }

        /**
         * 创建包含乐观 DAD 标志的候选。optimistic 只改变 tentative 的可用性判定，
         * 绝不能覆盖 deprecated、DAD failed 或非 GUA 的拒绝条件。
         */
        public Candidate(
                Inet6Address address,
                int prefixLength,
                boolean temporary,
                boolean deprecated,
                boolean tentative,
                boolean dadFailed,
                boolean optimistic) {
            this.address = Objects.requireNonNull(address, "address");
            if (prefixLength < 0 || prefixLength > 128) {
                throw new IllegalArgumentException("IPv6 prefixLength 必须位于 0..128。");
            }
            this.prefixLength = prefixLength;
            this.temporary = temporary;
            this.deprecated = deprecated;
            this.tentative = tentative;
            this.dadFailed = dadFailed;
            this.optimistic = optimistic;
            this.scope = classify(address);
        }

        /** 返回 IPv6 地址。 */
        public Inet6Address getAddress() {
            return address;
        }

        /** 返回前缀长度。 */
        public int getPrefixLength() {
            return prefixLength;
        }

        /** 返回是否为临时/隐私地址。 */
        public boolean isTemporary() {
            return temporary;
        }

        /** 返回是否已弃用。 */
        public boolean isDeprecated() {
            return deprecated;
        }

        /** 返回是否仍处于 tentative 状态。 */
        public boolean isTentative() {
            return tentative;
        }

        /** 返回 DAD 是否失败。 */
        public boolean isDadFailed() {
            return dadFailed;
        }

        /** Android 标记为 RFC 4429 乐观 DAD 地址，不代表公网入站已验证。 */
        public boolean isOptimistic() {
            return optimistic;
        }

        /** 返回地址作用域。 */
        public AddressScope getScope() {
            return scope;
        }

        /**
         * 返回该地址是否可作为 Endpoint 候选。
         *
         * @return 仅当地址为健康 GUA 时返回 {@code true}
         */
        public boolean isEndpointEligible() {
            return scope == AddressScope.GLOBAL_UNICAST
                    && !deprecated
                    && (!tentative || optimistic)
                    && !dadFailed;
        }

        /** 返回不含密钥或接口凭据的诊断文本。 */
        @Override
        public String toString() {
            return "Candidate{address=" + address.getHostAddress()
                    + "/" + prefixLength
                    + ", scope=" + scope
                    + ", temporary=" + temporary
                    + ", deprecated=" + deprecated
                    + ", tentative=" + tentative
                    + ", dadFailed=" + dadFailed
                    + ", optimistic=" + optimistic
                    + "}";
        }
    }

    /**
     * 对 IPv6 地址进行作用域分类。
     *
     * @param address IPv6 地址
     * @return 稳定的作用域枚举
     */
    public static AddressScope classify(Inet6Address address) {
        Objects.requireNonNull(address, "address");
        if (address.isAnyLocalAddress()) {
            return AddressScope.UNSPECIFIED;
        }
        if (address.isLoopbackAddress()) {
            return AddressScope.LOOPBACK;
        }
        if (address.isMulticastAddress()) {
            return AddressScope.MULTICAST;
        }
        if (address.isLinkLocalAddress() || hasPrefix(address, 0xfe80, 10)) {
            return AddressScope.LINK_LOCAL;
        }
        if (hasPrefix(address, 0xfc00, 7)) {
            return AddressScope.UNIQUE_LOCAL;
        }
        if (hasPrefix(address, 0x2000, 3)) {
            return AddressScope.GLOBAL_UNICAST;
        }
        return AddressScope.OTHER;
    }

    /**
     * 从候选集合中选择最适合作为 DDNS/WireGuard Endpoint 的地址。
     *
     * <p>排序优先级依次为：已完成 DAD、可用稳定 GUA、可用 temporary GUA、常见的 /64 前缀，
     * 最后按完整 16 字节地址排序，确保同一输入集合始终得到相同结果。</p>
     *
     * @param candidates 候选集合
     * @return 选中的地址；没有健康 GUA 时为空
     */
    public Optional<Candidate> selectEndpoint(Collection<Candidate> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        List<Candidate> eligible = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate != null && candidate.isEndpointEligible()) {
                eligible.add(candidate);
            }
        }
        if (eligible.isEmpty()) {
            return Optional.empty();
        }
        Collections.sort(eligible, ENDPOINT_ORDER);
        return Optional.of(eligible.get(0));
    }

    private static final Comparator<Candidate> ENDPOINT_ORDER = new Comparator<Candidate>() {
        /** 按稳定性、常见前缀和地址字节建立确定的优先级。 */
        @Override
        public int compare(Candidate left, Candidate right) {
            int dadOrder = Boolean.compare(left.isTentative(), right.isTentative());
            if (dadOrder != 0) {
                return dadOrder;
            }
            int temporaryOrder = Boolean.compare(left.isTemporary(), right.isTemporary());
            if (temporaryOrder != 0) {
                return temporaryOrder;
            }
            int leftPrefixPenalty = left.getPrefixLength() == 64 ? 0 : 1;
            int rightPrefixPenalty = right.getPrefixLength() == 64 ? 0 : 1;
            int prefixOrder = Integer.compare(leftPrefixPenalty, rightPrefixPenalty);
            if (prefixOrder != 0) {
                return prefixOrder;
            }
            return compareUnsigned(left.getAddress().getAddress(), right.getAddress().getAddress());
        }
    };

    /** 判断地址是否匹配给定高位前缀。 */
    private static boolean hasPrefix(Inet6Address address, int prefixBits, int prefixLength) {
        byte[] bytes = address.getAddress();
        int first16 = ((bytes[0] & 0xff) << 8) | (bytes[1] & 0xff);
        int mask = 0xffff << (16 - prefixLength);
        return (first16 & mask) == (prefixBits & mask);
    }

    /** 以无符号字节顺序比较完整 IPv6 地址，避免依赖平台文本压缩格式。 */
    private static int compareUnsigned(byte[] left, byte[] right) {
        for (int index = 0; index < Math.min(left.length, right.length); index++) {
            int result = Integer.compare(left[index] & 0xff, right[index] & 0xff);
            if (result != 0) {
                return result;
            }
        }
        return Integer.compare(left.length, right.length);
    }
}
