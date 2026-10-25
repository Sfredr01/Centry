package com.centry.model;

import java.util.Locale;

/**
 * The one definition of "same name" for categories and merchants: budgets, spend sums, alerts and the
 * previous-charge lookup all match on {@link #of}.
 */
public final class Keys {

    private Keys() {
    }

    /** Normalized key: trimmed and lower-cased. The stored columns keep the original text. */
    public static String of(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
