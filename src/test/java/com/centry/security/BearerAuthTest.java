package com.centry.security;

import com.centry.repository.ApiTokenRepository;
import com.centry.web.FixedClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class BearerAuthTest {

    private static final String EXPENSE = "{\"merchant\":\"Cafe\",\"amount\":4.50,\"category\":\"misc\"}";
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 1, 1, 0, 0);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ApiTokenRepository tokens;

    private long momId;
    private String momToken;

    @BeforeEach
    void household() {
        jdbc.sql("DELETE FROM api_token").update();
        jdbc.sql("DELETE FROM expense").update();
        jdbc.sql("DELETE FROM users WHERE username <> 'admin'").update();
        momId = addUser("mom", "{noop}not-used");
        momToken = tokenFor(momId);
    }

    private long addUser(String username, String hash) {
        return jdbc.sql("INSERT INTO users (username, display_name, password_hash, role, created_at) "
                        + "VALUES (?, ?, ?, 'MEMBER', '2026-01-01T00:00:00') RETURNING id")
                .params(username, username, hash).query(Long.class).single();
    }

    private String tokenFor(long userId) {
        String token = ApiTokens.generate();
        tokens.insert(userId, "phone", ApiTokens.hash(token), CREATED);
        return token;
    }

    private ResultActions record(String authorization) throws Exception {
        return mockMvc.perform(post("/api/v1/expenses").header(HttpHeaders.AUTHORIZATION, authorization)
                .contentType(MediaType.APPLICATION_JSON).content(EXPENSE));
    }

    @Test
    void bearerRecordsExpenseAttributedToOwner() throws Exception {
        record("Bearer " + momToken).andExpect(status().isCreated());

        assertThat(jdbc.sql("SELECT created_by FROM expense").query(Long.class).single()).isEqualTo(momId);
        assertThat(jdbc.sql("SELECT spent_by FROM expense").query(Long.class).single()).isEqualTo(momId);
    }

    @Test
    void schemeIsCaseInsensitive() throws Exception {
        record("bearer " + momToken).andExpect(status().isCreated());
    }

    @Test
    void bearerOnOtherApiPath403() throws Exception {
        mockMvc.perform(get("/api/v1/budgets").header(HttpHeaders.AUTHORIZATION, "Bearer " + momToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/expenses/1").header(HttpHeaders.AUTHORIZATION, "Bearer " + momToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/tokens/1").header(HttpHeaders.AUTHORIZATION, "Bearer " + momToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void bearerOnBrowserPage403OrRedirect() throws Exception {
        // Chosen behaviour: 403, not a redirect to the login page. A token is not a browser session.
        mockMvc.perform(get("/").accept(MediaType.TEXT_HTML)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + momToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void revokedToken401() throws Exception {
        jdbc.sql("DELETE FROM api_token").update();

        record("Bearer " + momToken).andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(content().string(""));
        assertThat(jdbc.sql("SELECT count(*) FROM expense").query(Integer.class).single()).isZero();
    }

    @Test
    void unknownToken401() throws Exception {
        record("Bearer " + ApiTokens.generate()).andExpect(status().isUnauthorized());
    }

    @Test
    void malformedHeader401() throws Exception {
        for (String header : new String[] {"Bearer", "Bearer ", "Bearer nonsense", "Bearer  " + momToken + "x",
                "Bearer " + momToken.substring(1), "Bearerx" + momToken}) {
            record(header).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void passwordlessOwner401() throws Exception {
        String kidToken = tokenFor(addUser("kid", null));

        record("Bearer " + kidToken).andExpect(status().isUnauthorized());
    }

    @Test
    void updatesLastUsed() throws Exception {
        record("Bearer " + momToken).andExpect(status().isCreated());

        assertThat(jdbc.sql("SELECT last_used_at FROM api_token").query(String.class).single())
                .isEqualTo("2026-03-15T10:00:00");
    }

    @Test
    void noCsrfNeededForBearer() throws Exception {
        // No csrf() post-processor anywhere in this class, so the 201 proves Bearer is exempt. The same request
        // with no Authorization header at all is still stopped by CSRF: the exemption was not widened.
        mockMvc.perform(post("/api/v1/expenses").contentType(MediaType.APPLICATION_JSON).content(EXPENSE))
                .andExpect(status().isForbidden());
        record("Bearer " + momToken).andExpect(status().isCreated());
    }

    @Test
    void basicStillWorksAlongsideBearer() throws Exception {
        mockMvc.perform(get("/api/v1/budgets").with(org.springframework.security.test.web.servlet.request
                .SecurityMockMvcRequestPostProcessors.httpBasic("admin", "test-admin-password")))
                .andExpect(status().isOk());
    }
}
