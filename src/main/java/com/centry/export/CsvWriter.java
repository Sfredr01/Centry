package com.centry.export;

import java.io.IOException;
import java.io.Writer;

/**
 * Minimal RFC 4180 writer. Merchant names are user input and the file is opened in a spreadsheet, so a text
 * field that would start a formula gets a leading apostrophe (OWASP CSV injection guidance).
 */
final class CsvWriter {

    private final Writer out;

    CsvWriter(Writer out) {
        this.out = out;
    }

    void row(String... fields) throws IOException {
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                out.write(',');
            }
            out.write(escape(fields[i]));
        }
        out.write("\r\n");
    }

    private static String escape(String field) {
        if (field == null || field.isEmpty()) {
            return "";
        }
        String value = startsFormula(field.charAt(0)) ? "'" + field : field;
        boolean quote = value.chars().anyMatch(c -> c == ',' || c == '"' || c == '\r' || c == '\n');
        return quote ? '"' + value.replace("\"", "\"\"") + '"' : value;
    }

    private static boolean startsFormula(char first) {
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r';
    }
}
