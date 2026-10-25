package com.centry.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

// Keys.of must equal SQLite's lower(trim(x)): only spaces are trimmed and only ASCII is lower-cased.
class KeysTest {

    @ParameterizedTest(name = "[{0}] -> [{1}]")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "Groceries           | groceries",
            "\"  Dining Out  \"    | dining out",
            "\"   \"               | \"\"",
            "\"\"                  | \"\"",
            "Épicerie            | Épicerie",
            "CAFÉ                | cafÉ",
            "AZaz09@[`{          | azaz09@[`{",
    })
    void normalizesLikeSqlite(String name, String key) {
        assertThat(Keys.of(name)).isEqualTo(key);
    }

    @ParameterizedTest
    @CsvSource({"food", "FOOD"})
    void tabsAndNewlinesAreNotTrimmed(String word) {
        assertThat(Keys.of("\t" + word + "\n")).isEqualTo("\t" + word.toLowerCase() + "\n");
    }
}
