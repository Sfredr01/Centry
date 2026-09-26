package com.centry.service;

import com.centry.dto.ExpenseEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExpenseProducer {

    private static final String TOPIC = "raw-expenses";
    private final KafkaTemplate<String, ExpenseEvent> kafkaTemplate;

    public void sendExpense(ExpenseEvent event) {
        log.info("Publishing expense event to Kafka topic {}: {}", TOPIC, event);
        // Uses the merchant name as the Kafka record key to ensure sequential ordering per merchant
        kafkaTemplate.send(TOPIC, event.getMerchant(), event);
    }
}