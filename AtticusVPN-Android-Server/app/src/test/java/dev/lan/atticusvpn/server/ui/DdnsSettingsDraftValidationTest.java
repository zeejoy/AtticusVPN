/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.junit.Test;

/**
 * 直接调用真正的 SettingsDraft 校验，不实例化 Activity，不启动 Android 或网络。
 * 反射仅用于访问私有静态值对象，避免为测试放宽配置对象的生产可见性。
 */
public final class DdnsSettingsDraftValidationTest {
    @Test public void duckDnsShortDraftCanBeDisabledWithoutValidatingHiddenHostname()
            throws Exception {
        final Fixture fixture = new Fixture("duckdns");
        fixture.draft("ddnsHostname", "myvpn");
        fixture.draft("ddnsProvider", "disabled");
        final Object checked = fixture.validate();
        assertEquals("disabled", value(checked, "ddnsProvider"));
        assertEquals("stored.duckdns.org", value(checked, "ddnsHostname"));
        assertEquals("", value(checked, "ddnsSecret"));
    }

    @Test public void disabledIgnoresMalformedAndOversizedHiddenDrafts() throws Exception {
        final Fixture fixture = new Fixture("duckdns");
        fixture.draft("ddnsProvider", "disabled");
        fixture.draft("ddnsHostname", "not a valid hostname");
        fixture.draft("ddnsAccount", "x".repeat(257));
        fixture.draft("ddnsZone", "zone\ninvalid");
        fixture.draft("ddnsRecord", "record\0invalid");
        fixture.draft("ddnsCustomUrl", "x".repeat(8193));
        fixture.draft("newDdnsSecret", "x".repeat(4097));
        final Object checked = fixture.validate();
        assertEquals("stored.duckdns.org", value(checked, "ddnsHostname"));
        assertEquals("storedAccount", value(checked, "ddnsAccount"));
        assertEquals("storedZone", value(checked, "ddnsZone"));
        assertEquals("storedRecord", value(checked, "ddnsRecord"));
        assertEquals(Fixture.STORED_URL, value(checked, "ddnsCustomUrl"));
        assertEquals("", value(checked, "ddnsSecret"));
    }

    @Test public void duckDnsOnlyValidatesVisibleFieldsAndPreservesInactiveStoredValues()
            throws Exception {
        final Fixture fixture = new Fixture("duckdns");
        fixture.draft("ddnsHostname", "myvpn");
        fixture.draft("ddnsAccount", "x".repeat(257));
        fixture.draft("ddnsZone", "bad\nzone");
        fixture.draft("ddnsRecord", "bad\0record");
        fixture.draft("ddnsCustomUrl", "not an https URL\n");
        fixture.draft("newDdnsSecret", "newTestToken");
        final Object checked = fixture.validate();
        assertEquals("myvpn.duckdns.org", value(checked, "ddnsHostname"));
        assertEquals("newTestToken", value(checked, "ddnsSecret"));
        assertEquals("storedAccount", value(checked, "ddnsAccount"));
        assertEquals("storedZone", value(checked, "ddnsZone"));
        assertEquals("storedRecord", value(checked, "ddnsRecord"));
        assertEquals(Fixture.STORED_URL, value(checked, "ddnsCustomUrl"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void activeDuckDnsHostnameStillMustBeValid() throws Exception {
        final Fixture fixture = new Fixture("duckdns");
        fixture.draft("ddnsHostname", "bad host name");
        fixture.validate();
    }

    @Test public void cloudflareRequiresBothVisibleIdentifiers() throws Exception {
        for (String required : new String[]{"ddnsZone", "ddnsRecord"}) {
            final Fixture fixture = new Fixture("cloudflare");
            fixture.draft(required, "");
            fixture.expectInvalid();
        }
    }

    @Test public void noIpAndDynuRequireTheirVisibleAccount() throws Exception {
        for (String provider : new String[]{"noip", "dynu"}) {
            final Fixture fixture = new Fixture(provider);
            fixture.draft("ddnsAccount", "");
            fixture.expectInvalid();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void activeAccountDraftStillUsesBoundsAndControlCharacterValidation() throws Exception {
        final Fixture fixture = new Fixture("noip");
        fixture.draft("ddnsAccount", "bad\naccount");
        fixture.validate();
    }

    @Test(expected = IllegalArgumentException.class)
    public void activeCustomUrlStillRequiresHttpsAndIpv6Placeholder() throws Exception {
        final Fixture fixture = new Fixture("custom");
        fixture.draft("ddnsCustomUrl", "http://api.example/update?ip={ipv6}");
        fixture.validate();
    }

    @Test public void customCredentialRemainsOptional() throws Exception {
        final Fixture fixture = new Fixture("disabled");
        fixture.draft("ddnsProvider", "custom");
        fixture.draft("newDdnsSecret", "");
        final Object checked = fixture.validate();
        assertEquals("custom", value(checked, "ddnsProvider"));
        assertEquals("", value(checked, "ddnsSecret"));
    }

    @Test public void blankSameProviderCredentialPreservesSavedValue() throws Exception {
        final Fixture fixture = new Fixture("duckdns");
        fixture.draft("newDdnsSecret", "");
        assertEquals("storedSecret", value(fixture.validate(), "ddnsSecret"));
    }

    @Test public void changingProviderNeverReusesPreviousSavedSecret() throws Exception {
        for (String provider : new String[]{"duckdns", "cloudflare", "noip", "dynu", "freedns"}) {
            final Fixture fixture = new Fixture("disabled");
            fixture.draft("ddnsProvider", provider);
            fixture.draft("newDdnsSecret", "");
            fixture.expectInvalid();
        }
    }

    @Test public void unchangedDisabledProviderIgnoresHiddenCredentialDraft() throws Exception {
        final Fixture fixture = new Fixture("disabled");
        fixture.draft("newDdnsSecret", "hidden\ninvalid");
        // 同服务商空值保留的原保存语义保持，不采纳隐藏的新凭据。
        assertEquals("storedSecret", value(fixture.validate(), "ddnsSecret"));
    }

    @Test public void connectionPortAndMtuStillApplyIndependentlyOfDdnsVisibility()
            throws Exception {
        final Fixture fixture = new Fixture("disabled");
        fixture.draft("listenPort", 25565);
        fixture.draft("mtu", 1320);
        final Object checked = fixture.validate();
        assertEquals(25565, value(checked, "listenPort"));
        assertEquals(1320, value(checked, "mtu"));
    }

    @Test public void hiddenDdnsDoesNotBypassInvalidPortOrMtu() throws Exception {
        final Fixture port = new Fixture("disabled");
        port.draft("listenPort", 0);
        port.expectInvalid();
        final Fixture mtu = new Fixture("disabled");
        mtu.draft("mtu", 1421);
        mtu.expectInvalid();
    }

    private static Object value(final Object object, final String name) throws Exception {
        final Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void put(final Object object, final String name, final Object value)
            throws Exception {
        final Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static Object create(final String nestedName) throws Exception {
        final Class<?> type = Class.forName(
                "dev.lan.atticusvpn.server.ui.MainActivity$" + nestedName);
        final Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static final class Fixture {
        private static final String STORED_URL = "https://stored.example/update?ip={ipv6}";
        private final Object previous = create("SettingsValues");
        private final Object draft = create("SettingsDraft");

        private Fixture(final String provider) throws Exception {
            for (Object object : new Object[]{previous, draft}) {
                put(object, "endpointHost", "");
                put(object, "listenPort", 51820);
                put(object, "mtu", 1280);
                put(object, "dnsEnabled", true);
                put(object, "dnsMode", "AUTOMATIC");
                put(object, "customDns", "");
                put(object, "ddnsProvider", provider);
                put(object, "ddnsHostname", "stored.duckdns.org");
                put(object, "ddnsAccount", "storedAccount");
                put(object, "ddnsZone", "storedZone");
                put(object, "ddnsRecord", "storedRecord");
                put(object, "ddnsCustomUrl", STORED_URL);
            }
            put(previous, "ddnsSecret", "storedSecret");
            put(draft, "newDdnsSecret", "");
        }

        private void draft(final String name, final Object value) throws Exception {
            put(draft, name, value);
        }

        private Object validate() throws Exception {
            final Method validate = draft.getClass().getDeclaredMethod(
                    "validate", previous.getClass());
            validate.setAccessible(true);
            try {
                return validate.invoke(draft, previous);
            } catch (InvocationTargetException error) {
                final Throwable cause = error.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw new AssertionError("Unexpected validation failure", cause);
            }
        }

        private void expectInvalid() throws Exception {
            try {
                validate();
                fail("Visible provider field should still be validated");
            } catch (IllegalArgumentException expected) {
                // 固定异常码，不显示任何草稿文本或凭据。
            }
        }
    }
}
