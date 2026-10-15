package com.centry.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaTopicConfig {

    public static final String BUDGET_ALERTS_TOPIC = "budget-alerts";
    public static final String SUBSCRIPTION_ALERTS_TOPIC = "subscription-alerts";
    public static final String RAW_EXPENSES_DLT_TOPIC = "raw-expenses.DLT";

    @Bean
    public NewTopic budgetAlertsTopic() {
        return TopicBuilder.name(BUDGET_ALERTS_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic subscriptionAlertsTopic() {
        return TopicBuilder.name(SUBSCRIPTION_ALERTS_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic rawExpensesDltTopic() {
        return TopicBuilder.name(RAW_EXPENSES_DLT_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public DefaultErrorHandler errorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(RAW_EXPENSES_DLT_TOPIC, record.partition()));
        // Retry 2 times with 1-second interval, then send to DLT
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));
    }
}
