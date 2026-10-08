package com.firstham.aethergui;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

/**
 * dev.020 resource/layout regression coverage: the nine dev.020 changes as testable contracts.
 */
public final class Dev020ResourcesTest {
    private static Document xml(String path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new File("src/main/res/" + path));
    }

    private static Map<String, Element> ids(String path) throws Exception {
        Map<String, Element> map = new HashMap<>();
        NodeList nodes = xml(path).getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            String id = element.getAttribute("android:id");
            if (!id.isEmpty()) assertNull("duplicate widget " + id, map.put(id, element));
        }
        return map;
    }

    // --- CHANGE 3: MASQUE connection method on the primary Configurations page -----------------

    @Test public void masqueMethodSitsOnPrimaryPageBetweenProtocolAndScanMode() throws Exception {
        Map<String, Element> map = ids("layout/activity_main.xml");
        Element general = map.get("@+id/configuration_general_body");
        Element transport = map.get("@+id/transport_layout");
        assertNotNull("transport selector must exist", transport);
        // It lives inside the primary (general) section...
        Node parent = transport.getParentNode();
        while (parent != null && parent != general) parent = parent.getParentNode();
        assertSame("the MASQUE connection method must be on the primary Configurations page", general, parent);
        // ...directly between the Protocol selector and the Scan Mode selector.
        Element protocol = map.get("@+id/protocol_layout");
        Element scan = map.get("@+id/scan_layout");
        assertTrue((protocol.compareDocumentPosition(transport) & Node.DOCUMENT_POSITION_FOLLOWING) != 0);
        assertTrue((transport.compareDocumentPosition(scan) & Node.DOCUMENT_POSITION_FOLLOWING) != 0);
        // Exactly ONE transport selector exists (no duplicate control left elsewhere).
        int transportInputs = 0;
        NodeList all = xml("layout/activity_main.xml").getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            if ("@+id/transport_input".equals(((Element) all.item(i)).getAttribute("android:id"))) transportInputs++;
        }
        assertEquals("no duplicate MASQUE method control", 1, transportInputs);
    }

    @Test public void transportOptionsHaveNoRecommendedSuffixInEitherLocale() throws Exception {
        for (String locale : new String[]{"values", "values-fa"}) {
            NodeList arrays = xml(locale + "/arrays.xml").getElementsByTagName("string-array");
            for (int i = 0; i < arrays.getLength(); i++) {
                Element array = (Element) arrays.item(i);
                if (!"transport_labels".equals(array.getAttribute("name"))) continue;
                NodeList items = array.getElementsByTagName("item");
                assertEquals(2, items.getLength());
                // Exactly the two allowed labels, in the stable order 0=HTTP/3, 1=HTTP/2.
                assertEquals("HTTP/3 (QUIC)", items.item(0).getTextContent());
                assertEquals("HTTP/2 (TCP)", items.item(1).getTextContent());
            }
            // No "(recommended)"/"پیشنهادی" anywhere in the sections/help resources for the
            // MASQUE method either.
            for (String file : new String[]{"configuration_sections.xml"}) {
                String text = xml(locale + "/" + file).getDocumentElement().getTextContent();
                assertFalse(locale + "/" + file + " must not say recommended",
                        text.contains("recommended"));
            }
        }
    }

    // --- CHANGE 4/5: the single-open accordion and the removed subtitle ------------------------

    @Test public void moreSettingsSubtitleIsFullyRemovedInBothLocales() throws Exception {
        // The subtitle string key itself is gone from both locales and the layout no longer
        // contains the summary TextView under the More Settings heading.
        for (String locale : new String[]{"values", "values-fa"}) {
            NodeList strings = xml(locale + "/configuration_sections.xml").getElementsByTagName("string");
            for (int i = 0; i < strings.getLength(); i++) {
                assertNotEquals("more_settings_summary must be removed",
                        "more_settings_summary", ((Element) strings.item(i)).getAttribute("name"));
            }
        }
        // No view in the layout references the removed string.
        NodeList nodes = xml("layout/activity_main.xml").getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            assertNotEquals("@string/more_settings_summary", ((Element) nodes.item(i)).getAttribute("android:text"));
        }
    }

    @Test public void everyMoreSettingsSectionStartsCollapsedAndVisibilityIsGone() throws Exception {
        Map<String, Element> map = ids("layout/activity_main.xml");
        String[] sections = {"advanced", "privacy", "routing", "proxy", "protocol", "organization", "help"};
        for (String section : sections) {
            Element body = map.get("@+id/configuration_" + section + "_body");
            assertNotNull(body);
            assertEquals("section " + section + " must start collapsed (gone)", "gone", body.getAttribute("android:visibility"));
        }
    }

    // --- CHANGE 9: the Home TIME row -----------------------------------------------------------

    @Test public void homeTimeRowSitsBetweenPingAndLocation() throws Exception {
        Map<String, Element> map = ids("layout/activity_main.xml");
        Element ping = map.get("@+id/ping_value");
        Element timeRow = map.get("@+id/time_row");
        Element location = map.get("@+id/location_layout");
        assertNotNull(ping);
        assertNotNull("the TIME row must exist", timeRow);
        assertNotNull(location);
        assertTrue("TIME must be directly below Ping",
                (ping.compareDocumentPosition(timeRow) & Node.DOCUMENT_POSITION_FOLLOWING) != 0);
        assertTrue("TIME must be directly above LOCATION",
                (timeRow.compareDocumentPosition(location) & Node.DOCUMENT_POSITION_FOLLOWING) != 0);
        // Hidden by default (visible only while Connected).
        assertEquals("gone", timeRow.getAttribute("android:visibility"));
        // The value uses the theme's time accent (not an inline hardcoded color).
        Element value = map.get("@+id/time_value");
        assertEquals("@color/time_accent", value.getAttribute("android:textColor"));
        // The label matches the label style used by Ping/LOCATION: muted + 11sp + 0.18 spacing.
        NodeList children = timeRow.getChildNodes();
        Element label = null;
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element && ((Element) child).getAttribute("android:text").equals("@string/time_label")) {
                label = (Element) child;
            }
        }
        assertNotNull(label);
        assertEquals("@color/muted", label.getAttribute("android:textColor"));
        assertEquals("11sp", label.getAttribute("android:textSize"));
        assertEquals("0.18", label.getAttribute("android:letterSpacing"));
        // TIME label strings exist in both locales.
        for (String locale : new String[]{"values", "values-fa"}) {
            NodeList strings = xml(locale + "/strings.xml").getElementsByTagName("string");
            boolean found = false;
            for (int i = 0; i < strings.getLength(); i++) {
                if ("time_label".equals(((Element) strings.item(i)).getAttribute("name"))) found = true;
            }
            assertTrue(locale + " missing time_label", found);
        }
    }

    @Test public void timeAccentThemeResourcesExistForLightAndDark() throws Exception {
        Map<String, String> light = colorMap("values/colors.xml");
        Map<String, String> dark = colorMap("values-night/colors.xml");
        assertTrue(light.containsKey("time_accent"));
        assertTrue(dark.containsKey("time_accent"));
        // Light: the suggested #7C6FE8; dark: a lighter equivalent of the same family.
        assertEquals("#7C6FE8", light.get("time_accent").toUpperCase());
        assertNotEquals(light.get("time_accent"), dark.get("time_accent"));
    }

    // --- CHANGE 8: the Show system apps control ------------------------------------------------

    @Test public void appPickerHasShowSystemAppsSwitchDefaultOff() throws Exception {
        Map<String, Element> map = ids("layout/activity_app_selection.xml");
        Element toggle = map.get("@+id/show_system_apps_switch");
        assertNotNull("the Show system apps switch must exist", toggle);
        // Default OFF: no android:checked="true".
        assertFalse("Show system apps must default OFF", "true".equals(toggle.getAttribute("android:checked")));
        // Strings exist in both locales.
        for (String locale : new String[]{"values", "values-fa"}) {
            NodeList strings = xml(locale + "/strings.xml").getElementsByTagName("string");
            boolean found = false;
            for (int i = 0; i < strings.getLength(); i++) {
                if ("show_system_apps".equals(((Element) strings.item(i)).getAttribute("name"))) found = true;
            }
            assertTrue(locale + " missing show_system_apps", found);
        }
    }

    private static Map<String, String> colorMap(String path) throws Exception {
        Map<String, String> colors = new HashMap<>();
        NodeList nodes = xml(path).getElementsByTagName("color");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element color = (Element) nodes.item(i);
            colors.put(color.getAttribute("name"), color.getTextContent());
        }
        return colors;
    }
}
