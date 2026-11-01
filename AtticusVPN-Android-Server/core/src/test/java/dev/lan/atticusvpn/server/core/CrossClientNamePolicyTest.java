/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.core;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public final class CrossClientNamePolicyTest {
    @Test
    public void acceptsIntersectionNameAtMaximumLength() {
        assertTrue(CrossClientNamePolicy.isValid("A123456789_abc-"));
    }

    @Test
    public void rejectsLongUnsafeOrNonLetterLeadingNames() {
        assertFalse(CrossClientNamePolicy.isValid("A123456789_abc-x"));
        assertFalse(CrossClientNamePolicy.isValid("1phone"));
        assertFalse(CrossClientNamePolicy.isValid("phone.one"));
        assertFalse(CrossClientNamePolicy.isValid("手机"));
    }

    @Test
    public void rejectsWindowsReservedDeviceNames() {
        assertFalse(CrossClientNamePolicy.isValid("CON"));
        assertFalse(CrossClientNamePolicy.isValid("com1"));
        assertFalse(CrossClientNamePolicy.isValid("LPT9"));
        assertTrue(CrossClientNamePolicy.isValid("CON-phone"));
    }

    @Test
    public void duplicateCheckIsCaseInsensitive() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CrossClientNamePolicy.requireNew("Phone", List.of("phone")));
    }
}
