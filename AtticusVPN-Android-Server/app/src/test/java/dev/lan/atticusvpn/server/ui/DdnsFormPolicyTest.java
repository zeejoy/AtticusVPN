/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;

import org.junit.Test;

/** 不启动 Android、服务或网络，检查表单范围与尚未提交的凭据草稿隔离。 */
public final class DdnsFormPolicyTest {
    @Test public void disabledShowsOnlyProviderSelector() {
        assertTrue(DdnsFormPolicy.fields("disabled").isEmpty());
    }

    @Test public void duckDnsShowsHostnameAndCredentialOnly() {
        assertEquals(EnumSet.of(DdnsFormPolicy.Field.HOSTNAME, DdnsFormPolicy.Field.CREDENTIAL),
                DdnsFormPolicy.fields("duckdns"));
    }

    @Test public void cloudflareShowsItsTwoRecordIdentifiersOnlyHere() {
        assertEquals(EnumSet.of(DdnsFormPolicy.Field.HOSTNAME, DdnsFormPolicy.Field.CREDENTIAL,
                        DdnsFormPolicy.Field.ZONE_ID, DdnsFormPolicy.Field.RECORD_ID),
                DdnsFormPolicy.fields("cloudflare"));
    }

    @Test public void noIpShowsHostnameAccountAndCredentialOnly() {
        assertEquals(EnumSet.of(DdnsFormPolicy.Field.HOSTNAME, DdnsFormPolicy.Field.ACCOUNT,
                        DdnsFormPolicy.Field.CREDENTIAL), DdnsFormPolicy.fields("noip"));
    }

    @Test public void dynuShowsHostnameAccountAndCredentialOnly() {
        assertEquals(EnumSet.of(DdnsFormPolicy.Field.HOSTNAME, DdnsFormPolicy.Field.ACCOUNT,
                        DdnsFormPolicy.Field.CREDENTIAL), DdnsFormPolicy.fields("dynu"));
    }

    @Test public void freeDnsShowsHostnameAndCredentialOnly() {
        assertEquals(EnumSet.of(DdnsFormPolicy.Field.HOSTNAME, DdnsFormPolicy.Field.CREDENTIAL),
                DdnsFormPolicy.fields("freedns"));
    }

    @Test public void customShowsItsUrlAndOptionalCredential() {
        assertEquals(EnumSet.of(DdnsFormPolicy.Field.HOSTNAME, DdnsFormPolicy.Field.CREDENTIAL,
                        DdnsFormPolicy.Field.CUSTOM_URL), DdnsFormPolicy.fields("custom"));
        assertTrue(DdnsFormPolicy.credentialIsOptional("custom"));
    }

    @Test public void duckDnsHintNeverAppearsForAnotherProvider() {
        for (String provider : new String[]{"disabled", "cloudflare", "noip", "dynu",
                "freedns", "custom"}) {
            assertFalse(DdnsFormPolicy.usesDuckDnsHostnameHint(provider));
        }
        assertTrue(DdnsFormPolicy.usesDuckDnsHostnameHint("duckdns"));
    }

    @Test public void allEnabledNonCustomProvidersRequireCredential() {
        for (String provider : new String[]{"duckdns", "cloudflare", "noip", "dynu", "freedns"}) {
            assertFalse(DdnsFormPolicy.credentialIsOptional(provider));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void translatedOrUnknownNameCannotChangeProviderMapping() {
        DdnsFormPolicy.fields("Cloudflare");
    }

    @Test(expected = UnsupportedOperationException.class)
    public void returnedVisibilityRulesAreImmutable() {
        DdnsFormPolicy.fields("duckdns").add(DdnsFormPolicy.Field.ZONE_ID);
    }

    @Test public void credentialDraftDoesNotCrossProvidersAndReturnsWithoutLoss() {
        final DdnsFormPolicy.CredentialDrafts drafts = new DdnsFormPolicy.CredentialDrafts();
        drafts.reset("duckdns");
        assertEquals("", drafts.select("cloudflare", "test-duck-draft"));
        assertEquals("test-duck-draft", drafts.select("duckdns", "test-cloud-draft"));
        assertEquals("test-cloud-draft", drafts.select("cloudflare", "test-duck-draft"));
    }

    @Test public void sameProviderCallbackPreservesCurrentCredentialDraft() {
        final DdnsFormPolicy.CredentialDrafts drafts = new DdnsFormPolicy.CredentialDrafts();
        drafts.reset("duckdns");
        assertEquals("test-only", drafts.select("duckdns", "test-only"));
        assertEquals("", drafts.select("duckdns", ""));
    }

    @Test public void disablingDoesNotDestroyTypedDraftButNeverShowsIt() {
        final DdnsFormPolicy.CredentialDrafts drafts = new DdnsFormPolicy.CredentialDrafts();
        drafts.reset("duckdns");
        assertEquals("", drafts.select("disabled", "test-only"));
        assertEquals("test-only", drafts.select("duckdns", ""));
    }

    @Test public void explicitRefreshResetsAllUnsubmittedCredentials() {
        final DdnsFormPolicy.CredentialDrafts drafts = new DdnsFormPolicy.CredentialDrafts();
        drafts.reset("duckdns");
        drafts.select("cloudflare", "test-only");
        drafts.reset("duckdns");
        assertEquals("", drafts.select("cloudflare", ""));
        assertEquals("", drafts.select("duckdns", ""));
        drafts.clear();
        assertEquals("", drafts.select("duckdns", ""));
    }

    @Test public void spinnerSelectionOnlyChangesLocalPresentationAndDrafts() throws Exception {
        final String source = mainActivitySource();
        final String selection = method(source, "private void onDdnsProviderSelected()",
                "private void drawPeers");
        assertTrue(selection.contains("updateDdnsForm(provider)"));
        assertFalse(selection.contains("saveSettings("));
        assertFalse(selection.contains("refreshState("));
        assertFalse(selection.contains("AtticusServerService."));
        assertFalse(selection.contains("repository("));
        assertFalse(selection.contains("requestTerminalRefresh("));
        assertFalse(selection.contains("submitWorker("));
    }

    @Test public void ddnsRestorationDisabledAndHelpPrecedesEditBox() throws Exception {
        final String source = mainActivitySource();
        final String helper = method(source, "private DdnsFieldRow addDdnsField(",
                "private Spinner spinner(");
        assertTrue(helper.contains("field.setSaveEnabled(false)"));
        assertTrue(helper.contains("field.setSaveFromParentEnabled(false)"));
        assertTrue(helper.indexOf("container.addView(label")
                < helper.indexOf("container.addView(help"));
        assertTrue(helper.indexOf("container.addView(help")
                < helper.indexOf("container.addView(field"));
        assertTrue(source.contains("ddnsProviderField.setSaveEnabled(false)"));
        assertTrue(source.contains("ddnsCredentialDrafts.clear()"));
    }

    @Test public void savedProviderDisplaysFieldsAndStoredSecretIsNeverPrefilled() throws Exception {
        final String source = mainActivitySource();
        final String apply = method(source, "private void applySettings(",
                "private void onDdnsProviderSelected()");
        assertTrue(apply.contains("ddnsSecretField.setText(\"\")"));
        assertFalse(apply.contains("ddnsSecretField.setText(values.ddnsSecret)"));
        assertTrue(apply.contains("ddnsCredentialDrafts.reset(values.ddnsProvider)"));
        assertTrue(apply.contains("updateDdnsForm(values.ddnsProvider)"));
        // 原来明确保存的同服务商空值保留逻辑不变；切换服务商不复用保存的凭据。
        assertTrue(source.contains("submittedSecret.isEmpty() && providerUnchanged"));
        assertTrue(source.contains("? previous.ddnsSecret"));
    }

    private static String method(final String source, final String start, final String end) {
        final int beginning = source.indexOf(start);
        final int ending = source.indexOf(end, beginning + start.length());
        assertTrue("Expected source method boundaries", beginning >= 0 && ending > beginning);
        return source.substring(beginning, ending);
    }

    private static String mainActivitySource() throws Exception {
        Path source = Paths.get("src/main/java/dev/lan/atticusvpn/server/ui/MainActivity.java");
        if (!Files.isRegularFile(source)) {
            source = Paths.get("app/src/main/java/dev/lan/atticusvpn/server/ui/MainActivity.java");
        }
        assertTrue("Missing MainActivity source", Files.isRegularFile(source));
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }
}
