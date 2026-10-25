package com.centry.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A real clock moves between two taps. The retry must still be recognised as the same expense, so the form
 * may not stamp "now" itself: the service stamps it once, on creation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(QuickAddDoubleTapTest.TickingClock.class)
class QuickAddDoubleTapTest {

    @TestConfiguration
    static class TickingClock {
        @Bean
        @Primary
        Clock tickingClock() {
            Instant start = Instant.parse("2026-03-15T10:00:00Z");
            AtomicLong seconds = new AtomicLong();
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(ZoneId zone) {
                    return this;
                }

                @Override
                public Instant instant() {
                    return start.plusSeconds(seconds.getAndIncrement());
                }
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void doubleTapWhileTheClockMovesStoresOneRow() throws Exception {
        String ref = "22222222-2222-4222-8222-222222222222";
        for (int tap = 0; tap < 2; tap++) {
            mockMvc.perform(post("/add").with(user("mom").roles("MEMBER")).with(csrf())
                            .header("HX-Request", "true")
                            .param("amount", "4.00").param("category", "Food").param("merchant", "Cafe")
                            .param("date", "2026-03-15").param("clientRef", ref))
                    .andExpect(status().isOk());
        }

        assertThat(jdbc.sql("SELECT COUNT(*) FROM expense WHERE transaction_id = ?").param(ref)
                .query(Long.class).single()).isEqualTo(1L);
    }
}
