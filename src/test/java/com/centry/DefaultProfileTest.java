package com.centry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

// Without the kafka profile the app must run with zero Kafka beans (no broker needed).
@SpringBootTest
@ActiveProfiles("test")
class DefaultProfileTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void startsWithNoKafkaBeans() {
        assertThat(context.getBeanNamesForType(KafkaTemplate.class)).isEmpty();
        assertThat(context.getBeanNamesForType(KafkaListenerEndpointRegistry.class)).isEmpty();
        assertThat(context.getBeanNamesForType(KafkaAdmin.class)).isEmpty();
    }
}
