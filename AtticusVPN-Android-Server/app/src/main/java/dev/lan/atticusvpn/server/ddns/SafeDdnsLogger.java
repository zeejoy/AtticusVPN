/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.ddns;

import android.util.Log;

import java.util.Objects;

/**
 * DDNS 专用安全日志接口。
 *
 * <p>接口实现必须在输出前调用 {@link DdnsLogSanitizer}。业务代码只传递固定事件和已经
 * 脱敏的结果，不记录完整 URL、请求头、正文、token 或异常原文。</p>
 */
public interface SafeDdnsLogger {

    /** 记录普通状态。 */
    void info(String message);

    /** 记录需要注意但不含秘密的状态。 */
    void warn(String message);

    /** 返回完全静默的日志实现，适合测试或禁用日志的部署。 */
    static SafeDdnsLogger noOp() {
        return NoOpHolder.INSTANCE;
    }

    /**
     * 创建写入 Android Logcat 的脱敏日志。
     *
     * @param tag Logcat 标签，不允许换行或过长值
     * @return 自动脱敏的日志实现
     */
    static SafeDdnsLogger android(String tag) {
        return new AndroidSafeLogger(tag);
    }

    /** 延迟持有无操作单例。 */
    final class NoOpHolder {
        private static final SafeDdnsLogger INSTANCE = new SafeDdnsLogger() {
            /** 丢弃普通状态。 */
            @Override
            public void info(String message) {
                // 明确不输出。
            }

            /** 丢弃警告状态。 */
            @Override
            public void warn(String message) {
                // 明确不输出。
            }
        };

        private NoOpHolder() {
        }
    }

    /** 所有写入点都强制调用脱敏器的 Android Logcat 实现。 */
    final class AndroidSafeLogger implements SafeDdnsLogger {
        private final String tag;

        private AndroidSafeLogger(String tag) {
            Objects.requireNonNull(tag, "tag");
            String candidate = tag.trim();
            if (candidate.isEmpty() || candidate.length() > 23
                    || candidate.indexOf('\r') >= 0 || candidate.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("Logcat 标签无效。");
            }
            this.tag = candidate;
        }

        /** 清洗后写入 info 日志。 */
        @Override
        public void info(String message) {
            Log.i(tag, DdnsLogSanitizer.sanitize(message));
        }

        /** 清洗后写入 warn 日志。 */
        @Override
        public void warn(String message) {
            Log.w(tag, DdnsLogSanitizer.sanitize(message));
        }
    }
}
