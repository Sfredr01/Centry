package com.centry.security;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiTokenApiTest {

    private static final String MEMBER_PASSWORD = "member-password-1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void household() {
        jdbc.sql("DELETE FROM api_token").update();
        jdbc.sql("DELETE FROM expense").update();
        jdbc.sql("DELETE FROM users WHERE username <> 'admin'").update();
        jdbc.sql("INSERT INTO users (username, display_name, password_hash, role, created_at) "
                + "VALUES ('mom', 'Mom', ?, 'MEMBER', '2026-01-01T00:00:00')")
                .param(passwordEncoder.encode(MEMBER_PASSWORD)).update();
    }

    private static RequestPostProcessor admin() {
        return httpBasic("admin", "test-admin-password");
    }

    private static RequestPostProcessor mom() {
        return httpBasic("mom", MEMBER_PASSWORD);
    }

    private ResultActions issue(RequestPostProcessor as, String json) throws Exception {
        return mockMvc.perform(post("/api/v1/tokens").with(as).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void issueReturnsTokenOnce() throws Exception {
        String body = issue(mom(), "{\"name\":\"Sam's iPhone\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("Sam's iPhone"))
                .andExpect(jsonPath("$.token").value(org.hamcrest.Matchers.matchesPattern("centry_[A-Za-z0-9_-]{43}")))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.token");

        // Only the hash is stored, and the list never shows the secret again.
        assertThat(jdbc.sql("SELECT token_hash FROM api_token").query(String.class).single())
                .isEqualTo(ApiTokens.hash(token)).isNotEqualTo(token);
        assertThat(mockMvc.perform(get("/api/v1/tokens").with(mom())).andReturn().getResponse().getContentAsString())
                .doesNotContain(token).doesNotContain("token");
    }

    @Test
    void twoIssuedTokensDiffer() throws Exception {
        String first = issue(mom(), "{\"name\":\"a\"}").andReturn().getResponse().getContentAsString();
        String second = issue(mom(), "{\"name\":\"a\"}").andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(first, "$.token")).isNotEqualTo((String) JsonPath.read(second, "$.token"));
    }

    @Test
    void nameRequiredAndBounded() throws Exception {
        for (String json : new String[] {"{}", "{\"name\":\"\"}", "{\"name\":\"   \"}",
                "{\"name\":\"" + "x".repeat(61) + "\"}"}) {
            issue(mom(), json).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.name").isString());
        }
        issue(mom(), "{\"name\":\"" + "x".repeat(60) + "\"}").andExpect(status().isCreated());
        assertThat(jdbc.sql("SELECT count(*) FROM api_token").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void listShowsOwnTokensWithoutSecret() throws Exception {
        issue(mom(), "{\"name\":\"phone\"}").andExpect(status().isCreated());
        issue(admin(), "{\"name\":\"admin-laptop\"}").andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/tokens").with(mom()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("phone"))
                .andExpect(jsonPath("$[0].createdAt").isString())
                .andExpect(jsonPath("$[0].lastUsedAt").doesNotExist())
                .andExpect(jsonPath("$[0].token").doesNotExist())
                .andExpect(jsonPath("$[0].tokenHash").doesNotExist());
    }

    @Test
    void revokeOwn204() throws Exception {
        int id = JsonPath.read(issue(mom(), "{\"name\":\"phone\"}").andReturn().getResponse().getContentAsString(),
                "$.id");

        mockMvc.perform(delete("/api/v1/tokens/" + id).with(mom())).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/tokens").with(mom())).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void revokeOthers404() throws Exception {
        int id = JsonPath.read(issue(mom(), "{\"name\":\"phone\"}").andReturn().getResponse().getContentAsString(),
                "$.id");

        // Not even an admin may: 404, same as an unknown id, so existence is not revealed.
        mockMvc.perform(delete("/api/v1/tokens/" + id).with(admin())).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/tokens/999999").with(mom())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/tokens").with(mom())).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void bearerCannotIssueTokens() throws Exception {
        String token = JsonPath.read(issue(mom(), "{\"name\":\"phone\"}").andReturn().getResponse()
                .getContentAsString(), "$.token");

        mockMvc.perform(post("/api/v1/tokens").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"more\"}"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.sql("SELECT count(*) FROM api_token").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void anonymous401() throws Exception {
        mockMvc.perform(get("/api/v1/tokens")).andExpect(status().isUnauthorized());
        // Without an Authorization header a write is stopped by CSRF first; with bad credentials it is a 401.
        RequestPostProcessor nobody = httpBasic("nobody", "whatever-password");
        issue(nobody, "{\"name\":\"x\"}").andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/tokens/1").with(nobody)).andExpect(status().isUnauthorized());
    }
}
