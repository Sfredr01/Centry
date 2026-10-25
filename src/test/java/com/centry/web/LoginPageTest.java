package com.centry.web;

import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class LoginPageTest {

    @Autowired
    private MockMvc mockMvc;

    private Document loginPage(String query) throws Exception {
        Document page = Html.of(mockMvc.perform(get("/login" + query).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk()));
        // Our own page, not Spring's generated one: Pico stylesheet and a phone viewport.
        assertThat(page.select("link[rel=stylesheet][href=/vendor/pico.min.css]")).hasSize(1);
        assertThat(page.select("meta[name=viewport]")).hasSize(1);
        return page;
    }

    @Test
    void rendersCustomLoginFormWithRememberMe() throws Exception {
        Document page = loginPage("");

        assertThat(page.select("form[method=post][action=/login]")).hasSize(1);
        assertThat(page.select("form input[name=username]")).hasSize(1);
        assertThat(page.select("form input[name=password][type=password]")).hasSize(1);
        assertThat(page.select("form input[type=checkbox][name=remember-me]")).hasSize(1);
        assertThat(page.select("form input[type=hidden][name=_csrf]")).hasSize(1);
        assertThat(page.select("[role=alert]")).isEmpty();
    }

    @Test
    void showsErrorOnBadCredentials() throws Exception {
        mockMvc.perform(post("/login").with(csrf()).param("username", "admin").param("password", "wrong-password"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login?error"));

        assertThat(loginPage("?error").select("[role=alert]")).hasSize(1);
    }

    @Test
    void showsMessageAfterLogout() throws Exception {
        assertThat(loginPage("?logout").select("[role=status]")).hasSize(1);
    }

    @Test
    void loginRedirectsToDashboard() throws Exception {
        mockMvc.perform(post("/login").with(csrf()).param("username", "admin")
                        .param("password", "test-admin-password"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/"));
    }
}
