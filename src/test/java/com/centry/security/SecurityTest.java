package com.centry.security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// No AdminBasicAuth here: every request states who it is.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityTest {

    private static final String ADMIN_PASSWORD = "test-admin-password";
    private static final String MEMBER_PASSWORD = "member-password-1";
    private static final String BUDGET = "{\"monthlyLimit\":50.00}";
    private static final String EXPENSE = "{\"merchant\":\"Cafe\",\"amount\":4.50,\"category\":\"misc\"}";
    private static final String NEW_USER = "{\"username\":\"newbie\",\"displayName\":\"New\",\"role\":\"MEMBER\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void household() {
        // Expenses reference their creator, so they go before the users do.
        jdbc.sql("DELETE FROM expense").update();
        jdbc.sql("DELETE FROM users WHERE username <> 'admin'").update();
        jdbc.sql("DELETE FROM persistent_logins").update();
        jdbc.sql("DELETE FROM budget").update();
        addUser("mom", passwordEncoder.encode(MEMBER_PASSWORD), "MEMBER");
        addUser("kid", null, "MEMBER");
    }

    private void addUser(String username, String hash, String role) {
        jdbc.sql("INSERT INTO users (username, display_name, password_hash, role, created_at) "
                + "VALUES (?, ?, ?, ?, '2026-01-01T00:00:00')").params(username, username, hash, role).update();
    }

    private static RequestPostProcessor admin() {
        return httpBasic("admin", ADMIN_PASSWORD);
    }

    private static RequestPostProcessor mom() {
        return httpBasic("mom", MEMBER_PASSWORD);
    }

    @Test
    void anonymousPageRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/").accept(MediaType.TEXT_HTML))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login"));
        mockMvc.perform(get("/login").accept(MediaType.TEXT_HTML)).andExpect(status().isOk());
    }

    @Test
    void anonymousApi401WithoutWwwAuthenticate() throws Exception {
        mockMvc.perform(get("/api/v1/budgets"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
        // A browser asking for HTML on an API path still gets a plain 401, never a redirect or Basic prompt.
        mockMvc.perform(get("/api/v1/budgets").accept(MediaType.TEXT_HTML))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
    }

    @Test
    void healthPublicNoDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}", true));
    }

    @Test
    void metricsAndPrometheusNotExposed() throws Exception {
        for (String path : new String[] {"/actuator/metrics", "/actuator/prometheus", "/actuator/info",
                "/actuator/health/db"}) {
            mockMvc.perform(get(path).with(admin())).andExpect(status().isNotFound());
            mockMvc.perform(get(path)).andExpect(status().is4xxClientError());
        }
    }

    @Test
    void memberCanRecordExpenseAndReadBudgets() throws Exception {
        mockMvc.perform(post("/api/v1/expenses").with(mom()).contentType(MediaType.APPLICATION_JSON)
                .content(EXPENSE)).andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/budgets").with(mom())).andExpect(status().isOk());
    }

    @Test
    void memberCannotPutOrDeleteBudgetsOrManageUsers() throws Exception {
        mockMvc.perform(put("/api/v1/budgets/food").with(mom()).contentType(MediaType.APPLICATION_JSON)
                .content(BUDGET)).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/budgets/food").with(mom())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/users").with(mom())).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/users").with(mom()).contentType(MediaType.APPLICATION_JSON)
                .content(NEW_USER)).andExpect(status().isForbidden());
    }

    @Test
    void adminCanManageBudgetsAndUsers() throws Exception {
        mockMvc.perform(put("/api/v1/budgets/food").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content(BUDGET)).andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/budgets/food").with(admin())).andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/users").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content(NEW_USER)).andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/users").with(admin())).andExpect(status().isOk());
    }

    @Test
    void basicAuthWorksForApi() throws Exception {
        mockMvc.perform(get("/api/v1/budgets").with(admin())).andExpect(status().isOk());
        // Case-insensitive usernames: the column is COLLATE NOCASE.
        mockMvc.perform(get("/api/v1/budgets").with(httpBasic("MOM", MEMBER_PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    void basicAuthWithWrongPassword401() throws Exception {
        mockMvc.perform(get("/api/v1/budgets").with(httpBasic("admin", "wrong-password-xx")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
        mockMvc.perform(get("/api/v1/budgets").with(httpBasic("nobody", "whatever-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
    }

    @Test
    void formLoginPostWithoutCsrf403() throws Exception {
        mockMvc.perform(post("/login").param("username", "mom").param("password", MEMBER_PASSWORD))
                .andExpect(status().isForbidden());
        // Basic is no way around CSRF outside /api.
        mockMvc.perform(post("/logout").with(mom())).andExpect(status().isForbidden());
    }

    @Test
    void formLoginIssuesRememberMeCookie() throws Exception {
        Cookie remember = mockMvc.perform(post("/login").with(csrf()).param("username", "mom")
                        .param("password", MEMBER_PASSWORD).param("remember-me", "on"))
                .andExpect(status().isFound())
                .andExpect(cookie().exists("remember-me"))
                .andExpect(cookie().httpOnly("remember-me", true))
                .andExpect(cookie().maxAge("remember-me", 30 * 24 * 60 * 60))
                .andReturn().getResponse().getCookie("remember-me");

        assertThat(jdbc.sql("SELECT count(*) FROM persistent_logins WHERE username = 'mom'")
                .query(Integer.class).single()).isEqualTo(1);
        // A brand-new "browser" with only the cookie (no session) is recognised via the stored token.
        mockMvc.perform(get("/api/v1/budgets").cookie(remember)).andExpect(status().isOk());
    }

    @Test
    void userWithoutPasswordCannotLogIn() throws Exception {
        for (String attempt : new String[] {"", "anything-at-all-1"}) {
            mockMvc.perform(get("/api/v1/budgets").with(httpBasic("kid", attempt)))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/login").with(csrf()).param("username", "kid").param("password", "anything-at-all-1"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login?error"));
    }
}
