package com.centry.controller;

import com.centry.AdminBasicAuth;
import org.springframework.context.annotation.Import;
import com.centry.entity.ExpenseEntity;
import com.centry.repository.ExpenseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AdminBasicAuth.class)
class BudgetApiTest {

    // "Now" is March 2026 for every request in this class.
    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-03-15T10:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ExpenseRepository expenses;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void clean() {
        expenses.deleteAll();
        jdbc.sql("DELETE FROM budget").update();
    }

    private ResultActions putBudget(String category, String json) throws Exception {
        return mockMvc.perform(put("/api/v1/budgets/" + category).contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private void spend(String category, String amount, LocalDateTime at) {
        expenses.save(new ExpenseEntity(null, java.util.UUID.randomUUID().toString(), "Shop",
                new BigDecimal(amount), category, at));
    }

    @Test
    void putCreatesAndReturnsStatus() throws Exception {
        putBudget("Groceries", "{\"monthlyLimit\":600.00}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("groceries"))
                .andExpect(jsonPath("$.monthlyLimit").value(600.00))
                .andExpect(jsonPath("$.warnPercent").value(80))
                .andExpect(jsonPath("$.warningAmount").value(480.00))
                .andExpect(jsonPath("$.spent").value(0.00))
                .andExpect(jsonPath("$.level").value("OK"));
    }

    @Test
    void putUpdatesExisting() throws Exception {
        putBudget("groceries", "{\"monthlyLimit\":600.00,\"warnPercent\":80}").andExpect(status().isOk());
        spend("Groceries", "300.00", LocalDateTime.of(2026, 3, 2, 9, 0));

        putBudget(" GROCERIES ", "{\"monthlyLimit\":1000.00,\"warnPercent\":25}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyLimit").value(1000.00))
                .andExpect(jsonPath("$.warnPercent").value(25))
                .andExpect(jsonPath("$.warningAmount").value(250.00))
                .andExpect(jsonPath("$.spent").value(300.00))
                .andExpect(jsonPath("$.level").value("WARNING_EXCEEDED"));

        mockMvc.perform(get("/api/v1/budgets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "missing limit|{\"warnPercent\":80}|monthlyLimit",
            "zero limit|{\"monthlyLimit\":0}|monthlyLimit",
            "negative limit|{\"monthlyLimit\":-5.00}|monthlyLimit",
            "sub-cent limit|{\"monthlyLimit\":10.001}|monthlyLimit",
            "ten digit limit|{\"monthlyLimit\":1234567890.00}|monthlyLimit",
            "warn percent zero|{\"monthlyLimit\":10.00,\"warnPercent\":0}|warnPercent",
            "warn percent hundred|{\"monthlyLimit\":10.00,\"warnPercent\":100}|warnPercent"})
    void putInvalid400(String name, String json, String field) throws Exception {
        putBudget("groceries", json)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors." + field).isString());

        mockMvc.perform(get("/api/v1/budgets")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void putMalformedJson400() throws Exception {
        putBudget("groceries", "{not json")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void listShowsCurrentMonthSpend() throws Exception {
        putBudget("dining", "{\"monthlyLimit\":100.00,\"warnPercent\":80}").andExpect(status().isOk());
        putBudget("bills", "{\"monthlyLimit\":500.00}").andExpect(status().isOk());
        spend("Dining", "85.00", LocalDateTime.of(2026, 3, 1, 0, 0));
        // Other months do not count toward March.
        spend("dining", "40.00", LocalDateTime.of(2026, 2, 28, 23, 59, 59));
        spend("dining", "40.00", LocalDateTime.of(2026, 4, 1, 0, 0));

        mockMvc.perform(get("/api/v1/budgets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].category").value("bills"))
                .andExpect(jsonPath("$[0].spent").value(0.00))
                .andExpect(jsonPath("$[0].level").value("OK"))
                .andExpect(jsonPath("$[1].category").value("dining"))
                .andExpect(jsonPath("$[1].monthlyLimit").value(100.00))
                .andExpect(jsonPath("$[1].warnPercent").value(80))
                .andExpect(jsonPath("$[1].warningAmount").value(80.00))
                .andExpect(jsonPath("$[1].spent").value(85.00))
                .andExpect(jsonPath("$[1].level").value("WARNING_EXCEEDED"));
    }

    @Test
    void deleteRemoves204() throws Exception {
        putBudget("groceries", "{\"monthlyLimit\":600.00}").andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/budgets/Groceries")).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/budgets")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void deleteUnknown404() throws Exception {
        mockMvc.perform(delete("/api/v1/budgets/nothing"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));
    }
}
