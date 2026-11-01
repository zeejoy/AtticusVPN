/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.Assert.*;
import dev.lan.atticusvpn.server.runtime.ServerNetworkProbe;

/** 无模拟 Root / 网络结果；这里只验证三语资源与存储语言码的约束。 */
public final class LocalizationResourcesTest {
    private static Path mainPath(String relative) {
        Path path = Path.of("src/main", relative);
        if (!Files.isRegularFile(path)) path = Path.of("app/src/main", relative);
        assertTrue("Missing source resource: " + relative, Files.isRegularFile(path));
        return path;
    }

    private static Map<String, String> strings(String directory) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        NodeList nodes = factory.newDocumentBuilder().parse(mainPath("res/" + directory + "/strings.xml").toFile()).getElementsByTagName("string");
        Map<String, String> values = new HashMap<>();
        for (int index = 0; index < nodes.getLength(); index++) {
            Element element = (Element) nodes.item(index);
            if ("false".equals(element.getAttribute("translatable"))) continue;
            String name = element.getAttribute("name");
            assertNull("Duplicate resource: " + name, values.put(name, element.getTextContent()));
        }
        return values;
    }

    private static List<String> placeholders(String value) {
        Matcher matcher = Pattern.compile("%[0-9]+\\$[sdf]").matcher(value);
        List<String> result = new ArrayList<>();
        while (matcher.find()) result.add(matcher.group());
        result.sort(String::compareTo);
        return result;
    }

    private static List<String> templatePlaceholders(String value) {
        Matcher matcher = Pattern.compile("\\{(?:ipv6|hostname|token)\\}").matcher(value);
        List<String> result = new ArrayList<>();
        while (matcher.find()) result.add(matcher.group());
        result.sort(String::compareTo);
        return result;
    }

    @Test public void englishCoversEveryTranslatableResource() throws Exception {
        Map<String, String> chinese = strings("values");
        Map<String, String> english = strings("values-en");
        assertEquals(chinese.keySet(), english.keySet());
        for (String key : chinese.keySet()) {
            assertFalse("Empty translation: " + key, english.get(key).isBlank());
            assertEquals("Formatting arguments differ: " + key,
                    placeholders(chinese.get(key)), placeholders(english.get(key)));
            assertEquals("DDNS template arguments differ: " + key,
                    templatePlaceholders(chinese.get(key)), templatePlaceholders(english.get(key)));
        }
    }

    @Test public void traditionalChineseCoversEveryTranslatableResource() throws Exception {
        Map<String, String> chinese = strings("values");
        Map<String, String> traditional = strings("values-b+zh+Hant");
        assertEquals(chinese.keySet(), traditional.keySet());
        for (String key : chinese.keySet()) {
            assertFalse("Empty Traditional Chinese translation: " + key,
                    traditional.get(key).isBlank());
            assertEquals("Traditional Chinese formatting arguments differ: " + key,
                    placeholders(chinese.get(key)), placeholders(traditional.get(key)));
            assertEquals("Traditional Chinese DDNS template arguments differ: " + key,
                    templatePlaceholders(chinese.get(key)), templatePlaceholders(traditional.get(key)));
        }
    }

    @Test public void unsupportedLanguageValuesDefaultToEnglish() {
        assertEquals("en", AppLanguage.normalize(null));
        assertEquals("en", AppLanguage.normalize(""));
        assertEquals("en", AppLanguage.normalize(" "));
        assertEquals("en", AppLanguage.normalize("fr"));
        assertEquals("en", AppLanguage.normalize("invalid"));
        assertEquals("zh-CN", AppLanguage.normalize("zh-CN"));
        assertEquals("en", AppLanguage.normalize("en"));
        assertEquals("en", AppLanguage.normalize("en-US"));
    }

    @Test public void chineseLocaleScriptAndRegionArePreserved() {
        assertEquals("zh-CN", AppLanguage.normalize("zh"));
        assertEquals("zh-CN", AppLanguage.normalize("zh-CN"));
        assertEquals("zh-CN", AppLanguage.normalize("zh-SG"));
        assertEquals("zh-CN", AppLanguage.normalize("zh-Hans"));
        assertEquals("zh-CN", AppLanguage.normalize("zh-Hans-TW"));
        assertEquals("zh-TW", AppLanguage.normalize("zh-TW"));
        assertEquals("zh-TW", AppLanguage.normalize("zh-HK"));
        assertEquals("zh-TW", AppLanguage.normalize("zh-MO"));
        assertEquals("zh-TW", AppLanguage.normalize("zh-Hant"));
        assertEquals("zh-TW", AppLanguage.normalize("zh-Hant-CN"));
        assertEquals("zh-TW", AppLanguage.normalize("zh_Hant_TW"));
    }

    @Test public void languagePageLabelsKeepLanguageAndNativeChineseWords() throws Exception {
        assertEquals("Language/语言", strings("values").get("language"));
        assertEquals("Language/語言", strings("values-b+zh+Hant").get("language"));
        assertEquals("Language", strings("values-en").get("language"));
        String languageSource = new String(Files.readAllBytes(mainPath(
                "java/dev/lan/atticusvpn/server/ui/LanguageSettingsActivity.java")), StandardCharsets.UTF_8);
        assertTrue(languageSource.contains("R.string.language_traditional_chinese"));
        assertTrue(languageSource.contains("AppLanguage.TRADITIONAL_CHINESE"));
        String localeSource = new String(Files.readAllBytes(mainPath("res/xml/locales_config.xml")),
                StandardCharsets.UTF_8);
        assertTrue(localeSource.contains("android:name=\"zh-CN\""));
        assertTrue(localeSource.contains("android:name=\"zh-TW\""));
        assertTrue(localeSource.contains("android:name=\"en\""));
        String appLanguage = new String(Files.readAllBytes(mainPath(
                "java/dev/lan/atticusvpn/server/ui/AppLanguage.java")), StandardCharsets.UTF_8);
        assertTrue(appLanguage.contains("get(0).toLanguageTag()"));
        assertFalse(appLanguage.contains("get(0).getLanguage()"));
    }

    @Test public void englishNetworkAndRootWarningsRemainQualified() throws Exception {
        Map<String, String> english = strings("values-en");
        assertTrue(english.get("ikev2_dataplane_unavailable").contains("unverified"));
        assertTrue(english.get("port_no_conflict_observed").contains("binding has not been tested"));
        assertTrue(english.get("check_passed").contains("client’s own results"));
        assertTrue(english.get("port_help").contains("51820"));
        assertTrue(english.get("port_help").contains("existing ports are preserved"));
        assertTrue(english.get("root_dialog_message").contains("Does not change routes"));
    }

    @Test public void fourWebsiteAndIndependentDnsWarningsExistInAllLanguages() throws Exception {
        Method probeHosts = ServerNetworkProbe.class.getDeclaredMethod("probeHosts");
        probeHosts.setAccessible(true);
        assertArrayEquals(new String[] { "www.cloudflare.com", "www.microsoft.com",
                "www.baidu.com", "www.bilibili.com" }, (String[]) probeHosts.invoke(null));
        for (String directory : List.of("values", "values-en", "values-b+zh+Hant")) {
            Map<String, String> resources = strings(directory);
            // 检测主机从实际实现取得；不为测试保留已不显示的说明资源。
            for (String key : List.of("check_passed", "check_passed_dns_disabled", "check_dns_failed")) {
                assertFalse(directory + " still includes the removed website detail: " + key,
                        resources.get(key).contains("IPv4 HTTPS"));
            }
            assertTrue(resources.get("check_dns_failed").contains("DNS"));
            assertTrue(resources.get("check_passed_dns_disabled").contains("DNS"));
            assertTrue(resources.get("port_help").contains("51820"));
            assertFalse(resources.get("port_help").contains("25565"));
        }
        assertTrue(strings("values-en").get("check_dns_failed")
                .startsWith("Phone Internet check passed"));
        assertTrue(strings("values").get("check_dns_failed").startsWith("手机出口检测通过"));
        assertTrue(strings("values-b+zh+Hant").get("check_dns_failed")
                .startsWith("手機出口檢測通過"));
    }

    @Test public void ddnsFieldHelpIsLocalizedAndSpecificToTheSelectedProvider() throws Exception {
        for (String directory : List.of("values", "values-en", "values-b+zh+Hant")) {
            Map<String, String> resources = strings(directory);
            assertTrue(resources.get("ddns_hostname_help").contains("https://"));
            assertFalse(resources.get("ddns_hostname_help").contains("DuckDNS"));
            assertTrue(resources.get("ddns_hostname_help_duckdns").contains("DuckDNS"));
            assertTrue(resources.get("ddns_hostname_help_duckdns").contains("subdomain"));
            assertTrue(resources.get("ddns_account_label_noip").contains("No-IP"));
            assertTrue(resources.get("ddns_account_help_noip").contains("DDNS Key"));
            assertFalse(resources.get("ddns_account_help_noip").contains("Dynu"));
            assertTrue(resources.get("ddns_account_label_dynu").contains("Dynu"));
            assertFalse(resources.get("ddns_account_help_dynu").contains("No-IP"));
            for (String provider : List.of("duckdns", "cloudflare", "noip", "dynu", "freedns", "custom")) {
                assertFalse(resources.get("ddns_credential_label_" + provider).isBlank());
                String help = resources.get("ddns_credential_help_" + provider);
                assertFalse(help.isBlank());
                assertTrue(directory + " missing saved-credential guidance for " + provider,
                        help.contains(directory.equals("values-en") ? "leave blank" : "留空"));
            }
            assertTrue(resources.get("ddns_credential_help_cloudflare").contains("API Token"));
            assertTrue(resources.get("ddns_credential_help_cloudflare").contains("API Key"));
            assertTrue(resources.get("ddns_record_id_help").contains("AAAA"));
            assertTrue(resources.get("ddns_credential_help_freedns").contains("URL"));
            assertEquals(List.of("{token}"),
                    templatePlaceholders(resources.get("ddns_credential_help_custom")));
            assertTrue(resources.get("ddns_custom_url_help").contains("HTTPS GET"));
            assertEquals(List.of("{hostname}", "{ipv6}", "{token}"),
                    templatePlaceholders(resources.get("ddns_custom_url_help")));
        }
        assertTrue(strings("values").get("ddns_hostname_help_duckdns")
                .contains("DuckDNS 可只填 subdomain"));
        assertTrue(strings("values-b+zh+Hant").get("ddns_hostname_help_duckdns")
                .contains("DuckDNS 可只填 subdomain"));
    }

    @Test public void translatedSpinnerLabelsAreNotSavedAsProtocolValues() throws Exception {
        String source = new String(Files.readAllBytes(mainPath("java/dev/lan/atticusvpn/server/ui/MainActivity.java")), StandardCharsets.UTF_8);
        assertTrue(source.contains("DNS_MODES[activity.dnsModeField.getSelectedItemPosition()]"));
        assertTrue(source.contains("DDNS_PROVIDERS[activity.ddnsProviderField.getSelectedItemPosition()]"));
        assertFalse(source.contains("(String) activity.dnsModeField.getSelectedItem()"));
        assertFalse(source.contains("(String) activity.ddnsProviderField.getSelectedItem()"));
    }

    @Test public void settingsDoesNotStartStopOrReloadTheServer() throws Exception {
        String source = new String(Files.readAllBytes(mainPath("java/dev/lan/atticusvpn/server/ui/SettingsActivity.java")), StandardCharsets.UTF_8);
        assertFalse(source.contains("AtticusServerService"));
        assertFalse(source.contains("requestRoot"));
        String languageSource = new String(Files.readAllBytes(mainPath("java/dev/lan/atticusvpn/server/ui/LanguageSettingsActivity.java")), StandardCharsets.UTF_8);
        assertFalse(languageSource.contains("AtticusServerService"));
        assertFalse(languageSource.contains("requestRoot"));
        assertTrue(languageSource.contains("AppLanguage.select"));
    }
}
