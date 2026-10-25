package com.centry.kafka;

import com.centry.model.BudgetAlertEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Republishes in-process budget alerts to the {@code budget-alerts} topic, keyed by category. */
@Component
@Profile("kafka")
@RequiredArgsConstructor
public class KafkaAlertBridge {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @EventListener
    public void republish(BudgetAlertEvent event) {
        kafkaTemplate.send(KafkaConfig.BUDGET_ALERTS_TOPIC, event.getCategory(), event);
    }
}
