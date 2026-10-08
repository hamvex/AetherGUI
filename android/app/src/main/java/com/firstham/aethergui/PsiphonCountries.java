package com.firstham.aethergui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Selectable Psiphon exit countries (dev.016 PART 8). The list is exactly the egress regions the
 * official helper reported as available on the validation device ("psiphon can leave from: AT AU
 * BE ..."); unknown or unverified regions are deliberately absent rather than guessed. The
 * selector stores the ISO 3166-1 alpha-2 code that {@code AETHER_PSIPHON_REGION} requires, so no
 * new persisted format exists: the existing {@code psiphonRegion} string keeps its meaning, and
 * "Automatic" is the empty string the runtime already treats as automatic selection.
 *
 * <p>This is the Psiphon <em>exit region</em> only. Aether's independent Exit Location
 * allow/deny policy stays blocked and is not connected to any of these entries.
 */
final class PsiphonCountries {
    /** ISO code -> its Locale, ordered exactly as the helper reported the regions available. */
    static final Map<String, Locale> COUNTRIES;
    static {
        // Only countries the helper itself offered during dev.014/dev.015 physical validation.
        String[] codes = {"AT", "AU", "BE", "BR", "CA", "CH", "DE", "DK", "ES", "FI", "FR",
                "GB", "ID", "IE", "IN", "IT", "JP", "LT", "NL", "NO", "PL", "RO", "RS", "SE",
                "SG", "US"};
        Map<String, Locale> countries = new LinkedHashMap<>();
        for (String code : codes) countries.put(code, new Locale("", code));
        COUNTRIES = Collections.unmodifiableMap(countries);
    }

    static List<String> codes() { return new ArrayList<>(COUNTRIES.keySet()); }

    /** "🇩🇪 Germany" for display in the app's current language, "Automatic" handled by the caller. */
    static String label(String code, Locale appLanguage) {
        if (code == null || code.isEmpty()) return "";
        Locale country = locale(code);
        return flag(code) + " " + country.getDisplayCountry(appLanguage == null ? Locale.getDefault() : appLanguage);
    }

    /** "Automatic" line for the dropdown; the caller adds the localized word. */
    static String flag(String code) {
        if (code == null || code.length() != 2 || !code.chars().allMatch(c -> c >= 'A' && c <= 'Z')) return "";
        int base = 0x1F1E6 - 'A';
        return new String(Character.toChars(base + code.charAt(0))) + new String(Character.toChars(base + code.charAt(1)));
    }

    static String normalize(String code) {
        if (code == null) return "";
        String upper = code.trim().toUpperCase(Locale.ROOT);
        return COUNTRIES.containsKey(upper) ? upper : "";
    }

    /**
     * dev.017 CHANGE 4: a manually entered country code only has to be a two-letter code — the
     * Psiphon helper decides at runtime whether it can honor it. Any valid code is preserved
     * (and restored in the UI as the Manual entry), unlike {@link #normalize(String)} which only
     * keeps verified list countries.
     */
    static String normalizeManual(String code) {
        if (code == null) return "";
        String upper = code.trim().toUpperCase(Locale.ROOT);
        return upper.matches("[A-Z]{2}") ? upper : "";
    }

    /** Whether the code is one of the curated, verified selector countries. */
    static boolean isKnown(String code) {
        return code != null && COUNTRIES.containsKey(code.trim().toUpperCase(Locale.ROOT));
    }

    static Locale locale(String code) {
        return new Locale("", code);
    }

    private PsiphonCountries() { }
}
