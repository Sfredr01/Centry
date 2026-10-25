package com.centry.model;

import java.util.Locale;

/** The one definition of "same category": budgets, spend sums and alerts all go through {@link #key}. */
public final class Categories {

    private Categories() {
    }

    /** Normalized category key: trimmed and lower-cased. {@code expense.category} keeps the original text. */
    public static String key(String category) {
        return category.trim().toLowerCase(Locale.ROOT);
    }
}
