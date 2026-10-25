package com.centry.controller;

import com.centry.dto.ExpenseEvent;
import com.centry.service.ExpenseProducer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ExpenseController.class)
class ExpenseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ExpenseProducer expenseProducer;

    @Test
    void testCreateExpenseEndpoint() throws Exception {
        ExpenseEvent event = ExpenseEvent.builder()
                .transactionId("tx-123")
                .merchant("Coffee Shop")
                .amount(BigDecimal.valueOf(4.50))
                .category("dining")
                .timestamp(LocalDateTime.now())
                .build();

        doNothing().when(expenseProducer).sendExpense(any(ExpenseEvent.class));

        mockMvc.perform(post("/api/v1/expenses")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(event)))
                .andExpect(status().isOk());
    }
}
