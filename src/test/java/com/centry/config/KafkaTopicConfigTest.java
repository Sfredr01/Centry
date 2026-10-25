package com.centry.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaTopicConfigTest {

    private final KafkaTopicConfig config = new KafkaTopicConfig();

    @Test
    void topicsHaveExpectedNamesAndPartitions() {
        assertTopic(config.budgetAlertsTopic(), "budget-alerts");
        assertTopic(config.subscriptionAlertsTopic(), "subscription-alerts");
        assertTopic(config.rawExpensesDltTopic(), "raw-expenses.DLT");
    }

    private static void assertTopic(NewTopic topic, String name) {
        assertThat(topic.name()).isEqualTo(name);
        assertThat(topic.numPartitions()).isEqualTo(3);
        assertThat(topic.replicationFactor()).isEqualTo((short) 1);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void errorHandlerRetriesThenPublishesFailedRecordToDltOnSamePartition() {
        KafkaTemplate<String, Object> template = mock(KafkaTemplate.class);
        when(template.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        DefaultErrorHandler handler = config.errorHandler(template);

        ConsumerRecord<Object, Object> record = new ConsumerRecord<>("raw-expenses", 2, 7L, "Netflix", "payload");
        Exception failure = new IllegalStateException("boom");
        Consumer consumer = mock(Consumer.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(container.isRunning()).thenReturn(true);
        // The recoverer only keeps the resolved partition if the consumer sees it on the DLT.
        Node node = new Node(0, "localhost", 9092);
        when(consumer.partitionsFor(eq("raw-expenses.DLT"), any(Duration.class))).thenReturn(List.of(
                new PartitionInfo("raw-expenses.DLT", 0, node, new Node[]{node}, new Node[]{node}),
                new PartitionInfo("raw-expenses.DLT", 1, node, new Node[]{node}, new Node[]{node}),
                new PartitionInfo("raw-expenses.DLT", 2, node, new Node[]{node}, new Node[]{node})));

        // FixedBackOff with 2 retries: first two failures are retried, the third is recovered to the DLT.
        for (int i = 0; i < 3; i++) {
            handler.handleOne(failure, record, consumer, container);
        }

        ArgumentCaptor<ProducerRecord> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template, timeout(5000)).send(sent.capture());
        assertThat(sent.getValue().topic()).isEqualTo("raw-expenses.DLT");
        assertThat(sent.getValue().partition()).isEqualTo(2);
        assertThat(sent.getValue().key()).isEqualTo("Netflix");
        assertThat(sent.getValue().value()).isEqualTo("payload");
    }
}
