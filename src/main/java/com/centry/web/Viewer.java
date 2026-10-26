package com.centry.web;

import java.util.Locale;

/** Who is signed in, as the app bar shows them. */
record Viewer(String name, String initials) {

    /** First letter of the first and last word, upper-cased: "Sam Rivera" gives "SR", "Mom" gives "M". */
    static Viewer of(String name) {
        String[] words = name.strip().split("\\s+");
        String initials = first(words[0]) + (words.length > 1 ? first(words[words.length - 1]) : "");
        return new Viewer(name.strip(), initials);
    }

    private static String first(String word) {
        return new String(Character.toChars(word.codePointAt(0))).toUpperCase(Locale.ROOT);
    }
}
