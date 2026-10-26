package com.centry.web;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class IconsTest {

    private static final List<String> NAMES = List.of("edit", "delete", "settings", "add", "home", "budgets",
            "logout", "close", "back", "more", "check", "warning", "error");

    @Autowired
    private SpringTemplateEngine engine;

    @Test
    void everyIconIsAnAccessible24pxOutline() {
        for (String name : NAMES) {
            Document icon = Jsoup.parse(engine.process("fragments/icons", Set.of(name), new Context()));

            assertThat(icon.select("svg.icon[viewBox=0 0 24 24][aria-hidden=true][focusable=false]"))
                    .as(name).hasSize(1);
            assertThat(icon.select("svg.icon > path, svg.icon > circle")).as(name).isNotEmpty();
            // Presentation lives in CSS only, so one rule recolours every icon.
            for (Element child : icon.select("svg.icon > *")) {
                assertThat(child.attributes().asList().stream().map(a -> a.getKey()))
                        .as(name).doesNotContain("fill", "stroke", "style");
            }
        }
    }

    @Test
    void unknownIconRendersNothing() {
        // Rendering a template by selector yields nothing for a missing name; inside th:replace the same miss
        // fails the page render, so a typo cannot ship a blank icon.
        assertThat(engine.process("fragments/icons", Set.of("nope"), new Context())).doesNotContain("<svg");
    }
}
