package com.centry.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

class CsvWriterTest {

    private static String row(String... fields) throws IOException {
        StringWriter out = new StringWriter();
        new CsvWriter(out).row(fields);
        return out.toString();
    }

    @Test
    void plainFieldsAreJoinedWithCommasAndCrlf() throws IOException {
        assertThat(row("a", "b c", "3")).isEqualTo("a,b c,3\r\n");
    }

    @Test
    void nullBecomesEmpty() throws IOException {
        assertThat(row("a", null, "c")).isEqualTo("a,,c\r\n");
    }

    @Test
    void fieldsWithCommaQuoteOrLineBreakAreQuoted() throws IOException {
        assertThat(row("a,b")).isEqualTo("\"a,b\"\r\n");
        assertThat(row("line\nbreak")).isEqualTo("\"line\nbreak\"\r\n");
        assertThat(row("mid\rcr")).isEqualTo("\"mid\rcr\"\r\n");
    }

    @Test
    void innerQuotesAreDoubled() throws IOException {
        assertThat(row("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"\r\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"=SUM(A1)", "+1", "-1", "@cmd", "\tx"})
    void formulaPrefixesAreNeutralised(String value) throws IOException {
        assertThat(row(value)).isEqualTo("'" + value + "\r\n");
    }

    @Test
    void leadingCarriageReturnIsNeutralisedAndQuoted() throws IOException {
        assertThat(row("\r=1")).isEqualTo("\"'\r=1\"\r\n");
    }

    @Test
    void neutralisedFieldStillQuotedWhenItNeedsIt() throws IOException {
        assertThat(row("=a,b")).isEqualTo("\"'=a,b\"\r\n");
    }

    @Test
    void emptyStringStaysEmpty() throws IOException {
        assertThat(row("")).isEqualTo("\r\n");
    }
}
