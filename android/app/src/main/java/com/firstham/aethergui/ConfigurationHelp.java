package com.firstham.aethergui;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.google.android.material.textfield.TextInputLayout;

/**
 * dev.017 CHANGE 2: the inline "?" help icons are gone. This class no longer injects a "?" button
 * next to every control; it only hides the explanation texts that used to sit beside them. The
 * explanations themselves moved to the centralized Help section at the end of More Settings
 * (CHANGE 10), which carries the consumer-friendly wording for the important controls.
 */
final class ConfigurationHelp {
    ConfigurationHelp(ViewGroup root) {
        collect(root);
    }

    private void collect(ViewGroup parent) {
        for (int index = 0; index < parent.getChildCount(); index++) {
            View child = parent.getChildAt(index);
            if (child instanceof TextView && "configuration_help".equals(child.getTag())) {
                // The hidden explanation TextViews stay in the layout so existing translations and
                // the pairing between a control and its explanation survive untouched; they are
                // simply never shown inline any more.
                child.setVisibility(View.GONE);
            } else if (child instanceof TextInputLayout) {
                TextInputLayout input = (TextInputLayout) child;
                // Helper texts move into the centralized Help section; the inline hint below the
                // field is no longer user-facing metadata that needs a "?" affordance.
                input.setHelperText(null);
                input.setHelperTextEnabled(false);
            } else if (child instanceof ViewGroup) collect((ViewGroup) child);
        }
    }

    void refresh() { }
}
