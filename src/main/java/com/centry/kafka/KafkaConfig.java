package com.centry.kafka;

import com.centry.dto.ExpenseEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.util.backoff.FixedBackOff;

import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
@Profile("kafka")
public class KafkaConfig {

    public static final String RAW_EXPENSES_TOPIC = "raw-expenses";
    public static final String BUDGET_ALERTS_TOPIC = "budget-alerts";
    public static final String RAW_EXPENSES_DLT_TOPIC = "raw-expenses.DLT";
    public static final String RAW_EXPENSES_CONTAINER_FACTORY = "rawExpensesListenerContainerFactory";

    @Bean
    public NewTopic budgetAlertsTopic() {
        return TopicBuilder.name(BUDGET_ALERTS_TOPIC).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic rawExpensesDltTopic() {
        return TopicBuilder.name(RAW_EXPENSES_DLT_TOPIC).partitions(3).replicas(1).build();
    }

    /**
     * Container factory used only by the raw-expenses listener, so the DLT recoverer is not
     * applied to any other listener.
     */
    @Bean(RAW_EXPENSES_CONTAINER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, ExpenseEvent> rawExpensesListenerContainerFactory(
            ConsumerFactory<String, ExpenseEvent> consumerFactory,
            KafkaTemplate<String, Object> kafkaTemplate,
            ProducerFactory<String, Object> producerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, ExpenseEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler(kafkaTemplate, producerFactory));
        return factory;
    }

    DefaultErrorHandler errorHandler(KafkaTemplate<String, Object> jsonTemplate,
                                     ProducerFactory<String, Object> producerFactory) {
        // Undeserializable records arrive as raw bytes and must reach the DLT unchanged.
        KafkaTemplate<String, byte[]> bytesTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(
                producerFactory.getConfigurationProperties(), new StringSerializer(), new ByteArraySerializer()));
        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, bytesTemplate);
        templates.put(Object.class, jsonTemplate);
        // Partition -1 lets the producer choose, so raw-expenses may have more partitions than the DLT.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(templates,
                (record, ex) -> new TopicPartition(RAW_EXPENSES_DLT_TOPIC, -1));
        // Retry transient failures twice, one second apart; poison and invalid input skips retries.
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));
        handler.addNotRetryableExceptions(DeserializationException.class, MethodArgumentNotValidException.class);
        return handler;
    }
}
