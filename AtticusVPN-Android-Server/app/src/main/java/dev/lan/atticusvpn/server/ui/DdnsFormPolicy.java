/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.ui;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * DDNS 表单的纯本地显示规则。选择服务商不保存设置，也不触发网络或服务调用。
 *
 * <p>标识与持久配置一致，不依赖翻译后的服务商名称或 Spinner 的显示文字。</p>
 */
public final class DdnsFormPolicy {
    public enum Field { HOSTNAME, ACCOUNT, CREDENTIAL, ZONE_ID, RECORD_ID, CUSTOM_URL }

    private DdnsFormPolicy() { }

    /** 关闭时不显示具体字段；其他服务商只显示当前 API 所需的输入项。 */
    public static Set<Field> fields(final String provider) {
        final EnumSet<Field> fields;
        switch (provider) {
            case "disabled":
                fields = EnumSet.noneOf(Field.class);
                break;
            case "duckdns":
            case "freedns":
                fields = EnumSet.of(Field.HOSTNAME, Field.CREDENTIAL);
                break;
            case "cloudflare":
                fields = EnumSet.of(Field.HOSTNAME, Field.CREDENTIAL,
                        Field.ZONE_ID, Field.RECORD_ID);
                break;
            case "noip":
            case "dynu":
                fields = EnumSet.of(Field.HOSTNAME, Field.ACCOUNT, Field.CREDENTIAL);
                break;
            case "custom":
                fields = EnumSet.of(Field.HOSTNAME, Field.CREDENTIAL, Field.CUSTOM_URL);
                break;
            default:
                throw new IllegalArgumentException("INVALID_DDNS_PROVIDER");
        }
        return Collections.unmodifiableSet(fields);
    }

    public static boolean usesDuckDnsHostnameHint(final String provider) {
        fields(provider);
        return "duckdns".equals(provider);
    }

    public static boolean credentialIsOptional(final String provider) {
        fields(provider);
        return "custom".equals(provider);
    }

    /**
     * 仅保存本 Activity 内尚未提交的凭据草稿，避免换服务商时错用上一家 Token。
     * 不接收已保存的凭据，不序列化、不写 Bundle、磁盘或日志；Activity 销毁后丢弃。
     */
    public static final class CredentialDrafts {
        private final Map<String, String> drafts = new HashMap<>();
        private String selectedProvider = "disabled";

        public String select(final String provider, final String currentDraft) {
            fields(provider);
            drafts.put(selectedProvider, currentDraft == null ? "" : currentDraft);
            selectedProvider = provider;
            final String draft = drafts.get(provider);
            return draft == null ? "" : draft;
        }

        /** 只有显式加载/保存配置后重置草稿，不从持久状态读出 Token 填入输入框。 */
        public void reset(final String provider) {
            fields(provider);
            drafts.clear();
            selectedProvider = provider;
        }

        public void clear() {
            drafts.clear();
            selectedProvider = "disabled";
        }
    }
}
