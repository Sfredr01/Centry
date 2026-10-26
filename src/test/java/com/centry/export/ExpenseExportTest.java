package com.centry.export;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.IOException;
import java.io.Writer;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExpenseExportTest {

    private static final String HEADER = "date,merchant,category,amount,spent_by,created_by,source,transaction_id\r\n";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void seed() {
        jdbc.sql("DELETE FROM expense").update();
        jdbc.sql("DELETE FROM users WHERE username <> 'admin'").update();
        jdbc.sql("""
                INSERT INTO users (id, username, display_name, password_hash, role, created_at)
                VALUES (900, 'mom', 'Mom, the Boss', NULL, 'MEMBER', '2026-01-01T00:00:00')""").update();
        expense("t-feb", "Before", "2026-02-28T23:59:59", 100, null, null);
        expense("t-first", "Market", "2026-03-01T00:00:00", 1250, 900, 900);
        expense("t-last", "=Evil, Inc", "2026-03-31T23:59:59", 5, null, null);
        expense("t-apr", "After", "2026-04-01T00:00:00", 100, null, null);
    }

    private void expense(String txn, String merchant, String at, long cents, Integer spentBy, Integer createdBy) {
        jdbc.sql("""
                INSERT INTO expense (transaction_id, merchant, amount_cents, category, occurred_at, spent_by, created_by)
                VALUES (?, ?, ?, 'Food', ?, ?, ?)""").params(txn, merchant, cents, at, spentBy, createdBy).update();
    }

    private static RequestPostProcessor admin() {
        return user("admin").roles("ADMIN");
    }

    private String csv(String from, String to) throws Exception {
        return mockMvc.perform(get("/export/expenses.csv").param("from", from).param("to", to).with(admin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void adminDownloadsCsvWithHeaderAndRows() throws Exception {
        assertThat(csv("2026-03-01", "2026-03-31")).isEqualTo(HEADER
                + "2026-03-01,Market,Food,12.50,\"Mom, the Boss\",\"Mom, the Boss\",app,t-first\r\n"
                + "2026-03-31,\"'=Evil, Inc\",Food,0.05,,,feed,t-last\r\n");
    }

    @Test
    void amountsAreDecimalStrings() throws Exception {
        assertThat(csv("2026-03-31", "2026-03-31")).contains(",0.05,").doesNotContain("5.0E");
    }

    @Test
    void rangeIsInclusive() throws Exception {
        String body = csv("2026-02-28", "2026-04-01");
        assertThat(body).contains("t-feb", "t-first", "t-last", "t-apr");
        assertThat(csv("2026-03-01", "2026-03-01")).contains("t-first").doesNotContain("t-last");
    }

    @Test
    void memberForbidden() throws Exception {
        mockMvc.perform(get("/export/expenses.csv").param("from", "2026-03-01").param("to", "2026-03-31")
                .with(user("mom").roles("MEMBER"))).andExpect(status().isForbidden());
    }

    @Test
    void anonymousRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/export/expenses.csv").accept("text/html").param("from", "2026-03-01")
                .param("to", "2026-03-31")).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
    }

    @ParameterizedTest
    @CsvSource(value = {
            "2026-03-31,2026-03-01", // inverted
            "'',2026-03-01", // blank
            "nonsense,2026-03-01", // unparseable
    })
    void missingOrInvertedRange400(String from, String to) throws Exception {
        mockMvc.perform(get("/export/expenses.csv").param("from", from).param("to", to).with(admin()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/export/expenses.csv").param("from", "2026-03-01").with(admin()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/export/expenses.csv").param("to", "2026-03-01").with(admin()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tooLongRange400() throws Exception {
        mockMvc.perform(get("/export/expenses.csv").param("from", "2020-01-01").param("to", "2025-01-02")
                .with(admin())).andExpect(status().isBadRequest());
        // Exactly five years is still fine.
        mockMvc.perform(get("/export/expenses.csv").param("from", "2020-01-01").param("to", "2025-01-01")
                .with(admin())).andExpect(status().isOk());
    }

    @Test
    void aBrokenClientConnectionSurfacesAsIOException() {
        Writer broken = new Writer() {
            private int calls;

            @Override
            public void write(char[] buffer, int offset, int length) throws IOException {
                // The header row goes through; the first data row hits the "closed" connection.
                if (++calls > 16) {
                    throw new IOException("client went away");
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };

        assertThatThrownBy(() -> new ExpenseExport(jdbc).write(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31),
                broken)).isInstanceOf(IOException.class).hasMessage("client went away");
    }

    @Test
    void filenameAndContentType() throws Exception {
        mockMvc.perform(get("/export/expenses.csv").param("from", "2026-03-01").param("to", "2026-03-31")
                        .with(admin()))
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"centry-expenses-2026-03-01-to-2026-03-31.csv\""));
    }
}
