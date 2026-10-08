package com.firstham.aethergui;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class PsiphonConfiguration {
    static final List<String> TOPOLOGIES = Collections.unmodifiableList(Arrays.asList("off", "only", "chain"));
    static final List<String> CHAIN_TRANSPORTS = Collections.unmodifiableList(Arrays.asList("auto", "cdn", "direct"));

    static String coreMode(String topology) {
        switch (topology) {
            case "off": return "off";
            case "only": return "direct";
            case "chain": return "chain";
            case "reverse": return "reverse";
            default: throw new IllegalArgumentException("Invalid Psiphon topology");
        }
    }

    static boolean usesChainTransport(String topology) {
        return "chain".equals(topology);
    }

    static boolean usesWarp(String topology) {
        return !"only".equals(topology);
    }

    private PsiphonConfiguration() { }
}
