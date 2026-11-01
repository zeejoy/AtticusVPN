/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.Assert.*;

/** 只检查公开副本的资源与源码，不声称完成真实 Launcher 遮罩或手势验收。 */
public final class LauncherAndPublicResourcesTest {
    private static Path mainPath(String relative) {
        Path path = Path.of("src/main", relative);
        if (!Files.isRegularFile(path)) path = Path.of("app/src/main", relative);
        assertTrue("Missing source resource: " + relative, Files.isRegularFile(path));
        return path;
    }

    private static Element xml(String relative) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(mainPath(relative).toFile()).getDocumentElement();
    }

    private static String text(String relative) throws Exception {
        return new String(Files.readAllBytes(mainPath(relative)), StandardCharsets.UTF_8);
    }

    @Test public void manifestSelectsBothLauncherVariantsWithoutReplacingNotificationIcon() throws Exception {
        Element application = (Element) xml("AndroidManifest.xml").getElementsByTagName("application").item(0);
        assertEquals("@mipmap/ic_launcher", application.getAttribute("android:icon"));
        assertEquals("@mipmap/ic_launcher", application.getAttribute("android:roundIcon"));
        assertTrue(Files.isRegularFile(mainPath("res/drawable/ic_atticus_shield.xml")));
        assertTrue(text("java/dev/lan/atticusvpn/server/service/AtticusServerService.java")
                .contains("R.drawable.ic_atticus_shield"));
    }

    @Test public void api24And25HaveLegacyBitmapIcon() throws Exception {
        Element legacy = xml("res/mipmap-anydpi/ic_launcher.xml");
        assertEquals("layer-list", legacy.getTagName());
        Element inset = (Element) legacy.getElementsByTagName("inset").item(0);
        for (String edge : List.of("Left", "Top", "Right", "Bottom")) {
            assertEquals("4%", inset.getAttribute("android:inset" + edge));
        }
        Element bitmap = (Element) legacy.getElementsByTagName("bitmap").item(0);
        assertEquals("@drawable/app_launcher_art", bitmap.getAttribute("android:src"));
        assertEquals("fill", bitmap.getAttribute("android:gravity"));
        Element white = (Element) legacy.getElementsByTagName("solid").item(0);
        assertEquals("#FFFFFF", white.getAttribute("android:color"));
    }

    @Test public void api26AdaptiveIconKeepsWhiteBackgroundAndInsetArtwork() throws Exception {
        Element adaptive = xml("res/mipmap-anydpi-v26/ic_launcher.xml");
        assertEquals("adaptive-icon", adaptive.getTagName());
        Element background = (Element) adaptive.getElementsByTagName("background").item(0);
        Element foreground = (Element) adaptive.getElementsByTagName("foreground").item(0);
        assertEquals("@android:color/white", background.getAttribute("android:drawable"));
        assertEquals("@drawable/launcher_foreground", foreground.getAttribute("android:drawable"));
        Element monochrome = (Element) adaptive.getElementsByTagName("monochrome").item(0);
        assertEquals("@drawable/launcher_monochrome", monochrome.getAttribute("android:drawable"));
        assertEquals("vector", xml("res/drawable/launcher_monochrome.xml").getTagName());
        Element inset = xml("res/drawable/launcher_foreground.xml");
        assertEquals("inset", inset.getTagName());
        for (String edge : List.of("Left", "Top", "Right", "Bottom")) {
            assertEquals("18%", inset.getAttribute("android:inset" + edge));
        }
        Element bitmap = (Element) inset.getElementsByTagName("bitmap").item(0);
        assertEquals("@drawable/app_launcher_art", bitmap.getAttribute("android:src"));
        assertEquals("fill", bitmap.getAttribute("android:gravity"));
    }

    @Test public void suppliedArtworkRemainsFullResolutionPng() throws Exception {
        byte[] png = Files.readAllBytes(mainPath("res/drawable-nodpi/app_launcher_art.png"));
        assertTrue(png.length > 24);
        assertArrayEquals(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10},
                java.util.Arrays.copyOf(png, 8));
        assertEquals("IHDR", new String(png, 12, 4, StandardCharsets.US_ASCII));
        ByteBuffer header = ByteBuffer.wrap(png).order(ByteOrder.BIG_ENDIAN);
        assertEquals(1254, header.getInt(16));
        assertEquals(1254, header.getInt(20));
    }

    @Test public void publicFootersUseAliasInAllLanguages() throws Exception {
        for (String directory : List.of("values", "values-en", "values-b+zh+Hant")) {
            NodeList strings = xml("res/" + directory + "/strings.xml").getElementsByTagName("string");
            String footer = null;
            for (int index = 0; index < strings.getLength(); index++) {
                Element value = (Element) strings.item(index);
                if ("footer".equals(value.getAttribute("name"))) footer = value.getTextContent();
            }
            assertNotNull(footer);
            assertTrue(footer.contains("TianzeL"));
            assertTrue(footer.contains("AtticusVPN %1$s"));
        }
    }

    @Test public void terminalLintFixesKeepApi24BreakStrategyAndScrollDelegation() throws Exception {
        String terminal = text("java/dev/lan/atticusvpn/server/ui/TerminalStatusView.java");
        assertTrue(terminal.contains("text.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)"));
        assertTrue(terminal.contains("setCompatibleSimpleBreakStrategy(text)"));
        assertTrue(terminal.contains("private static void setCompatibleSimpleBreakStrategy(TextView text)"));
        assertTrue(terminal.contains("@Override public boolean performClick()"));
        assertTrue(terminal.contains("return super.performClick()"));
        assertTrue(terminal.contains("if (!touchMoved &&"));
        assertTrue(terminal.contains("case MotionEvent.ACTION_CANCEL: endTouch(); break;"));
        assertTrue(terminal.contains("return super.onTouchEvent(event)"));
        assertTrue(terminal.contains("requestDisallowInterceptTouchEvent(canScrollVertically"));
        String activity = text("java/dev/lan/atticusvpn/server/ui/MainActivity.java");
        assertFalse(activity.contains("(Object[]) markers"));
        assertTrue(activity.contains("markers[4], markers[5], markers[6], markers[7]"));
    }
}
