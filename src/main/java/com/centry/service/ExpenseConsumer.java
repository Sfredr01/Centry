package com.centry.service;

import com.centry.dto.ExpenseEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ExpenseConsumer {

    @KafkaListener(topics = "raw-expenses", groupId = "centry-group")
    public void consumeRawExpense(ExpenseEvent event) {
        log.info(" Successfully received event from Kafka [raw-expenses]: merchant='{}', amount=${}, category='{}'",
                event.getMerchant(), event.getAmount(), event.getCategory());
    }
}