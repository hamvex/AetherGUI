package com.firstham.aethergui;

import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.view.ViewParent;
import androidx.core.view.ViewCompat;
import com.firstham.aethergui.databinding.ActivityMainBinding;
import com.google.android.material.button.MaterialButton;

/**
 * Presentation state only: collapsing a category never changes its settings.
 *
 * <p>dev.020 CHANGE 4: More Settings is a TRUE single-open accordion. At most ONE section may be
 * expanded at any moment: opening a section automatically collapses the previously open one, and
 * tapping the open section collapses it (so the all-collapsed state is always reachable). Help
 * follows the same rule. Entering More Settings always starts fully collapsed - the visual
 * expanded/collapsed state is NEVER persisted (a process death cannot restore an expanded
 * section), and leaving the page collapses everything so a later return starts fresh. The
 * expand/collapse changes are posted to the next frame so a fast tap cannot interleave with a
 * mid-dispatch rebuild, and the body visibility flip itself keeps the existing no-animation
 * contract (no layout jump: each section occupies exactly its header height while collapsed,
 * exactly as dev.019 rendered it).
 */
final class ConfigurationSections {
    private final Section[] sections;
    private final ConfigurationHelp help;
    /** The only currently expanded section, or null when all are collapsed. */
    private Section expanded;

    ConfigurationSections(ActivityMainBinding b, Bundle saved) {
        // The primary screen (Connection/Protocol/Psiphon/Tor/Scan/Split) is always visible, so
        // only the More Settings page sections are collapsible here. dev.017 CHANGE 10: the order
        // is Performance, Privacy & Security, Network & Routing, Proxy & Chaining, Advanced
        // Protocols, Organization / Zero Trust, Help — with Help last (CHANGE 2). All sections
        // start COLLAPSED (dev.020 CHANGE 4: the saved-state parameter is deliberately ignored -
        // re-entering More Settings always begins fully collapsed).
        sections = new Section[]{
                new Section("advanced", b.configurationAdvancedHeader, b.configurationAdvancedBody),
                new Section("privacy", b.configurationPrivacyHeader, b.configurationPrivacyBody),
                new Section("routing", b.configurationRoutingHeader, b.configurationRoutingBody),
                new Section("proxy", b.configurationProxyHeader, b.configurationProxyBody),
                new Section("protocol", b.configurationProtocolHeader, b.configurationProtocolBody),
                new Section("organization", b.configurationOrganizationHeader, b.configurationOrganizationBody),
                new Section("help", b.configurationHelpHeader, b.configurationHelpBody)
        };
        for (Section section : sections) {
            ViewCompat.setAccessibilityHeading(section.header, true);
            section.expand(false);
            // dev.020 CHANGE 4: a header tap resolves to exactly one terminal state - collapse
            // (it was the expanded one) or expand (and collapse the previous) - and is POSTED
            // after the tap dispatch. Rapid section switching cannot interleave a stale
            // header-visibility read with the state change, so no double-expansion is possible.
            section.header.setOnClickListener(v -> section.header.post(() -> toggle(section)));
        }
        help = new ConfigurationHelp(b.configurationsPage);
        help2 = new ConfigurationHelp(b.moreSettingsPage);
    }

    private final ConfigurationHelp help2;

    void refreshHelp() { help.refresh(); help2.refresh(); }

    /**
     * The single-open toggle. A section that is already expanded collapses; any other section
     * expands while the previously expanded one (if any) is collapsed in the same operation.
     */
    private void toggle(Section target) {
        if (expanded == target) {
            expanded = null;
            target.expand(false);
            return;
        }
        if (expanded != null) expanded.expand(false);
        expanded = target;
        target.expand(true);
    }

    /** Collapses every section; used when leaving More Settings so re-entry starts fresh. */
    void collapseAll() {
        for (Section section : sections) section.expand(false);
        expanded = null;
    }

    /** The accordion's currently expanded section key, or null when all are collapsed. */
    String expandedSection() {
        return expanded == null ? null : expanded.key;
    }

    void save(Bundle out) {
        // dev.020 CHANGE 4: visual accordion state is intentionally NOT persisted. Re-entering
        // More Settings always starts fully collapsed; nothing about the actual settings values
        // is saved here either (leaving the page never resets a setting).
    }

    void reveal(View field) {
        if (field == null) return;
        for (Section section : sections) {
            for (ViewParent parent = field.getParent(); parent != null; parent = parent.getParent()) {
                if (parent == section.body) { expand(section); break; }
            }
        }
        field.post(() -> {
            field.requestFocus();
            field.requestRectangleOnScreen(new Rect(0, 0, field.getWidth(), field.getHeight()), true);
        });
    }

    /** Validation-reveal path: expands the section and collapses every other (single-open). */
    private void expand(Section target) {
        for (Section section : sections) if (section != target) section.expand(false);
        expanded = target;
        target.expand(true);
    }

    private static final class Section {
        final String key;
        final MaterialButton header;
        final View body;

        Section(String key, MaterialButton header, View body) {
            this.key = key; this.header = header; this.body = body;
        }

        void expand(boolean expanded) {
            body.setVisibility(expanded ? View.VISIBLE : View.GONE);
            header.setIconResource(expanded ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);
            ViewCompat.setStateDescription(header, header.getContext().getString(expanded
                    ? R.string.configuration_expanded : R.string.configuration_collapsed));
        }
    }
}
