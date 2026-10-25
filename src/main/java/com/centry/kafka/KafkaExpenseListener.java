package com.centry.kafka;

import com.centry.dto.ExpenseEvent;
import com.centry.service.ExpenseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Feeds expenses published by an external producer on {@code raw-expenses} into the use case. */
@Component
@Profile("kafka")
@RequiredArgsConstructor
public class KafkaExpenseListener {

    private final ExpenseService expenseService;

    @KafkaListener(topics = KafkaConfig.RAW_EXPENSES_TOPIC, groupId = "centry-group",
            containerFactory = KafkaConfig.RAW_EXPENSES_CONTAINER_FACTORY)
    public void onMessage(@Payload @Valid ExpenseEvent event) {
        expenseService.record(event);
    }
}
