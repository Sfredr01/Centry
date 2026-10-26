package com.centry.web;

import com.centry.model.Role;
import com.centry.repository.ExpenseRepository;
import com.centry.repository.UserRepository;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class LayoutTest {

    private static final List<String> PAGES = List.of("/", "/add", "/budgets", "/subscriptions");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private ExpenseRepository expenses;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void clean() {
        expenses.deleteAll();
        jdbc.sql("DELETE FROM users WHERE username <> 'admin'").update();
    }

    private Document page(String path) throws Exception {
        return Html.of(mockMvc.perform(get(path).with(user("mom").roles("MEMBER"))).andExpect(status().isOk()));
    }

    @Test
    void pagesCarryCsrfMetaAndHtmxHeaders() throws Exception {
        for (String path : PAGES) {
            Document page = page(path);

            String token = page.selectFirst("meta[name=_csrf]").attr("content");
            String header = page.selectFirst("meta[name=_csrf_header]").attr("content");
            assertThat(token).as(path).isNotBlank();
            assertThat(header).as(path).isEqualTo("X-CSRF-TOKEN");
            // htmx copies body[hx-headers] onto every request it sends.
            assertThat(page.body().attr("hx-headers")).as(path)
                    .isEqualTo("{\"" + header + "\":\"" + token + "\"}");
            assertThat(page.select("meta[name=viewport]")).as(path).hasSize(1);
            assertThat(page.select("link[rel=manifest][href=/manifest.webmanifest]")).as(path).hasSize(1);
            assertThat(page.select("script[src=/vendor/htmx.min.js]")).as(path).hasSize(1);
        }
    }

    @Test
    void everyPageLoadsTheAppScriptDeferred() throws Exception {
        for (String path : PAGES) {
            assertThat(page(path).select("script[src=/js/app.js][defer]")).as(path).hasSize(1);
        }
        Document login = Html.of(mockMvc.perform(get("/login")).andExpect(status().isOk()));
        assertThat(login.select("script[src=/js/app.js][defer]")).hasSize(1);
    }

    @Test
    void appBarShowsAvatarMenuWithNameAndLogout() throws Exception {
        Document page = page("/");

        assertThat(page.select("header a.brand[href=/] img[src=/icons/icon.svg]")).hasSize(1);
        assertThat(page.select("header a.brand").text()).isEqualTo("Centry");
        // No users row for "mom" here, so the login name stands in for the display name.
        Element summary = page.selectFirst("header details.dropdown.account-menu > summary");
        assertThat(summary.attr("aria-label")).isEqualTo("Account menu for mom");
        assertThat(summary.select(".avatar").text()).isEqualTo("M");
        assertThat(page.select("header details ul form[method=post][action=/logout] input[name=_csrf]")).hasSize(1);
        Element logout = page.selectFirst("header details ul form[action=/logout] button");
        assertThat(logout.text()).isEqualTo("Log out");
        assertThat(logout.select("svg.icon")).hasSize(1);
    }

    @Test
    void avatarUsesTheStoredDisplayName() throws Exception {
        users.insert("sam", "Sam Rivera", null, Role.MEMBER, LocalDateTime.of(2026, 1, 1, 0, 0));

        Document page = Html.of(mockMvc.perform(get("/").with(user("sam").roles("MEMBER"))).andExpect(status().isOk()));

        assertThat(page.select("header .avatar").text()).isEqualTo("SR");
        assertThat(page.select("header .account-name").text()).isEqualTo("Sam Rivera");
    }

    @Test
    void avatarFallsBackToTheLoginWhenTheStoredNameIsBlank() throws Exception {
        users.insert("sam", " ", null, Role.MEMBER, LocalDateTime.of(2026, 1, 1, 0, 0));

        Document page = Html.of(mockMvc.perform(get("/").with(user("sam").roles("MEMBER"))).andExpect(status().isOk()));

        assertThat(page.select("header .account-name").text()).isEqualTo("sam");
    }

    @Test
    void noSettingsLinkUntilASettingsPageExists() throws Exception {
        assertThat(page("/").select("header a[href=/settings]")).isEmpty();
    }

    @Test
    void tabBarLinksEveryPageAndMarksTheCurrentOne() throws Exception {
        for (String path : PAGES) {
            Document page = page(path);

            // Bottom navigation for thumbs: Dashboard, Add, Budgets, Subs.
            assertThat(page.select("nav.tabbar a").eachAttr("href")).as(path)
                    .containsExactly("/", "/add", "/budgets", "/subscriptions");
            assertThat(page.select("nav.tabbar svg.icon")).as(path).hasSize(4);
            assertThat(page.select("nav.tabbar a[aria-current=page]").eachAttr("href")).as(path).containsExactly(path);
            // The Add tab is an icon only, so it needs a name.
            assertThat(page.select("nav.tabbar a[href=/add]").attr("aria-label")).as(path).isEqualTo("Add expense");
        }
    }

    @Test
    void tabBarHasSubscriptionsTab() throws Exception {
        var tab = page("/").selectFirst("nav.tabbar a[href=/subscriptions]");

        assertThat(tab.text()).isEqualTo("Subs");
        assertThat(tab.select("svg[aria-hidden=true]")).hasSize(1);
    }

    @Test
    void loginPageHasNoTabBar() throws Exception {
        Document login = Html.of(mockMvc.perform(get("/login")).andExpect(status().isOk()));

        assertThat(login.select("nav.tabbar")).isEmpty();
        assertThat(login.select("details.account-menu")).isEmpty();
    }

    @Test
    void themeColorIsForestByDayAndForestFloorByNight() throws Exception {
        Document page = page("/");

        assertThat(page.select("meta[name=theme-color][media=(prefers-color-scheme: light)]").attr("content"))
                .isEqualTo("#2f5d3a");
        assertThat(page.select("meta[name=theme-color][media=(prefers-color-scheme: dark)]").attr("content"))
                .isEqualTo("#121812");
    }
}
