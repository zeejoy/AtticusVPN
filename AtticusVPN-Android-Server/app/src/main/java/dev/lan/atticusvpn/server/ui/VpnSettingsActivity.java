/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

/** Reuses the original form, validator and atomic save/reload transactions. */
public final class VpnSettingsActivity extends MainActivity {
    public static final String EXTRA_SECTION="atticus_settings_section";
    @Override protected boolean isVpnSettings(){return true;}
}
