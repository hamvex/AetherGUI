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

/**
 * dev.034 UI-context regression coverage (PROMPT PHASE 36.11 / 22): the v2.3.0 controls
 * sit in the agreed sections, the context-aware containers start hidden, and every new
 * label, dropdown array and Help entry exists in English AND Persian.
 */
public final class V230UiResourcesTest {
    private static Document xml(String path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new File("src/main/res/" + path));
    }

    private static Map<String, Element> ids() throws Exception {
        Map<String, Element> map = new HashMap<>();
        NodeList nodes = xml("layout/activity_main.xml").getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            String id = element.getAttribute("android:id");
            if (!id.isEmpty()) assertNull("duplicate widget " + id, map.put(id, element));
        }
        return map;
    }

    @Test public void goolModeSitsDirectlyBelowProtocolOnThePrimaryPage() throws Exception {
        Map<String, Element> map = ids();
        Element general = map.get("@+id/configuration_general_body");
        Element layout = map.get("@+id/gool_mode_layout");
        assertNotNull(layout);
        Node parent = layout.getParentNode();
        while (parent != null && parent != general) parent = parent.getParentNode();
        assertSame("Gool Mode belongs on the primary Configurations page", general, parent);
        assertEquals("context-aware: hidden until a Gool protocol is selected", "gone",
                layout.getAttribute("android:visibility"));
        assertTrue(map.get("@+id/gool_mode_input").getTagName().endsWith("MaterialAutoCompleteTextView"));
        assertEquals("@string/gool_mode", layout.getAttribute("android:hint"));
        assertEquals("@string/gool_mode_summary", layout.getAttribute("app:helperText"));
        // Directly below Protocol, above the scan selector.
        assertTrue((map.get("@+id/protocol_layout").compareDocumentPosition(layout) & Node.DOCUMENT_POSITION_FOLLOWING) != 0);
        assertTrue((layout.compareDocumentPosition(map.get("@+id/scan_layout")) & Node.DOCUMENT_POSITION_FOLLOWING) != 0);
    }

    @Test public void newV230ControlsLiveInTheirLogicalSections() throws Exception {
        Map<String, Element> map = ids();
        Map<String, String> expected = new HashMap<>();
        for (String id : new String[]{"perf_profile_input", "tcp_connect_secs_input", "h2_keepalive_secs_input",
                "h2_keepalive_timeout_secs_input", "wg_endpoint_cooldown_secs_input", "wg_stale_secs_input",
                "stats_logging_switch", "exit_location_secs_layout"})
            expected.put(id, "advanced");
        for (String id : new String[]{"ech_auto_container", "ech_dns_input", "ech_domain_input", "tls_ciphers_input",
                "tls_groups_input", "grease_switch", "tls_verify_switch", "h2_fragment_sni_switch"})
            expected.put(id, "privacy");
        for (String id : new String[]{"api_fragment_switch", "gool_inner_container", "gool_inner_peer_input"})
            expected.put(id, "protocol");
        for (String id : new String[]{"enroll_address_input", "reprovision_switch"})
            expected.put(id, "organization");
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            Element field = map.get("@+id/" + entry.getKey());
            assertNotNull("missing dev.034 control " + entry.getKey(), field);
            Element body = map.get("@+id/configuration_" + entry.getValue() + "_body");
            Node parent = field.getParentNode();
            while (parent != null && parent != body) parent = parent.getParentNode();
            assertSame("wrong section for " + entry.getKey(), body, parent);
        }
    }

    @Test public void contextualV230ContainersStartHidden() throws Exception {
        // Visibility is driven by updateDependencies() from the persisted settings; the
        // default XML state must be hidden so nothing decorative ever shows.
        for (String id : new String[]{"gool_mode_layout", "gool_inner_container", "ech_auto_container",
                "exit_location_secs_layout", "h2_fragment_sni_switch"}) {
            Element element = ids().get("@+id/" + id);
            assertEquals(id + " must start hidden", "gone", element.getAttribute("android:visibility"));
        }
    }

    @Test public void goolModePerfAndEchLabelArraysMatchInBothLocales() throws Exception {
        Map<String, Integer> sizes = new HashMap<>();
        sizes.put("gool_mode_labels", 2);
        sizes.put("perf_profile_labels", 4);
        sizes.put("ech_labels", 3);
        for (String locale : new String[]{"values", "values-fa"}) {
            NodeList arrays = xml(locale + "/arrays.xml").getElementsByTagName("string-array");
            Set<String> names = new HashSet<>();
            for (int i = 0; i < arrays.getLength(); i++) {
                Element array = (Element) arrays.item(i);
                names.add(array.getAttribute("name"));
                Integer expected = sizes.get(array.getAttribute("name"));
                if (expected != null)
                    assertEquals(locale + " " + array.getAttribute("name"), expected.intValue(), array.getElementsByTagName("item").getLength());
            }
            for (String name : sizes.keySet()) assertTrue(locale + " missing " + name, names.contains(name));
        }
        // The storage contract: Gool Mode index 0 = Gool over MASQUE, 1 = Classic Gool.
        Element english = array("values/arrays.xml", "gool_mode_labels");
        assertEquals("Gool over MASQUE", english.getElementsByTagName("item").item(0).getTextContent());
        assertEquals("Classic Gool", english.getElementsByTagName("item").item(1).getTextContent());
        Element persian = array("values-fa/arrays.xml", "gool_mode_labels");
        for (int i = 0; i < 2; i++)
            assertTrue("the Persian Gool Mode labels must be translated",
                    persian.getElementsByTagName("item").item(i).getTextContent().matches("(?s).*[\\u0600-\\u06ff].*"));
    }

    @Test public void everyNewV230ControlHasALabelAndHelpInBothLocales() throws Exception {
        String[] labels = {"gool_mode", "gool_mode_summary", "gool_inner_peer", "gool_inner_peer_summary",
                "api_fragment", "api_fragment_summary", "ech_dns", "ech_dns_summary", "ech_domain", "ech_domain_summary",
                "tls_ciphers", "tls_ciphers_summary", "tls_groups", "tls_groups_summary", "grease", "grease_summary",
                "tls_verify", "tls_verify_summary", "enroll_address", "enroll_address_summary", "reprovision",
                "reprovision_summary", "perf_profile", "perf_profile_summary", "stats_logging", "stats_logging_summary",
                "tcp_connect_secs", "tcp_connect_summary", "h2_keepalive_secs", "h2_keepalive_summary",
                "h2_keepalive_timeout_secs", "h2_keepalive_timeout_summary", "wg_endpoint_cooldown_secs",
                "wg_cooldown_summary", "wg_stale_secs", "wg_stale_summary", "exit_location_secs",
                "exit_location_secs_summary", "h2_fragment_sni", "h2_fragment_sni_summary"};
        for (String locale : new String[]{"values", "values-fa"}) {
            Set<String> names = stringNames(locale + "/core_settings.xml");
            for (String label : labels) assertTrue(locale + " missing " + label, names.contains(label));
            if (locale.equals("values-fa")) {
                for (String label : labels) {
                    String text = text(locale + "/core_settings.xml", label);
                    // PROMPT PHASE 23: a bare technical term (GREASE) may stay recognizable;
                    // every summary and every other label must be actual Persian.
                    if (!label.equals("grease"))
                        assertTrue(label + " is not translated to Persian", text.matches("(?s).*[\\u0600-\\u06ff].*"));
                    assertFalse(label + " leaks an environment variable", text.contains("AETHER_"));
                }
            }
        }
        // The centralized Help documents the new control groups in both locales.
        for (String help : new String[]{"help_gool_mode", "help_tls_fingerprint", "help_expert_reliability"}) {
            assertTrue(stringNames("values/configuration_sections.xml").contains(help));
            assertTrue(stringNames("values-fa/configuration_sections.xml").contains(help));
            String persian = text("values-fa/configuration_sections.xml", help);
            assertTrue(help + " Persian translation missing", persian.matches("(?s).*[\\u0600-\\u06ff].*"));
        }
    }

    private static Element array(String path, String name) throws Exception {
        NodeList arrays = xml(path).getElementsByTagName("string-array");
        for (int i = 0; i < arrays.getLength(); i++)
            if (((Element) arrays.item(i)).getAttribute("name").equals(name)) return (Element) arrays.item(i);
        throw new AssertionError("missing array " + name);
    }

    private static Set<String> stringNames(String path) throws Exception {
        Set<String> names = new HashSet<>();
        NodeList strings = xml(path).getElementsByTagName("string");
        for (int i = 0; i < strings.getLength(); i++) names.add(((Element) strings.item(i)).getAttribute("name"));
        return names;
    }

    private static String text(String path, String name) throws Exception {
        NodeList strings = xml(path).getElementsByTagName("string");
        for (int i = 0; i < strings.getLength(); i++)
            if (((Element) strings.item(i)).getAttribute("name").equals(name))
                return ((Element) strings.item(i)).getTextContent();
        throw new AssertionError("missing string " + name);
    }
}
