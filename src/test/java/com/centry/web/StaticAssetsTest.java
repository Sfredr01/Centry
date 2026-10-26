package com.centry.web;

import com.jayway.jsonpath.JsonPath;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Anonymous unless a test says otherwise: these assets must load on the login page.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class StaticAssetsTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void manifestServedAnonymouslyWithStartUrl() throws Exception {
        String manifest = mockMvc.perform(get("/manifest.webmanifest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Centry"))
                .andExpect(jsonPath("$.start_url").value("/add"))
                .andExpect(jsonPath("$.display").value("standalone"))
                .andExpect(jsonPath("$.theme_color").value("#2f5d3a"))
                .andExpect(jsonPath("$.background_color").value("#f5f1e8"))
                .andReturn().getResponse().getContentAsString();

        // Every icon the manifest lists is really served, without logging in.
        for (String src : JsonPath.<List<String>>read(manifest, "$.icons[*].src")) {
            mockMvc.perform(get(src)).andExpect(status().isOk());
        }
        assertThat(JsonPath.<List<String>>read(manifest, "$.icons[*].src")).isNotEmpty();
    }

    @Test
    void vendoredHtmxAndPicoServedAnonymously() throws Exception {
        mockMvc.perform(get("/vendor/htmx.min.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("htmx")));
        mockMvc.perform(get("/vendor/pico.min.css"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Pico CSS")));
    }

    @Test
    void noExternalScriptOrStylesheetUrls() throws Exception {
        for (String path : List.of("/login", "/", "/add", "/budgets")) {
            Document page = Html.of(mockMvc.perform(get(path).with(user("admin").roles("ADMIN")))
                    .andExpect(status().isOk()));
            // The pages do load the vendored copies (and not, say, only Spring's generated stylesheet).
            assertThat(page.select("link[rel=stylesheet][href=/vendor/pico.min.css]")).as(path).hasSize(1);
            for (Element tag : page.select("script[src], link[rel=stylesheet][href]")) {
                String url = tag.hasAttr("src") ? tag.attr("src") : tag.attr("href");
                assertThat(url).as(path + " " + tag).startsWith("/").doesNotStartWith("//");
            }
        }
    }
}
