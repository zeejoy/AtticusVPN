/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.network;

import java.util.Objects;

/**
 * 合并同一默认网络的两类回调数据，禁止混用旧网络的数据。
 *
 * <p>本类不调用 Android API，便于对回调乱序、网络切换和停止清理做 JVM 回归测试。
 * 调用方必须持有自己的生命周期锁；Android 对象需由调用方复制后传入。</p>
 */
final class NetworkCallbackState<N, C, L> {
    private N network;
    private C capabilities;
    private L links;

    void available(final N value) {
        clear();
        network = Objects.requireNonNull(value, "network");
    }

    boolean isCurrent(final N value) {
        return network != null && network.equals(value);
    }

    boolean capabilities(final N value, final C update) {
        if (!isCurrent(value)) return false;
        capabilities = Objects.requireNonNull(update, "capabilities");
        return true;
    }

    boolean links(final N value, final L update) {
        if (!isCurrent(value)) return false;
        links = Objects.requireNonNull(update, "links");
        return true;
    }

    boolean lost(final N value) {
        if (!isCurrent(value)) return false;
        clear();
        return true;
    }

    void clear() {
        network = null;
        capabilities = null;
        links = null;
    }

    C capabilities() { return capabilities; }
    L links() { return links; }
}
