/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

import org.junit.Test;
import org.w3c.dom.Element;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

/** 静态回归覆盖设置及 Peer 导出导航约束；不声称完成真机点击或深浅显示验收。 */
public final class SettingsNavigationResourcesTest {
    private static Path mainPath(String relative) {
        Path path = Path.of("src/main", relative);
        if (!Files.isRegularFile(path)) path = Path.of("app/src/main", relative);
        assertTrue("Missing source resource: " + relative, Files.isRegularFile(path));
        return path;
    }

    private static String source(String name) throws Exception {
        return new String(Files.readAllBytes(mainPath("java/dev/lan/atticusvpn/server/ui/" + name + ".java")),
                StandardCharsets.UTF_8);
    }

    @Test public void sharedHeaderUsesAccessibleThemedLeftArrowWithoutVisibleBackText() throws Exception {
        String header = source("BackNavigation");
        assertTrue(header.contains("ImageButton back = new ImageButton(activity)"));
        assertTrue(header.contains("back.setImageResource(R.drawable.ic_settings_back)"));
        assertTrue(header.contains("back.setContentDescription(activity.getString(R.string.back))"));
        assertTrue(header.contains("back.setColorFilter(activity.getColor(R.color.black), PorterDuff.Mode.SRC_IN)"));
        assertTrue(header.contains("new LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48))"));
        assertTrue(header.contains("back.setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12))"));
        assertTrue(header.contains("backLayout.gravity = Gravity.START"));
        assertTrue(header.contains("back.setOnClickListener(view -> activity.finish())"));
        assertFalse(header.contains("back.setText("));
        assertFalse(header.contains("AtticusServerService"));
        assertTrue(source("SettingsPageActivity").contains("BackNavigation.addTo(this, content)"));
        assertFalse(source("SettingsPageActivity").contains("addButton(content, R.string.back)"));
    }

    @Test public void peerExportUsesTheSameArrowWithoutChangingExportControlsOrSensitiveLifecycle() throws Exception {
        String page = source("PeerExportActivity");
        assertTrue(page.contains("BackNavigation.addTo(this, content)"));
        assertFalse(page.contains("button(content, R.string.back)"));
        assertTrue(page.indexOf("BackNavigation.addTo(this, content)")
                < page.indexOf("getString(R.string.wg_export_title), 26"));
        // 返回只关闭当前页面，仍由已有生命周期释放配置/二维码，不直接停止服务器。
        assertTrue(page.contains("getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE)"));
        assertTrue(page.contains("if (originalSecureFlag == 0) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE)"));
        assertTrue(page.contains("foreground = false;"));
        assertTrue(page.contains("clearQr();"));
        assertTrue(page.contains("clearSnapshot();"));
        assertTrue(page.contains("if (pendingFile != null) { pendingFile.close(); pendingFile = null; }"));
        assertTrue(page.contains("image.setSaveEnabled(false)"));
        assertTrue(page.contains("view.setSaveEnabled(false)"));
        assertTrue(page.contains("R.string.wg_security_title"));
        assertTrue(page.contains("R.string.wg_gallery_warning"));
        assertTrue(page.contains("button(content, R.string.wg_save_conf)"));
        assertTrue(page.contains("button(content, R.string.wg_copy_config)"));
        assertFalse(page.contains("AtticusServerService"));
    }

    @Test public void settingsAndAllSecondLevelPagesUseTheSameHeader() throws Exception {
        for (String name : new String[] { "SettingsActivity", "LanguageSettingsActivity",
                "AppearanceSettingsActivity", "DeviceCapabilitiesActivity" }) {
            String page = source(name);
            assertTrue(name, page.contains("extends SettingsPageActivity"));
            assertTrue(name, page.contains("createPage("));
            assertFalse(name, page.contains("addButton(content, R.string.back)"));
        }
    }

    @Test public void arrowAssetIsSmallVectorAndMirrorsOnlyForRtl() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Element vector = factory.newDocumentBuilder().parse(mainPath("res/drawable/ic_settings_back.xml").toFile())
                .getDocumentElement();
        assertEquals("vector", vector.getTagName());
        assertEquals("24dp", vector.getAttribute("android:width"));
        assertEquals("24dp", vector.getAttribute("android:height"));
        assertEquals("true", vector.getAttribute("android:autoMirrored"));
        Element path = (Element) vector.getElementsByTagName("path").item(0);
        assertNotNull(path);
        assertTrue(path.getAttribute("android:pathData").contains("L4,12"));
    }
}
