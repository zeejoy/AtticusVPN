/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.ddns;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对 DDNS 日志执行最后一道确定性脱敏。
 *
 * <p>生产代码仍应优先只记录固定事件，而不是依赖事后清洗。本类会隐藏 URL 查询串、
 * Authorization/Bearer/Basic 值，以及 token、password、api-key、secret、update-key 等
 * 常见键名对应的值，并限制最终长度。</p>
 */
public final class DdnsLogSanitizer {
    private static final int MAX_LOG_LENGTH = 1024;
    private static final String REDACTED = "[已隐藏]";
    private static final Pattern URL_QUERY = Pattern.compile(
            "(?i)(https://[^\\s?#]+)\\?[^\\s#]*");
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)(authorization\\s*[:=]\\s*)(?:bearer|basic)?\\s*[^,;\\s]+(?:\\s+[^,;\\s]+)?");
    private static final Pattern SENSITIVE_PAIR = Pattern.compile(
            "(?i)(token|password|passwd|api[-_]?key|secret|update[-_]?key)"
                    + "(\\s*[:=]\\s*)([^&;,\\s}]+)");

    private DdnsLogSanitizer() {
    }

    /**
     * 清洗任意日志文本。
     *
     * @param value 原始文本，可为空
     * @return 不含常见凭据且长度受限的单行文本
     */
    public static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String singleLine = value.replace('\r', ' ').replace('\n', ' ');
        String sanitized = URL_QUERY.matcher(singleLine)
                .replaceAll("$1?[查询参数已隐藏]");
        sanitized = AUTHORIZATION.matcher(sanitized)
                .replaceAll("$1" + REDACTED);
        sanitized = replaceSensitivePairs(sanitized);
        return limit(sanitized);
    }

    /**
     * 在通用规则之外隐藏调用方掌握的确切秘密。
     *
     * @param value 原始文本
     * @param secrets 可能出现的原始凭据
     * @return 脱敏文本
     */
    public static String sanitize(String value, String... secrets) {
        String sanitized = sanitize(value);
        if (secrets == null) {
            return sanitized;
        }
        for (String secret : secrets) {
            if (secret != null && !secret.isEmpty()) {
                sanitized = sanitized.replace(secret, REDACTED);
            }
        }
        return limit(sanitized);
    }

    /** 使用 Matcher.quoteReplacement 安全替换可能包含美元符号的输入。 */
    private static String replaceSensitivePairs(String input) {
        Matcher matcher = SENSITIVE_PAIR.matcher(input);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(
                    result,
                    Matcher.quoteReplacement(matcher.group(1) + matcher.group(2) + REDACTED));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /** 限制日志长度，既控制资源也减少意外数据暴露面。 */
    private static String limit(String value) {
        if (value.length() <= MAX_LOG_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_LOG_LENGTH) + "…";
    }
}
