/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.runtime;

/** 仅携带固定安全错误码，故意不链接可能含密钥或系统路径的底层异常。 */
public final class NativeEngineException extends Exception {
    private final String safeCode;

    public NativeEngineException(final String safeCode) {
        super(safeCode);
        this.safeCode = safeCode;
    }

    public String safeCode() {
        return safeCode;
    }
}
