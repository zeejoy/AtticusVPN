/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.ddns;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 面向自建 HTTPS API 的可配置 DDNS Provider。
 *
 * <p>URL、请求头和正文支持 {@code {hostname}}、{@code {ipv6}}、{@code {token}} 三个占位符。
 * URL 中的替换值会进行查询参数安全编码；头和正文按原值替换。token 不会出现在请求对象
 * 的 {@code toString()} 或结果消息中。该实现仅允许 HTTPS，并拒绝 URL user-info 和头换行。</p>
 */
public final class CustomApiDdnsProvider implements DdnsProvider {
    private static final Pattern NAMED_PLACEHOLDER =
            Pattern.compile("\\{([A-Za-z][A-Za-z0-9_-]*)}");

    private final DdnsHttpTransport transport;
    private final String endpointTemplate;
    private final String method;
    private final Map<String, String> headerTemplates;
    private final String bodyTemplate;
    private final String token;
    private final String successBodyContains;

    /** 使用标准 URLConnection 传输创建自定义 Provider。 */
    public CustomApiDdnsProvider(
            String endpointTemplate,
            String method,
            Map<String, String> headerTemplates,
            String bodyTemplate,
            String token,
            String successBodyContains) {
        this(new UrlConnectionDdnsHttpTransport(), endpointTemplate, method,
                headerTemplates, bodyTemplate, token, successBodyContains);
    }

    /**
     * 创建自定义 HTTPS Provider。
     *
     * @param transport 可替换 HTTPS 传输
     * @param endpointTemplate HTTPS URL 模板；IPv6 也可放在请求头或正文模板中
     * @param method GET、POST 或 PUT；Android 标准 HttpURLConnection 不支持 PATCH
     * @param headerTemplates 请求头模板
     * @param bodyTemplate 可选正文模板
     * @param token 可在模板中引用的秘密；不需要时可传空字符串
     * @param successBodyContains 可选的成功响应固定片段；为空时仅按 2xx 判断
     */
    public CustomApiDdnsProvider(
            DdnsHttpTransport transport,
            String endpointTemplate,
            String method,
            Map<String, String> headerTemplates,
            String bodyTemplate,
            String token,
            String successBodyContains) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.endpointTemplate = validateEndpointTemplate(endpointTemplate);
        this.method = DdnsProviderSupport.requireHttpMethod(method);
        this.headerTemplates = validateHeaderTemplates(headerTemplates);
        this.bodyTemplate = bodyTemplate == null ? "" : limitTemplate(bodyTemplate, 65_536, "请求体模板");
        this.token = token == null || token.isEmpty()
                ? "" : DdnsProviderSupport.requireSecret(token, "自定义 API token");
        this.successBodyContains = successBodyContains == null
                ? "" : limitTemplate(successBodyContains, 1024, "成功响应标记");
        ensureKnownPlaceholders(this.bodyTemplate);
        ensureKnownPlaceholders(this.successBodyContains);
        for (String template : this.headerTemplates.values()) {
            ensureKnownPlaceholders(template);
        }
        if (!requestContainsIpv6Placeholder()) {
            throw new IllegalArgumentException(
                    "自定义 API 的 URL、请求头或请求体必须至少包含一个 {ipv6}。");
        }
    }

    /** 返回固定 Provider 标识。 */
    @Override
    public String getId() {
        return "custom";
    }

    /** 渲染用户配置的 HTTPS 请求并根据状态码/固定响应片段判断成功。 */
    @Override
    public DdnsUpdateResult update(DdnsUpdateRequest request) {
        Objects.requireNonNull(request, "request");
        String urlText = renderUrl(endpointTemplate, request);
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headerTemplates.entrySet()) {
            headers.put(entry.getKey(), DdnsProviderSupport.requireHeaderValue(
                    renderRaw(entry.getValue(), request)));
        }
        byte[] body = renderRaw(bodyTemplate, request).getBytes(StandardCharsets.UTF_8);
        DdnsHttpTransport.Request httpRequest = new DdnsHttpTransport.Request(
                DdnsProviderSupport.httpsUrl(urlText), method, headers, body,
                DdnsProviderSupport.DEFAULT_CONNECT_TIMEOUT_MILLIS,
                DdnsProviderSupport.DEFAULT_READ_TIMEOUT_MILLIS,
                "custom-ddns-update");
        try {
            DdnsHttpTransport.Response response = transport.execute(httpRequest);
            if (!DdnsProviderSupport.isSuccessfulHttp(response.getStatusCode())) {
                return DdnsProviderSupport.httpFailure(getId(), response.getStatusCode());
            }
            if (!successBodyContains.isEmpty()
                    && !response.getBody().contains(renderRaw(successBodyContains, request))) {
                return new DdnsUpdateResult(getId(), DdnsUpdateResult.Status.RETRYABLE_ERROR,
                        response.getStatusCode(), "自定义 DDNS API 未返回预期的成功标记。");
            }
            return new DdnsUpdateResult(getId(), DdnsUpdateResult.Status.UPDATED,
                    response.getStatusCode(), "自定义 DDNS API 已接受更新；等待解析确认。");
        } catch (IOException error) {
            return DdnsProviderSupport.networkFailure(getId(), error);
        }
    }

    /** 验证模板结构和占位符；先用非敏感样例渲染来验证 URL。 */
    private static String validateEndpointTemplate(String value) {
        String template = limitTemplate(
                DdnsProviderSupport.requireValue(value, "自定义 API URL 模板", 8192),
                8192,
                "自定义 API URL 模板");
        ensureKnownPlaceholders(template);
        String sample = template
                .replace("{hostname}", "vpn.example.com")
                .replace("{ipv6}", "2001%3Adb8%3A%3A1")
                .replace("{token}", "sample");
        DdnsProviderSupport.httpsUrl(sample);
        return template;
    }

    /** 复制并验证请求头模板，防止配置时注入额外头。 */
    private static Map<String, String> validateHeaderTemplates(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        if (source.size() > 32) {
            throw new IllegalArgumentException("自定义 API 请求头过多。");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String name = DdnsProviderSupport.requireHeaderName(entry.getKey());
            String value = DdnsProviderSupport.requireHeaderValue(entry.getValue());
            result.put(name, limitTemplate(value, 8192, "请求头模板"));
        }
        return Collections.unmodifiableMap(result);
    }

    /** URL 上下文使用百分号编码替换，避免 token 或域名改变 URL 结构。 */
    private String renderUrl(String template, DdnsUpdateRequest request) {
        return template
                .replace("{hostname}", DdnsProviderSupport.queryEncode(request.getHostname()))
                .replace("{ipv6}", DdnsProviderSupport.queryEncode(request.getAddressText()))
                .replace("{token}", DdnsProviderSupport.queryEncode(token));
    }

    /** 请求头和正文上下文使用原值替换，随后仍会执行头换行验证。 */
    private String renderRaw(String template, DdnsUpdateRequest request) {
        return template
                .replace("{hostname}", request.getHostname())
                .replace("{ipv6}", request.getAddressText())
                .replace("{token}", token);
    }

    /**
     * 拒绝形如命名变量的未知占位符，避免用户误以为变量已生效。
     * JSON 对象本身的普通花括号不会被当作占位符。
     */
    private static void ensureKnownPlaceholders(String template) {
        Matcher matcher = NAMED_PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!"hostname".equals(name) && !"ipv6".equals(name) && !"token".equals(name)) {
                throw new IllegalArgumentException("自定义 API 模板包含未知占位符。");
            }
        }
    }

    /** 确认地址会实际进入请求，而不是只出现在成功响应匹配规则中。 */
    private boolean requestContainsIpv6Placeholder() {
        if (endpointTemplate.contains("{ipv6}") || bodyTemplate.contains("{ipv6}")) {
            return true;
        }
        for (String template : headerTemplates.values()) {
            if (template.contains("{ipv6}")) {
                return true;
            }
        }
        return false;
    }

    /** 限制配置模板长度，避免意外的大对象和请求。 */
    private static String limitTemplate(String value, int maximum, String displayName) {
        Objects.requireNonNull(value, displayName);
        if (value.length() > maximum || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(displayName + " 无效或过长。");
        }
        return value;
    }
}
