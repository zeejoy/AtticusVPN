/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.ddns;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 使用 Android/Java 标准库 {@link HttpURLConnection} 的 DDNS HTTPS 传输。
 *
 * <p>实现不依赖第三方网络库，不跟随重定向，限制响应为 16 KiB，并且从不记录请求 URL、
 * 请求头、请求体或异常原文。调用方应把异常映射成固定的脱敏消息。</p>
 */
public final class UrlConnectionDdnsHttpTransport implements DdnsHttpTransport {
    private static final int MAX_RESPONSE_BYTES = 16 * 1024;

    /** 执行一次有超时和响应上限的 HTTPS 请求。 */
    @Override
    public Response execute(Request request) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) request.getUrl().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(request.getMethod());
            connection.setConnectTimeout(request.getConnectTimeoutMillis());
            connection.setReadTimeout(request.getReadTimeoutMillis());
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "text/plain, application/json");
            for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }

            byte[] body = request.getBody();
            if (body.length > 0) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }
            }

            int status = connection.getResponseCode();
            InputStream stream = status >= 400
                    ? connection.getErrorStream()
                    : connection.getInputStream();
            String responseBody = stream == null ? "" : readBounded(stream);
            return new Response(status, responseBody);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** 读取有限字节并在超限时中止，防止异常服务消耗过多内存。 */
    private static String readBounded(InputStream input) throws IOException {
        try (InputStream source = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int total = 0;
            int count;
            while ((count = source.read(buffer)) != -1) {
                if (total + count > MAX_RESPONSE_BYTES) {
                    throw new IOException("DDNS response exceeded size limit");
                }
                output.write(buffer, 0, count);
                total += count;
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
