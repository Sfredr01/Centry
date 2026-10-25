package com.centry.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import org.springframework.security.test.context.support.WithMockUser;
import com.centry.dto.ExpenseEvent;
import com.centry.entity.ExpenseEntity;
import com.centry.repository.ExpenseRepository;
import com.centry.service.ExpenseService;
import com.centry.service.ExpenseService.Recorded;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ExpenseController.class)
@WithMockUser
class ExpenseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ExpenseService expenseService;

    @MockitoBean
    private ExpenseRepository expenseRepository;

    private static ExpenseEntity stored(ExpenseEvent e) {
        return new ExpenseEntity(7L, e.getTransactionId(), e.getMerchant(), e.getAmount(), e.getCategory(),
                e.getTimestamp(), null, null);
    }

    @Test
    void testCreateExpenseEndpoint() throws Exception {
        LocalDateTime timestamp = LocalDateTime.of(2026, 3, 4, 5, 6, 7);
        ExpenseEvent event = ExpenseEvent.builder()
                .transactionId("tx-123")
                .merchant("Coffee Shop")
                .amount(BigDecimal.valueOf(4.50))
                .category("dining")
                .timestamp(timestamp)
                .build();
        when(expenseService.record(any(), any())).thenReturn(new Recorded(stored(event), true));

        mockMvc.perform(post("/api/v1/expenses").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(event)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/expenses/7"))
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.transactionId").value("tx-123"));

        ArgumentCaptor<ExpenseEvent> captor = ArgumentCaptor.forClass(ExpenseEvent.class);
        verify(expenseService).record(captor.capture(), any());
        ExpenseEvent sent = captor.getValue();
        assertThat(sent.getTransactionId()).isEqualTo("tx-123");
        assertThat(sent.getTimestamp()).isEqualTo(timestamp);
        assertThat(sent.getMerchant()).isEqualTo("Coffee Shop");
        assertThat(sent.getAmount()).isEqualByComparingTo("4.50");
        assertThat(sent.getCategory()).isEqualTo("dining");
    }

    @Test
    void replayedExpenseIs200WithLocation() throws Exception {
        ExpenseEntity existing = new ExpenseEntity(7L, "tx-1", "Coffee Shop", BigDecimal.ONE, "dining",
                LocalDateTime.of(2026, 3, 4, 5, 6, 7), null, null);
        when(expenseService.record(any(), any())).thenReturn(new Recorded(existing, false));

        mockMvc.perform(post("/api/v1/expenses").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"transactionId\":\"tx-1\",\"merchant\":\"Coffee Shop\",\"amount\":1,\"category\":\"dining\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Location", "/api/v1/expenses/7"))
                .andExpect(jsonPath("$.id").value(7));
    }

    // The service assigns a missing id and defaults a missing timestamp for every entry point (HTTP and Kafka).
    @Test
    void missingTransactionIdAndTimestampAreLeftToTheService() throws Exception {
        when(expenseService.record(any(), any())).thenReturn(new Recorded(
                new ExpenseEntity(1L, "x", "Coffee Shop", BigDecimal.ONE, "dining", LocalDateTime.now(), null, null), true));

        mockMvc.perform(post("/api/v1/expenses").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"merchant\":\"Coffee Shop\",\"amount\":4.50,\"category\":\"dining\"}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<ExpenseEvent> captor = ArgumentCaptor.forClass(ExpenseEvent.class);
        verify(expenseService).record(captor.capture(), any());
        ExpenseEvent sent = captor.getValue();
        assertThat(sent.getTransactionId()).isNull();
        assertThat(sent.getTimestamp()).isNull();
        assertThat(sent.getMerchant()).isEqualTo("Coffee Shop");
    }
}
