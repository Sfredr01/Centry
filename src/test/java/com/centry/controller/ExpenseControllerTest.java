package com.centry.controller;

import com.centry.config.ClockConfig;
import com.centry.dto.ExpenseEvent;
import org.springframework.context.annotation.Import;
import com.centry.service.ExpenseService;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ExpenseController.class)
@Import(ClockConfig.class)
class ExpenseControllerTest {

    private static final String ACCEPTED = "Expense recorded.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ExpenseService expenseService;

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

        mockMvc.perform(post("/api/v1/expenses")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(event)))
                .andExpect(status().isOk())
                .andExpect(content().string(ACCEPTED));

        ArgumentCaptor<ExpenseEvent> captor = ArgumentCaptor.forClass(ExpenseEvent.class);
        verify(expenseService).record(captor.capture());
        ExpenseEvent sent = captor.getValue();
        assertThat(sent.getTransactionId()).isEqualTo("tx-123");
        assertThat(sent.getTimestamp()).isEqualTo(timestamp);
        assertThat(sent.getMerchant()).isEqualTo("Coffee Shop");
        assertThat(sent.getAmount()).isEqualByComparingTo("4.50");
        assertThat(sent.getCategory()).isEqualTo("dining");
    }

    @Test
    void missingTransactionIdAndTimestampAreGenerated() throws Exception {
        LocalDateTime before = LocalDateTime.now();

        mockMvc.perform(post("/api/v1/expenses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"merchant\":\"Coffee Shop\",\"amount\":4.50,\"category\":\"dining\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(ACCEPTED));

        ArgumentCaptor<ExpenseEvent> captor = ArgumentCaptor.forClass(ExpenseEvent.class);
        verify(expenseService).record(captor.capture());
        ExpenseEvent sent = captor.getValue();
        assertThat(sent.getTransactionId()).isNotNull();
        assertThat(UUID.fromString(sent.getTransactionId()).toString()).isEqualTo(sent.getTransactionId());
        assertThat(sent.getTimestamp()).isNotNull().isAfterOrEqualTo(before);
        assertThat(sent.getMerchant()).isEqualTo("Coffee Shop");
    }
}
