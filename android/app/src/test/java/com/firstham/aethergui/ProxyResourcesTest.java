package com.firstham.aethergui;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public final class ProxyResourcesTest {
    private static Map<String, String> strings(String locale) throws Exception {
        Map<String, String> result = new HashMap<>();
        for (String name : new String[]{"strings.xml", "proxy_mode.xml"}) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            NodeList nodes = factory.newDocumentBuilder().parse(new File("src/main/res/" + locale + "/" + name))
                    .getElementsByTagName("string");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element item = (Element) nodes.item(i);
                assertNull("duplicate " + item.getAttribute("name"),
                        result.put(item.getAttribute("name"), item.getTextContent()));
            }
        }
        return result;
    }

    @Test public void englishAndPersianExposeProxyAndBothEndpointPlaceholders() throws Exception {
        for (String locale : new String[]{"values", "values-fa"}) {
            Map<String, String> strings = strings(locale);
            assertEquals(locale.equals("values") ? "Proxy" : "\u067e\u0631\u0648\u06a9\u0633\u06cc", strings.get("proxy_mode"));
            String summary = strings.get("proxy_mode_summary");
            assertTrue(summary.contains("HTTP\\n%1$s"));
            assertTrue(summary.contains("SOCKS5\\n%2$s"));
            assertTrue(strings.get("service_proxy_ready").contains("HTTP"));
            assertTrue(strings.get("service_proxy_port_unavailable").contains("%2$s"));
            assertFalse(strings.containsKey("service_gool_rejecting_iran"));
            assertFalse(strings.containsKey("service_gool_iran_failed"));
        }
    }

    @Test public void endpointsAreSelectableOnlyInTheProxyConfigurationSection() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        NodeList nodes = factory.newDocumentBuilder().parse(new File("src/main/res/layout/activity_main.xml"))
                .getElementsByTagName("*");
        boolean button = false, endpoints = false;
        for (int i = 0; i < nodes.getLength(); i++) {
            Element item = (Element) nodes.item(i);
            if ("@+id/proxy_mode_button".equals(item.getAttribute("android:id"))) {
                button = true;
                assertEquals("@string/proxy_mode", item.getAttribute("android:text"));
            }
            if ("@+id/proxy_endpoints".equals(item.getAttribute("android:id"))) {
                endpoints = true;
                assertEquals("ltr", item.getAttribute("android:textDirection"));
                assertEquals("true", item.getAttribute("android:textIsSelectable"));
                boolean proxyCategory = false;
                for (org.w3c.dom.Node parent = item.getParentNode(); parent instanceof Element; parent = parent.getParentNode()) {
                    String id = ((Element) parent).getAttribute("android:id");
                    assertNotEquals("@+id/home_page", id);
                    if ("@+id/configuration_proxy_body".equals(id)) proxyCategory = true;
                }
                assertTrue("endpoints must be inside Proxy & Chaining", proxyCategory);
            }
        }
        assertTrue(button); assertTrue(endpoints);
    }
}
