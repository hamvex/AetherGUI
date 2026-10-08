package com.firstham.aethergui;

import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import static org.junit.Assert.*;

public final class PsiphonConfigurationTest {
    @Test public void onlyAndChainDirectHaveDifferentOfficialEnvironments() {
        Map<String, Object> values = CoreSettings.values(new LinkedHashMap<>());
        values.put("psiphonTransport", "direct");
        values.put("psiphonMode", "chain");
        Map<String, String> chain = PrivacyRuntimeConfig.mappedOptions(values, "wg");
        assertEquals("chain", chain.get("AETHER_PSIPHON"));
        assertEquals("direct", chain.get("AETHER_PSIPHON_MODE"));
        assertEquals(PrivacyRuntimeConfig.PSIPHON_SOCKS, chain.get("AETHER_PSIPHON_BIND"));
        values.put("psiphonMode", "only");
        Map<String, String> only = PrivacyRuntimeConfig.mappedOptions(values, "wg");
        assertEquals("direct", only.get("AETHER_PSIPHON"));
        assertFalse(only.containsKey("AETHER_PSIPHON_MODE"));
        assertFalse(only.containsKey("AETHER_PSIPHON_BIND"));
        assertEquals("direct", values.get("psiphonTransport"));
    }

    @Test public void transportNeverSelectsTheTopologyAndDormantChoicesAreRetained() {
        for (String transport : PsiphonConfiguration.CHAIN_TRANSPORTS) {
            Map<String, Object> values = CoreSettings.values(new LinkedHashMap<>());
            values.put("psiphonTransport", transport);
            for (String topology : Arrays.asList("chain", "off", "only", "reverse", "chain")) {
                values.put("psiphonMode", topology);
                Map<String, Object> before = new LinkedHashMap<>(values);
                Map<String, String> environment = PrivacyRuntimeConfig.mappedOptions(values, "masque");
                assertEquals(topology.equals("chain") ? transport : null, environment.get("AETHER_PSIPHON_MODE"));
                assertEquals(before, values);
                assertEquals(transport, values.get("psiphonTransport"));
            }
        }
    }

    @Test public void noAmbiguousDirectTopologyOrUnvalidatedReverseChoiceIsExposed() {
        assertEquals(Arrays.asList("off", "only", "chain"), PsiphonConfiguration.TOPOLOGIES);
        assertEquals(Arrays.asList("auto", "cdn", "direct"), PsiphonConfiguration.CHAIN_TRANSPORTS);
        assertEquals("reverse", PsiphonConfiguration.coreMode("reverse"));
        assertThrows(IllegalArgumentException.class, () -> PsiphonConfiguration.coreMode("direct"));
        assertThrows(UnsupportedOperationException.class, () -> PsiphonConfiguration.TOPOLOGIES.add("direct"));
        assertThrows(UnsupportedOperationException.class, () -> PsiphonConfiguration.CHAIN_TRANSPORTS.add("only"));
        Map<String, Object> values = CoreSettings.values(new LinkedHashMap<>());
        values.put("psiphonMode", "direct");
        assertEquals("psiphonMode", PrivacySettings.invalid(values, "wg"));
    }

    @Test public void onlyAndReverseIgnoreSavedChainShapeWithoutClearingRegionOrHttp() {
        for (String topology : Arrays.asList("only", "reverse")) {
            Map<String, Object> values = CoreSettings.values(new LinkedHashMap<>());
            values.put("psiphonMode", topology);
            values.put("psiphonTransport", "cdn");
            values.put("psiphonRegion", "de");
            values.put("psiphonHttp", true);
            Map<String, String> environment = PrivacyRuntimeConfig.mappedOptions(values, "masque");
            assertFalse(environment.containsKey("AETHER_PSIPHON_MODE"));
            assertEquals("DE", environment.get("AETHER_PSIPHON_REGION"));
            assertEquals(PrivacyRuntimeConfig.PSIPHON_HTTP, environment.get("AETHER_PSIPHON_HTTP"));
        }
    }

    @Test public void onlyDoesNotDependOnSavedWarpProtocolOrChainTransport() {
        assertFalse(PsiphonConfiguration.usesWarp("only"));
        for (String topology : Arrays.asList("off", "chain", "reverse")) {
            assertTrue(PsiphonConfiguration.usesWarp(topology));
        }
        Map<String, Object> values = CoreSettings.values(new LinkedHashMap<>());
        for (String invalid : Arrays.asList("direct", "future")) {
            assertTrue(PsiphonConfiguration.usesWarp(invalid));
            values.put("psiphonMode", invalid);
            assertEquals("psiphonMode", CoreSettings.invalid(values, "wg", "h2"));
        }
    }

    @Test public void translatedControlsHaveDistinctLabelsAndContextHelp() throws Exception {
        Document english = resource("values/psiphon_settings.xml");
        assertArrayEquals(new String[]{"Off", "Psiphon Only (unavailable)", "Psiphon Chain"}, labels(english, "psiphon_topology_labels"));
        assertArrayEquals(new String[]{"Auto", "CDN (blocked)", "Direct"}, labels(english, "psiphon_chain_transport_labels"));
        Map<String, String> original = strings(english);
        Map<String, String> translated = strings(resource("values-fa/psiphon_settings.xml"));
        assertEquals(original.keySet(), translated.keySet());
        for (String name : original.keySet()) {
            if (name.startsWith("psiphon_region_manual_valid") || name.contains("manual_code")) continue; // format strings; checked below
            String value = translated.get(name);
            assertTrue("Persian translation missing: " + name, value.matches("(?s).*[\u0600-\u06ff].*") || name.contains("auto_compact"));
        }
        for (String name : Arrays.asList("psiphon_topology_labels", "psiphon_chain_transport_labels")) {
            assertEquals(labels(english, name).length, labels(resource("values-fa/psiphon_settings.xml"), name).length);
        }
        Document layout = resource("layout/activity_main.xml");
        Element topology = widget(layout, "psiphon_topology_input");
        Element transport = widget(layout, "psiphon_chain_transport_input");
        assertEquals("@string/psiphon_topology_help", ((Element) topology.getParentNode()).getAttribute("app:helperText"));
        assertEquals("@string/psiphon_chain_transport_help", ((Element) transport.getParentNode()).getAttribute("app:helperText"));
        Node parent = transport;
        while (parent != null && parent != widget(layout, "psiphon_chain_transport_layout")) parent = parent.getParentNode();
        assertNotNull(parent);
        assertEquals("gone", ((Element) parent).getAttribute("android:visibility"));
        assertEquals("false", topology.getAttribute("android:enabled"));
        assertEquals("false", transport.getAttribute("android:enabled"));
        // dev.017 CHANGE 4: the compact Manual country-code input sits under the region dropdown.
        Element manual = widget(layout, "region_manual_input");
        assertEquals("@string/psiphon_region_manual_help", ((Element) manual.getParentNode()).getAttribute("app:helperText"));
        assertEquals("gone", ((Element) manual.getParentNode()).getAttribute("android:visibility"));
        // dev.017 CHANGE 14: Home shows LOCATION, not NODE, and no "Tap to secure" remains.
        NodeList homeLabels = resource("values/strings.xml").getElementsByTagName("string");
        String nodeLabel = null;
        for (int index = 0; index < homeLabels.getLength(); index++) {
            Element string = (Element) homeLabels.item(index);
            if ("node_label".equals(string.getAttribute("name"))) nodeLabel = string.getTextContent();
        }
        assertEquals("LOCATION", nodeLabel);
    }

    private static Document resource(String relative) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new File("src/main/res/" + relative));
    }

    private static String[] labels(Document document, String name) {
        NodeList arrays = document.getElementsByTagName("string-array");
        for (int index = 0; index < arrays.getLength(); index++) {
            Element array = (Element) arrays.item(index);
            if (!name.equals(array.getAttribute("name"))) continue;
            NodeList items = array.getElementsByTagName("item");
            String[] values = new String[items.getLength()];
            for (int item = 0; item < items.getLength(); item++) values[item] = items.item(item).getTextContent();
            return values;
        }
        throw new AssertionError("Missing array " + name);
    }

    private static Map<String, String> strings(Document document) {
        Map<String, String> values = new LinkedHashMap<>();
        NodeList strings = document.getElementsByTagName("string");
        for (int index = 0; index < strings.getLength(); index++) {
            Element string = (Element) strings.item(index);
            assertNull(values.put(string.getAttribute("name"), string.getTextContent()));
        }
        return values;
    }

    private static Element widget(Document document, String identifier) {
        NodeList elements = document.getElementsByTagName("*");
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            if (element.getAttribute("android:id").equals("@+id/" + identifier)) return element;
        }
        throw new AssertionError("Missing widget " + identifier);
    }
}
