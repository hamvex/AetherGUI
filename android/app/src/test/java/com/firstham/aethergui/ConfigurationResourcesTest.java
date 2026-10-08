package com.firstham.aethergui;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.*;

public final class ConfigurationResourcesTest {
    private static Document xml(String path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new File("src/main/res/" + path));
    }

    @Test public void allSettingsRemainInOneAppropriateCategory() throws Exception {
        Map<String, Element> ids = new HashMap<>();
        NodeList nodes = xml("layout/activity_main.xml").getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            String id = element.getAttribute("android:id");
            if (!id.isEmpty()) assertNull("duplicate widget " + id, ids.put(id, element));
        }
        // dev.017 primary screen: the frequently-changed settings live directly on the
        // configurations page with no accordion; the low-frequency sections moved to the separate
        // More Settings page. dev.017 CHANGE 3 moved Packet Size/VPN MTU from the primary
        // "general" list into the Network & Routing section of More Settings; dev.017 CHANGE 4
        // added the compact Manual country-code input next to the region dropdown.
        String[][] categories = {
                {"general", "vpn_mode_button", "proxy_mode_button", "protocol_input", "transport_input", "psiphon_topology_input", "psiphon_chain_transport_input", "psiphon_region_input", "region_manual_input", "psiphon_http_switch", "tor_proxy_switch", "scan_input", "split_switch", "include_apps_radio", "exclude_apps_radio", "choose_apps_button"},
                {"advanced", "reconnect_switch", "auto_connect_switch", "no_data_check_switch", "validate_secs_input", "startup_secs_input", "reconnect_secs_input", "wg_keepalive_input", "wg_no_profile_retry_switch"},
                {"privacy", "obfuscation_input", "ech_input", "ech_custom_input", "h2_fragment_switch", "h2_fragment_size_input", "h2_fragment_delay_input", "killswitch_switch"},
                {"routing", "ip_input", "dns_switch", "dns_input", "route_sniff_switch", "route_sniff_ms_input", "route_block_input", "route_direct_input", "mtu_automatic_button", "mtu_manual_button", "mtu_input"},
                {"proxy", "proxy_endpoints", "socks_input", "http_proxy_input", "upstream_proxy_input", "lan_switch", "copy_lan_address_button", "copy_lan_port_button"},
                {"protocol", "peer_input", "quic_v2_switch", "h2_peer_input", "wiw_outer_peer_input", "wiw_inner_peer_input", "mim_outer_peer_input", "mim_inner_peer_input"},
                {"organization", "team_input", "access_token_input", "access_client_id_input", "access_client_secret_input", "access_email_input", "gateway_switch"},
                {"help"}
        };
        for (String[] category : categories) {
            Element body = ids.get("@+id/configuration_" + category[0] + "_body");
            assertNotNull(body);
            assertEquals(category[0].equals("general") ? "visible" : "gone", body.getAttribute("android:visibility"));
            for (int i = 1; i < category.length; i++) {
                Element field = ids.get("@+id/" + category[i]);
                assertNotNull("missing setting " + category[i], field);
                Node parent = field.getParentNode();
                while (parent != null && parent != body) parent = parent.getParentNode();
                assertSame("wrong category for " + category[i], body, parent);
                if (field.getTagName().endsWith("TextInputEditText") || field.getTagName().endsWith("MaterialAutoCompleteTextView")) {
                    Element layout = (Element) field.getParentNode();
                    Node next = layout.getNextSibling();
                    while (next != null && !(next instanceof Element)) next = next.getNextSibling();
                    boolean summary = next instanceof Element && "TextView".equals(((Element) next).getTagName())
                            && ((Element) next).getAttribute("android:text").endsWith("_summary");
                    assertTrue("missing local help for " + category[i], !layout.getAttribute("app:helperText").isEmpty() || summary);
                }
            }
        }
        // The primary body is on the configurations page; every other body is on More Settings.
        Element primary = ids.get("@+id/configuration_general_body");
        Node page = primary.getParentNode();
        while (page != null && page.getNodeType() != Node.ELEMENT_NODE) page = page.getParentNode();
        assertNotNull(ids.get("@+id/more_settings_button"));
        Element more = ids.get("@+id/more_settings_page");
        for (String[] category : categories) {
            if (category[0].equals("general")) continue;
            Node parent = ids.get("@+id/configuration_" + category[0] + "_body").getParentNode();
            while (parent != null && parent.getNodeType() != Node.ELEMENT_NODE) parent = parent.getParentNode();
            while (parent != null && parent != more) parent = parent.getParentNode();
            assertSame("More Settings page should contain " + category[0], more, parent);
        }
    }

    @Test public void categoryAndSettingHelpExistsInEnglishAndPersianWithoutReleaseHeadings() throws Exception {
        Set<String> english = new HashSet<>();
        for (String locale : new String[]{"values", "values-fa"}) {
            NodeList strings = xml(locale + "/configuration_sections.xml").getElementsByTagName("string");
            Set<String> names = new HashSet<>();
            for (int i = 0; i < strings.getLength(); i++) {
                Element item = (Element) strings.item(i);
                assertTrue(names.add(item.getAttribute("name")));
                String text = item.getTextContent();
                // dev.017 CHANGE 3: configurations_summary is intentionally empty (the removed
                // "Defaults work for most people. Tap ? for help." subtitle is not replaced).
                boolean removedSubtitle = "configurations_summary".equals(item.getAttribute("name"));
                if (!removedSubtitle) {
                    assertFalse(text.trim().isEmpty());
                    if (locale.equals("values-fa")) assertTrue("Persian translation missing: " + item.getAttribute("name"), text.matches("(?s).*[\\u0600-\\u06ff].*"));
                }
                assertFalse(text.contains("AETHER_"));
                assertFalse(text.contains("v2.0.0"));
            }
            if (locale.equals("values")) english = names;
            else assertEquals(english, names);
        }
        NodeList layout = xml("layout/activity_main.xml").getElementsByTagName("*");
        for (int i = 0; i < layout.getLength(); i++) {
            String text = ((Element) layout.item(i)).getAttribute("android:text");
            assertNotEquals("@string/core_v2_features", text);
            assertNotEquals("@string/core_numeric_help", text);
        }
    }

    @Test public void headerMatchesPageThemeWhileStatusBarStaysDistinct() throws Exception {
        // dev.017 CHANGE 1: the header blends into the page background in BOTH themes (it used to
        // be a fixed white strip). The status bar keeps its own scrim color and light/dark icon
        // appearance so it remains visually distinct from the header/body.
        Map<String, String> lightPage = colorMap("values/colors.xml");
        Map<String, String> darkPage = colorMap("values-night/colors.xml");
        assertEquals(lightPage.get("background"), lightPage.get("header_background"));
        assertEquals(darkPage.get("background"), darkPage.get("header_background"));
        // The dark header must actually be dark and its foreground readable.
        assertFalse(darkPage.get("header_background").equalsIgnoreCase(lightPage.get("header_background")));
        // The status bar scrim is deliberately distinct from the header/body in both themes.
        assertNotEquals(lightPage.get("header_background"), lightPage.get("status_bar_scrim"));
        assertNotEquals(darkPage.get("header_background"), darkPage.get("status_bar_scrim"));
        for (String locale : new String[]{"values", "values-night"}) {
            Map<String, String> theme = new HashMap<>();
            NodeList items = xml(locale + "/themes.xml").getElementsByTagName("item");
            for (int i = 0; i < items.getLength(); i++) {
                Element item = (Element) items.item(i);
                theme.put(item.getAttribute("name"), item.getTextContent());
            }
            assertEquals("@color/status_bar_scrim", theme.get("android:statusBarColor"));
            assertEquals(locale.equals("values") ? "true" : "false", theme.get("android:windowLightStatusBar"));
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

    @Test public void longDescriptionsAreHelpMetadataNotPermanentText() throws Exception {
        NodeList elements = xml("layout/activity_main.xml").getElementsByTagName("TextView");
        int descriptions = 0;
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            if ("configuration_help".equals(element.getAttribute("android:tag"))) {
                descriptions++;
                assertEquals("gone", element.getAttribute("android:visibility"));
            }
            if ("@+id/proxy_endpoints".equals(element.getAttribute("android:id"))) {
                assertNotEquals("gone", element.getAttribute("android:visibility"));
                assertFalse(element.hasAttribute("android:tag"));
            }
        }
        assertTrue("category and important setting help", descriptions >= 25);
    }

    @Test public void privacyToolsAreTranslatedAndPsiphonActivationRemainsUnavailable() throws Exception {
        Set<String> english = new HashSet<>();
        for (String locale : new String[]{"values", "values-fa"}) {
            NodeList strings = xml(locale + "/configuration_help.xml").getElementsByTagName("string");
            Set<String> names = new HashSet<>();
            for (int index = 0; index < strings.getLength(); index++) {
                Element entry = (Element) strings.item(index);
                assertTrue(names.add(entry.getAttribute("name")));
                assertFalse(entry.getTextContent().trim().isEmpty());
                assertFalse(entry.getTextContent().contains("AETHER_"));
                if (locale.equals("values-fa")) assertTrue(entry.getTextContent().matches("(?s).*[\\u0600-\\u06ff].*"));
            }
            if (locale.equals("values")) english = names;
            else assertEquals(english, names);
        }
        NodeList controls = xml("layout/activity_main.xml").getElementsByTagName("*");
        boolean unavailable = false;
        for (int index = 0; index < controls.getLength(); index++) {
            Element control = (Element) controls.item(index);
            assertFalse(control.getAttribute("android:id").contains("psiphon_switch"));
            if (control.getAttribute("android:text").equals("@string/configuration_psiphon_unavailable")) {
                assertEquals("TextView", control.getTagName());
                unavailable = true;
            }
            if (control.getAttribute("android:id").startsWith("@+id/psiphon_")
                    && (control.getTagName().endsWith("MaterialAutoCompleteTextView")
                    || control.getTagName().endsWith("TextInputEditText")
                    || control.getTagName().endsWith("MaterialSwitch")))
                assertEquals("false", control.getAttribute("android:enabled"));
        }
        assertTrue(unavailable);
    }

    @Test public void categoryOrderIsPerformanceFirstAndHelpLast() throws Exception {
        // dev.017 CHANGE 10: the More Settings order is Performance, Privacy & Security, Network
        // & Routing, Proxy & Chaining, Advanced Protocols, Organization / Zero Trust, Help — with
        // Help as the final centralized section (CHANGE 2). The primary screen keeps no accordion
        // headers at all.
        String[] expected = {"advanced", "privacy", "routing", "proxy", "protocol", "organization", "help"};
        int position = 0;
        NodeList nodes = xml("layout/activity_main.xml").getElementsByTagName("*");
        for (int index = 0; index < nodes.getLength(); index++) {
            String id = ((Element) nodes.item(index)).getAttribute("android:id");
            if (id.startsWith("@+id/configuration_") && id.endsWith("_header")) {
                assertTrue(position < expected.length);
                assertEquals("@+id/configuration_" + expected[position++] + "_header", id);
            }
        }
        assertEquals(expected.length, position);
    }

    @Test public void unverifiedPrivacyPathsAreExplanationsNotActivationControls() throws Exception {
        Set<String> found = new HashSet<>();
        NodeList nodes = xml("layout/activity_main.xml").getElementsByTagName("*");
        for (int index = 0; index < nodes.getLength(); index++) {
            Element element = (Element) nodes.item(index);
            String text = element.getAttribute("android:text");
            if (text.equals("@string/privacy_exit_unavailable")) {
                assertEquals("TextView", element.getTagName());
                found.add(text);
            }
            if (element.getAttribute("android:id").equals("@+id/privacy_disable_unavailable"))
                assertEquals("gone", element.getAttribute("android:visibility"));
        }
        // Exit Location stays a blocked explanation (moved to More Settings); the Tor extensions
        // note is now covered by the contextual help next to the Tor switch on the primary page.
        assertEquals(1, found.size());
    }

    @Test public void privacyChoicesAreNextToProtocolWithoutDuplicatingOrExposingBlockedControls() throws Exception {
        Map<String, Element> ids = new HashMap<>();
        NodeList nodes = xml("layout/activity_main.xml").getElementsByTagName("*");
        for (int index = 0; index < nodes.getLength(); index++) {
            Element element = (Element) nodes.item(index);
            String id = element.getAttribute("android:id");
            if (!id.isEmpty()) assertNull(ids.put(id, element));
        }
        // dev.016: Psiphon controls sit directly after the Protocol selector on the primary
        // screen, visible only while their combined protocol is active; the country selector is a
        // dropdown, not a free-text ISO field.
        Element primary = ids.get("@+id/configuration_general_body");
        for (String id : new String[]{"psiphon_topology_layout", "psiphon_chain_transport_layout", "psiphon_region_layout", "psiphon_http_switch"}) {
            Element option = ids.get("@+id/" + id);
            assertSame("Psiphon control should sit in the primary section", primary, option.getParentNode());
            assertEquals("gone", option.getAttribute("android:visibility"));
        }
        Element protocol = ids.get("@+id/protocol_layout");
        Element psiphon = ids.get("@+id/psiphon_topology_layout");
        assertTrue((protocol.compareDocumentPosition(psiphon) & Node.DOCUMENT_POSITION_FOLLOWING) != 0);
        assertTrue((psiphon.compareDocumentPosition(ids.get("@+id/scan_layout")) & Node.DOCUMENT_POSITION_FOLLOWING) != 0);
        // dev.019 ISSUE 2: the Tor Routing toggle is no longer user-facing UI — Tor participation
        // is derived from the Protocol entry. The switch view stays only as the hidden
        // compatibility carrier of the persisted torProxy preference (never visible, never
        // clickable); the region selector remains a dropdown entry, not a text edit.
        assertSame(primary, ids.get("@+id/tor_proxy_switch").getParentNode());
        assertEquals("gone", ids.get("@+id/tor_proxy_switch").getAttribute("android:visibility"));
        assertEquals("false", ids.get("@+id/tor_proxy_switch").getAttribute("android:enabled"));
        assertTrue(ids.get("@+id/psiphon_region_input").getTagName().endsWith("MaterialAutoCompleteTextView"));
    }

    @Test public void resetDefaultsMovedToMoreSettingsAfterHelpWithConfirmation() throws Exception {
        // dev.019 ISSUE 9: Reset Defaults is removed from the primary Configurations screen and
        // lives at the very END of More Settings — AFTER the Help section — separated by a
        // divider; the confirmation strings exist in English and Persian.
        Map<String, Element> ids = new HashMap<>();
        NodeList nodes = xml("layout/activity_main.xml").getElementsByTagName("*");
        for (int index = 0; index < nodes.getLength(); index++) {
            Element element = (Element) nodes.item(index);
            String id = element.getAttribute("android:id");
            if (!id.isEmpty()) assertNull(ids.put(id, element));
        }
        Element reset = ids.get("@+id/reset_button");
        Element more = ids.get("@+id/more_settings_page");
        Element configurationsPage = ids.get("@+id/configurations_page");
        assertNotNull(reset);
        // The reset button is inside the More Settings page (and NOT inside Configurations).
        Node parent = reset.getParentNode();
        boolean inMore = false;
        while (parent != null) {
            if (parent == more) { inMore = true; break; }
            if (parent == configurationsPage) break;
            parent = parent.getParentNode();
        }
        assertTrue("Reset defaults must live on the More Settings page", inMore);
        // AFTER the Help section.
        assertTrue((ids.get("@+id/configuration_help_body").compareDocumentPosition(reset)
                & Node.DOCUMENT_POSITION_FOLLOWING) != 0);
        // The primary configurations page no longer contains it: the only reset_button in the
        // document is the one on More Settings (the id map above would have failed on a dup).
        assertEquals(1, ids.keySet().stream().filter(k -> k.equals("@+id/reset_button")).count());
        // Confirmation strings exist in both locales.
        for (String locale : new String[]{"values", "values-fa"}) {
            Document strings = xml(locale + "/strings.xml");
            NodeList all = strings.getElementsByTagName("string");
            Set<String> names = new HashSet<>();
            for (int i = 0; i < all.getLength(); i++) names.add(((Element) all.item(i)).getAttribute("name"));
            assertTrue(locale + " missing reset_confirmation_title", names.contains("reset_confirmation_title"));
            assertTrue(locale + " missing reset_confirmation_message", names.contains("reset_confirmation_message"));
        }
    }

    @Test public void torRoutingHelpTextRemovedAndCompleteHelpCoversCurrentControls() throws Exception {
        // dev.019 ISSUES 2/7: the obsolete Tor Routing help entry is gone; the centralized Help
        // section now documents every visible feature, in English and Persian.
        for (String locale : new String[]{"values", "values-fa"}) {
            Document sections = xml(locale + "/configuration_sections.xml");
            NodeList all = sections.getElementsByTagName("string");
            Set<String> names = new HashSet<>();
            for (int i = 0; i < all.getLength(); i++) names.add(((Element) all.item(i)).getAttribute("name"));
            assertFalse(locale + " must not document the removed Tor Routing toggle", names.contains("help_tor"));
            String[] required = {"help_connection_mode", "help_protocol", "help_masque_method", "help_scan_mode", "help_psiphon_transport",
                    "help_exit_country", "help_split_tunneling", "help_mtu", "help_performance", "help_privacy_security",
                    "help_network_routing", "help_proxy_chaining", "help_advanced_protocols", "help_organization",
                    "help_settings_theme", "help_settings_language", "help_settings_notifications",
                    "help_settings_quick_settings", "help_settings_updates", "help_settings_backup", "help_reset_defaults"};
            for (String name : required) assertTrue(locale + " missing " + name, names.contains(name));
        }
        // The update explanatory text is removed from the layout (dev.019 update-area cleanup).
        NodeList layoutNodes = xml("layout/activity_main.xml").getElementsByTagName("*");
        for (int i = 0; i < layoutNodes.getLength(); i++) {
            Element element = (Element) layoutNodes.item(i);
            assertFalse("update_channel_note must not be referenced", element.getAttribute("android:text").equals("@string/update_channel_note"));
        }
    }
}
