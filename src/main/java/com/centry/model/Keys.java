package com.centry.model;

/**
 * The one definition of "same name" for categories and merchants: budgets, spend sums, alerts and
 * subscriptions all match on {@link #of}.
 *
 * <p>It must equal SQLite's {@code lower(trim(x))}, which the spend and merchant queries use: trim strips only
 * spaces and lower folds only ASCII. Java's own trim/toLowerCase go further, so a name like "Épicerie" would
 * get a key that SQL never produces and its spend would silently not count.
 */
public final class Keys {

    private Keys() {
    }

    /** Normalized key: spaces stripped from both ends, ASCII letters lower-cased. Columns keep the original text. */
    public static String of(String name) {
        int start = 0;
        int end = name.length();
        while (start < end && name.charAt(start) == ' ') {
            start++;
        }
        while (end > start && name.charAt(end - 1) == ' ') {
            end--;
        }
        StringBuilder key = new StringBuilder(end - start);
        for (int i = start; i < end; i++) {
            char c = name.charAt(i);
            key.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        return key.toString();
    }
}
