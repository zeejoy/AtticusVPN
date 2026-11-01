/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.ddns;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 可替换的 DDNS HTTP 传输边界。
 *
 * <p>Provider 只负责组成协议请求；Android 运行时使用
 * {@link UrlConnectionDdnsHttpTransport}，普通 JVM 测试则可注入内存实现。</p>
 */
public interface DdnsHttpTransport {

    /**
     * 执行一次 HTTPS 请求。
     *
     * @param request 已验证的请求
     * @return 有长度上限的响应
     * @throws IOException 网络、TLS 或读写失败
     */
    Response execute(Request request) throws IOException;

    /**
     * 不可变 HTTP 请求。
     *
     * <p>{@link #toString()} 有意不显示 URL、头和值或正文，因此即使 URL 查询参数中含有
     * DuckDNS/FreeDNS 令牌，也不会因对象被记录而泄露。</p>
     */
    final class Request {
        private final URL url;
        private final String method;
        private final Map<String, String> headers;
        private final byte[] body;
        private final int connectTimeoutMillis;
        private final int readTimeoutMillis;
        private final String safeOperationName;

        /**
         * 创建 HTTPS 请求。
         *
         * @param url HTTPS 地址；可能含敏感查询参数，禁止直接记录
         * @param method HTTP 方法
         * @param headers 请求头；会复制保存
         * @param body 请求体；会复制保存，可为空
         * @param connectTimeoutMillis 连接超时
         * @param readTimeoutMillis 读取超时
         * @param safeOperationName 不含用户数据和凭据的操作名
         */
        public Request(
                URL url,
                String method,
                Map<String, String> headers,
                byte[] body,
                int connectTimeoutMillis,
                int readTimeoutMillis,
                String safeOperationName) {
            this.url = Objects.requireNonNull(url, "url");
            if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getHost().isEmpty()
                    || url.getUserInfo() != null) {
                throw new IllegalArgumentException(
                        "DDNS 传输只允许无 user-info 且带主机名的 HTTPS URL。");
            }
            this.method = DdnsProviderSupport.requireHttpMethod(method);
            this.headers = immutableHeaders(headers);
            this.body = body == null ? new byte[0] : body.clone();
            if (connectTimeoutMillis < 1 || readTimeoutMillis < 1) {
                throw new IllegalArgumentException("HTTP 超时必须大于 0。");
            }
            this.connectTimeoutMillis = connectTimeoutMillis;
            this.readTimeoutMillis = readTimeoutMillis;
            this.safeOperationName = DdnsProviderSupport.requireSafeLabel(safeOperationName);
        }

        /** 返回实际请求 URL；调用者不得将其写入日志。 */
        public URL getUrl() {
            return url;
        }

        /** 返回 HTTP 方法。 */
        public String getMethod() {
            return method;
        }

        /** 返回不可变请求头；调用者不得记录头值。 */
        public Map<String, String> getHeaders() {
            return headers;
        }

        /** 返回请求体副本；调用者不得记录正文。 */
        public byte[] getBody() {
            return body.clone();
        }

        /** 返回连接超时毫秒数。 */
        public int getConnectTimeoutMillis() {
            return connectTimeoutMillis;
        }

        /** 返回读取超时毫秒数。 */
        public int getReadTimeoutMillis() {
            return readTimeoutMillis;
        }

        /** 返回可安全显示的固定操作名。 */
        public String getSafeOperationName() {
            return safeOperationName;
        }

        /** 只输出方法和安全操作名，绝不输出 URL、认证头或请求体。 */
        @Override
        public String toString() {
            return "DdnsHttpRequest{method=" + method
                    + ", operation=" + safeOperationName + "}";
        }

        /** 防止换行注入，并将请求头复制为不可变映射。 */
        private static Map<String, String> immutableHeaders(Map<String, String> source) {
            if (source == null || source.isEmpty()) {
                return Collections.emptyMap();
            }
            Map<String, String> copy = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : source.entrySet()) {
                String name = DdnsProviderSupport.requireHeaderName(entry.getKey());
                String value = DdnsProviderSupport.requireHeaderValue(entry.getValue());
                copy.put(name, value);
            }
            return Collections.unmodifiableMap(copy);
        }
    }

    /** 只包含状态码和有界文本的 HTTP 响应。 */
    final class Response {
        private final int statusCode;
        private final String body;

        /**
         * 创建响应对象。
         *
         * @param statusCode HTTP 状态码
         * @param body 响应正文，可为空
         */
        public Response(int statusCode, String body) {
            if (statusCode < 100 || statusCode > 999) {
                throw new IllegalArgumentException("HTTP 响应状态码无效。");
            }
            this.statusCode = statusCode;
            this.body = body == null ? "" : DdnsProviderSupport.limitText(body, 16_384);
        }

        /** 返回 HTTP 状态码。 */
        public int getStatusCode() {
            return statusCode;
        }

        /** 返回最多 16 KiB 的响应正文；Provider 不应把它原样写入日志。 */
        public String getBody() {
            return body;
        }

        /** 不显示正文，避免服务商意外回显请求凭据。 */
        @Override
        public String toString() {
            return "DdnsHttpResponse{statusCode=" + statusCode
                    + ", bodyLength=" + body.length() + "}";
        }
    }
}
