/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.ddns;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Provider 实现共用的输入验证、编码和安全结果映射。 */
final class DdnsProviderSupport {
    static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 15_000;
    static final int DEFAULT_READ_TIMEOUT_MILLIS = 15_000;
    private static final Pattern PROVIDER_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
    private static final Pattern SAFE_LABEL = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,64}");
    private static final String BASE64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    private DdnsProviderSupport() {
    }

    /** 校验 Provider 标识，确保其可安全进入日志。 */
    static String requireProviderId(String value) {
        Objects.requireNonNull(value, "providerId");
        String candidate = value.trim().toLowerCase(Locale.ROOT);
        if (!PROVIDER_ID.matcher(candidate).matches()) {
            throw new IllegalArgumentException("Provider 标识无效。");
        }
        return candidate;
    }

    /** 校验可安全输出的固定标签。 */
    static String requireSafeLabel(String value) {
        Objects.requireNonNull(value, "safeOperationName");
        String candidate = value.trim();
        if (!SAFE_LABEL.matcher(candidate).matches()) {
            throw new IllegalArgumentException("安全操作名无效。");
        }
        return candidate;
    }

    /** 仅允许 Provider 所需的有限 HTTP 方法。 */
    static String requireHttpMethod(String value) {
        Objects.requireNonNull(value, "method");
        String candidate = value.trim().toUpperCase(Locale.ROOT);
        if (!"GET".equals(candidate) && !"POST".equals(candidate)
                && !"PUT".equals(candidate)) {
            throw new IllegalArgumentException("DDNS HTTP 方法不受支持。");
        }
        return candidate;
    }

    /** 校验 HTTP 请求头名称。 */
    static String requireHeaderName(String value) {
        Objects.requireNonNull(value, "headerName");
        if (!HEADER_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("HTTP 请求头名称无效。");
        }
        return value;
    }

    /** 拒绝空值和换行，防止请求头注入。 */
    static String requireHeaderValue(String value) {
        Objects.requireNonNull(value, "headerValue");
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("HTTP 请求头值无效。");
        }
        return value;
    }

    /** 要求配置中的秘密非空且不含控制字符。 */
    static String requireSecret(String value, String displayName) {
        Objects.requireNonNull(value, displayName);
        if (value.isEmpty() || value.length() > 4096 || containsControl(value)) {
            throw new IllegalArgumentException(displayName + " 无效。");
        }
        return value;
    }

    /** 要求普通配置值非空且不含控制字符。 */
    static String requireValue(String value, String displayName, int maxLength) {
        Objects.requireNonNull(value, displayName);
        String candidate = value.trim();
        if (candidate.isEmpty() || candidate.length() > maxLength || containsControl(candidate)) {
            throw new IllegalArgumentException(displayName + " 无效。");
        }
        return candidate;
    }

    /** 构造并再次确认 HTTPS URL。 */
    static URL httpsUrl(String value) {
        try {
            URL url = new URL(value);
            if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getHost().isEmpty()
                    || url.getUserInfo() != null) {
                throw new IllegalArgumentException("DDNS 地址必须是无 user-info 的 HTTPS URL。");
            }
            return url;
        } catch (MalformedURLException error) {
            throw new IllegalArgumentException("DDNS 地址无效。", error);
        }
    }

    /** 按 UTF-8 编码单个查询参数。 */
    static String queryEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /** 生成 Basic Authorization 值，不依赖较新 Android API。 */
    static String basicAuthorization(String username, String password) {
        String user = requireValue(username, "用户名", 512);
        // Basic 认证以冒号分隔用户名和密码；拒绝含冒号的用户名可避免字段边界歧义。
        if (user.indexOf(':') >= 0) {
            throw new IllegalArgumentException("用户名不能包含冒号。");
        }
        String secret = requireSecret(password, "密码");
        byte[] bytes = (user + ":" + secret).getBytes(StandardCharsets.UTF_8);
        return "Basic " + base64(bytes);
    }

    /** 为 JSON 字符串值转义控制符、引号和反斜杠。 */
    static String jsonEscape(String value) {
        StringBuilder output = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"': output.append("\\\""); break;
                case '\\': output.append("\\\\"); break;
                case '\b': output.append("\\b"); break;
                case '\f': output.append("\\f"); break;
                case '\n': output.append("\\n"); break;
                case '\r': output.append("\\r"); break;
                case '\t': output.append("\\t"); break;
                default:
                    if (character < 0x20) {
                        output.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        output.append(character);
                    }
            }
        }
        return output.toString();
    }

    /** 创建常用的 JSON 请求头。 */
    static Map<String, String> jsonHeaders(String authorization) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", requireHeaderValue(authorization));
        headers.put("Content-Type", "application/json; charset=utf-8");
        return Collections.unmodifiableMap(headers);
    }

    /** 创建常用的 Basic Authorization 请求头。 */
    static Map<String, String> basicHeaders(String username, String password) {
        return basicHeaders(username, password, "AtticusVPN-Server/0.3.0 TianzeL");
    }

    /** 创建带调用方指定 User-Agent 的 Basic Authorization 请求头。 */
    static Map<String, String> basicHeaders(
            String username,
            String password,
            String userAgent) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", basicAuthorization(username, password));
        headers.put("User-Agent", requireHeaderValue(userAgent));
        return Collections.unmodifiableMap(headers);
    }

    /** 把 I/O 失败映射为不包含异常文字的固定结果。 */
    static DdnsUpdateResult networkFailure(String providerId, IOException ignored) {
        return DdnsUpdateResult.local(
                providerId,
                DdnsUpdateResult.Status.RETRYABLE_ERROR,
                "DDNS 网络请求失败；可稍后重试。");
    }

    /** 将 HTTP 状态码转换成临时或永久错误，正文不进入结果。 */
    static DdnsUpdateResult httpFailure(String providerId, int httpStatus) {
        DdnsUpdateResult.Status status = httpStatus == 408 || httpStatus == 425
                || httpStatus == 429 || httpStatus >= 500
                ? DdnsUpdateResult.Status.RETRYABLE_ERROR
                : DdnsUpdateResult.Status.PERMANENT_ERROR;
        return new DdnsUpdateResult(
                providerId,
                status,
                httpStatus,
                status == DdnsUpdateResult.Status.RETRYABLE_ERROR
                        ? "DDNS 服务暂时不可用；可稍后重试。"
                        : "DDNS 服务拒绝请求；请检查域名和凭据配置。");
    }

    /** 判断状态码是否属于成功范围。 */
    static boolean isSuccessfulHttp(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    /** 以 Locale.ROOT 生成用于解析 Provider 固定响应码的小写字符串。 */
    static String normalizedBody(DdnsHttpTransport.Response response) {
        return response.getBody().trim().toLowerCase(Locale.ROOT);
    }

    /** 限制任意文本长度。 */
    static String limitText(String value, int maximum) {
        if (value.length() <= maximum) {
            return value;
        }
        return value.substring(0, maximum);
    }

    /** 判断输入中是否有任何 C0/DEL 控制字符。 */
    private static boolean containsControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 0x1f || character == 0x7f) {
                return true;
            }
        }
        return false;
    }

    /** 纯 Java 8 兼容的 Base64 编码，支持 Android API 24。 */
    private static String base64(byte[] input) {
        StringBuilder output = new StringBuilder(((input.length + 2) / 3) * 4);
        for (int offset = 0; offset < input.length; offset += 3) {
            int first = input[offset] & 0xff;
            int second = offset + 1 < input.length ? input[offset + 1] & 0xff : 0;
            int third = offset + 2 < input.length ? input[offset + 2] & 0xff : 0;
            output.append(BASE64.charAt(first >>> 2));
            output.append(BASE64.charAt(((first & 0x03) << 4) | (second >>> 4)));
            output.append(offset + 1 < input.length
                    ? BASE64.charAt(((second & 0x0f) << 2) | (third >>> 6)) : '=');
            output.append(offset + 2 < input.length ? BASE64.charAt(third & 0x3f) : '=');
        }
        return output.toString();
    }
}
