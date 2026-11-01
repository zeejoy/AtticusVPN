/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.storage;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

import org.junit.Test;

/** 验证无主保险库只按严格文件名与已提交状态白名单删除。 */
public final class PeerExportVaultTest {
    private static final String KEPT = "11111111111111111111111111111111";
    private static final String ORPHAN = "22222222222222222222222222222222";

    @Test
    public void findsOnlyStrictOrphanFileNames() {
        final Set<String> result = PeerExportVault.orphanIds(
                Arrays.asList(
                        "peer-" + KEPT + ".export.enc",
                        "peer-" + ORPHAN + ".export.enc",
                        "server-state.enc",
                        "peer-../../bad.export.enc"),
                Collections.singleton(KEPT));

        assertEquals(Collections.singleton(ORPHAN), result);
    }
}
