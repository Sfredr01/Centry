package com.centry.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.serializer.DeserializationException;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaConfigTest {

    private final KafkaConfig config = new KafkaConfig();

    @Test
    void topicsHaveExpectedNamesAndPartitions() {
        assertTopic(config.budgetAlertsTopic(), "budget-alerts");
        assertTopic(config.rawExpensesDltTopic(), "raw-expenses.DLT");
    }

    private static void assertTopic(NewTopic topic, String name) {
        assertThat(topic.name()).isEqualTo(name);
        assertThat(topic.numPartitions()).isEqualTo(3);
        assertThat(topic.replicationFactor()).isEqualTo((short) 1);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private DefaultErrorHandler handler(KafkaTemplate<String, Object> template) {
        ProducerFactory<String, Object> factory = mock(ProducerFactory.class);
        when(factory.getConfigurationProperties()).thenReturn(Map.of("bootstrap.servers", "localhost:9092"));
        return config.errorHandler(template, factory);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private KafkaTemplate<String, Object> jsonTemplate() {
        KafkaTemplate<String, Object> template = mock(KafkaTemplate.class);
        when(template.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        return template;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void handleOnce(DefaultErrorHandler handler, Exception failure) {
        ConsumerRecord<Object, Object> record = new ConsumerRecord<>("raw-expenses", 2, 7L, "Netflix", "payload");
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(container.isRunning()).thenReturn(true);
        handler.handleOne(failure, record, mock(Consumer.class), container);
    }

    @Test
    void transientFailureIsRetriedBeforeBeingRecoveredToDlt() {
        KafkaTemplate<String, Object> template = jsonTemplate();
        DefaultErrorHandler handler = handler(template);
        Exception failure = new IllegalStateException("boom");

        // FixedBackOff with 2 retries: the first two failures are retried, the third is recovered.
        handleOnce(handler, failure);
        handleOnce(handler, failure);
        verify(template, never()).send(any(ProducerRecord.class));
        handleOnce(handler, failure);

        ArgumentCaptor<ProducerRecord> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template, timeout(5000)).send(sent.capture());
        assertThat(sent.getValue().topic()).isEqualTo("raw-expenses.DLT");
        assertThat(sent.getValue().partition()).isNull();
        assertThat(sent.getValue().key()).isEqualTo("Netflix");
        assertThat(sent.getValue().value()).isEqualTo("payload");
    }

    @Test
    void deserializationFailureGoesToDltOnFirstAttempt() {
        KafkaTemplate<String, Object> template = jsonTemplate();

        handleOnce(handler(template), new DeserializationException("bad json", new byte[0], false,
                new IllegalArgumentException("bad")));

        ArgumentCaptor<ProducerRecord> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template, timeout(5000)).send(sent.capture());
        assertThat(sent.getValue().topic()).isEqualTo("raw-expenses.DLT");
    }
}
