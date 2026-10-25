package com.centry.service;

import com.centry.dto.ExpenseEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class ExpenseProducerTest {

    @Mock
    private KafkaTemplate<String, ExpenseEvent> kafkaTemplate;

    @Test
    void sendExpensePublishesToRawExpensesKeyedByMerchant() {
        ExpenseEvent event = ExpenseEvent.builder()
                .transactionId("tx-1")
                .merchant("Netflix")
                .amount(new BigDecimal("15.99"))
                .category("Entertainment")
                .timestamp(LocalDateTime.of(2025, 1, 2, 3, 4))
                .build();

        new ExpenseProducer(kafkaTemplate).sendExpense(event);

        verify(kafkaTemplate).send("raw-expenses", "Netflix", event);
        verifyNoMoreInteractions(kafkaTemplate);
    }
}
