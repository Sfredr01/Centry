package com.centry.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    public static final String BUDGET_ALERTS_TOPIC = "budget-alerts";

    @Bean
    public NewTopic budgetAlertsTopic() {
        return TopicBuilder.name(BUDGET_ALERTS_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }
}