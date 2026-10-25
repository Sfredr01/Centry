package com.centry.kafka;

import com.centry.model.BudgetAlertEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Republishes in-process budget alerts to the {@code budget-alerts} topic, keyed by category. */
@Component
@Profile("kafka")
@RequiredArgsConstructor
public class KafkaAlertBridge {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    // AFTER_COMMIT: nothing is published to Kafka for an expense that rolled back.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void republish(BudgetAlertEvent event) {
        kafkaTemplate.send(KafkaConfig.BUDGET_ALERTS_TOPIC, event.getCategory(), event);
    }
}
