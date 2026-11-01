/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.core;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 验证面向用户的固定错误消息简短、唯一且不包含密钥字段名称。 */
public final class SafeErrorCodeTest {
    @Test
    public void everyCodeHasShortFixedMessageWithoutSecretFields() {
        final Set<String> messages = new HashSet<>();
        for (final SafeErrorCode code : SafeErrorCode.values()) {
            final String message = code.userMessage();
            assertFalse(message.isBlank());
            assertTrue(message.length() < 100);
            assertFalse(message.contains("PrivateKey"));
            assertFalse(message.contains("PresharedKey"));
            assertTrue(messages.add(message));
        }
    }
}
