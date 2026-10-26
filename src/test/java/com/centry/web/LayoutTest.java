package com.centry.web;

import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

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
    void appBarShowsBrandUsernameAndLogoutForm() throws Exception {
        Document page = page("/");

        assertThat(page.select("header a.brand[href=/] img[src=/icons/icon.svg]")).hasSize(1);
        assertThat(page.select("header a.brand").text()).isEqualTo("Centry");
        assertThat(page.select("header .signed-in span").text()).isEqualTo("mom");
        assertThat(page.select("header form[method=post][action=/logout] input[type=hidden][name=_csrf]"))
                .hasSize(1);
        assertThat(page.select("header form[action=/logout] button")).hasSize(1);
    }

    @Test
    void tabBarLinksEveryPageAndMarksTheCurrentOne() throws Exception {
        for (String path : PAGES) {
            Document page = page(path);

            // Bottom navigation for thumbs: Dashboard, Add, Budgets, Subs.
            assertThat(page.select("nav.tabbar a").eachAttr("href")).as(path)
                    .containsExactly("/", "/add", "/budgets", "/subscriptions");
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
