package com.centry.kafka;

import com.centry.entity.ExpenseEntity;
import com.centry.repository.ExpenseRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// kafka profile against an in-JVM broker: external producer -> raw-expenses -> DB, alerts -> budget-alerts, poison -> DLT.
@SpringBootTest
@ActiveProfiles({"test", "kafka"})
@EmbeddedKafka(partitions = 1, topics = {"raw-expenses", "budget-alerts", "raw-expenses.DLT"}, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class KafkaProfileIngestTest {

    @DynamicPropertySource
    static void budget(DynamicPropertyRegistry registry) {
        registry.add("centry.budget.webhook-url", () -> "");
        registry.add("centry.budget.categories.kafkatest.monthly-limit", () -> "100.00");
        registry.add("centry.budget.categories.kafkatest.warning-threshold-percent", () -> "0.80");
    }

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private ExpenseRepository expenseRepository;

    @MockitoSpyBean
    private KafkaExpenseListener listener;

    private void send(String key, String json) {
        Map<String, Object> props = KafkaTestUtils.producerProps(broker);
        KafkaTemplate<String, String> template = new KafkaTemplate<>(
                new DefaultKafkaProducerFactory<>(props, new StringSerializer(), new StringSerializer()));
        template.send("raw-expenses", key, json);
        template.flush();
        template.destroy();
    }

    private Consumer<String, String> consumerOf(String topic) {
        Map<String, Object> props = KafkaTestUtils.consumerProps(broker, UUID.randomUUID().toString(), false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
                props, new StringDeserializer(), new StringDeserializer()).createConsumer();
        broker.consumeFromAnEmbeddedTopic(consumer, topic);
        return consumer;
    }

    // The DLT is shared by every test in this class, so look for this test's record among all of them.
    private static List<String> awaitDltRecord(Consumer<String, String> dlt, Predicate<String> mine) {
        List<String> seen = new ArrayList<>();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            dlt.poll(Duration.ofMillis(200)).forEach(r -> seen.add(r.value()));
            assertThat(seen).anyMatch(mine);
        });
        return seen;
    }

    @Test
    void messageIsRecorded() {
        String tx = "tx-" + UUID.randomUUID();
        send("Cafe", "{\"transactionId\":\"" + tx
                + "\",\"merchant\":\"Cafe\",\"amount\":4.50,\"category\":\"misc\"}");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            ExpenseEntity row = expenseRepository.findByTransactionId(tx).orElse(null);
            assertThat(row).isNotNull();
            assertThat(row.merchant()).isEqualTo("Cafe");
            assertThat(row.amount()).isEqualByComparingTo("4.50");
            assertThat(row.category()).isEqualTo("misc");
        });
    }

    @Test
    void budgetAlertIsRepublished() {
        try (Consumer<String, String> alerts = consumerOf("budget-alerts")) {
            send("Store", "{\"transactionId\":\"tx-alert\",\"merchant\":\"Store\","
                    + "\"amount\":150.00,\"category\":\"kafkatest\"}");

            ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(
                    alerts, "budget-alerts", Duration.ofSeconds(20));
            assertThat(record.key()).isEqualTo("kafkatest");
            assertThat(record.value()).contains("LIMIT_EXCEEDED").contains("kafkatest");
        }
    }

    @Test
    void invalidMessageGoesToDltWithoutRetry() throws Exception {
        String tx = "tx-invalid-" + UUID.randomUUID();
        String invalid = "{\"transactionId\":\"" + tx + "\",\"merchant\":\"Cafe\",\"amount\":-5,\"category\":\"misc\"}";
        try (Consumer<String, String> dlt = consumerOf("raw-expenses.DLT")) {
            send("Invalid", invalid);

            List<String> seen = awaitDltRecord(dlt, v -> v.contains(tx));
            // Not retryable: the listener body never ran, nothing was stored, no second DLT copy follows.
            verify(listener, never()).onMessage(argThat(e -> e != null && tx.equals(e.getTransactionId())));
            assertThat(expenseRepository.findByTransactionId(tx)).isEmpty();
            dlt.poll(Duration.ofSeconds(3)).forEach(r -> seen.add(r.value()));
            assertThat(seen).filteredOn(v -> v.contains(tx)).hasSize(1);
        }
    }

    @Test
    void redeliveredMessageIsNotDuplicated() {
        String tx = "tx-redelivered-" + UUID.randomUUID();
        String sentinel = "tx-sentinel-" + UUID.randomUUID();
        String json = "{\"transactionId\":\"%s\",\"merchant\":\"Cafe\",\"amount\":4.50,\"category\":\"misc\"}";
        send("Cafe", json.formatted(tx));
        send("Cafe", json.formatted(tx));
        // One partition keeps order, so once the sentinel is stored the duplicate has been handled too.
        send("Cafe", json.formatted(sentinel));

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(expenseRepository.findByTransactionId(sentinel)).isPresent());
        assertThat(expenseRepository.findAll()).filteredOn(e -> tx.equals(e.transactionId())).hasSize(1);
    }

    @Test
    void malformedJsonGoesToDltWithoutRetry() throws Exception {
        String poison = "{not json";
        try (Consumer<String, String> dlt = consumerOf("raw-expenses.DLT")) {
            send("Poison", poison);

            List<String> seen = awaitDltRecord(dlt, poison::equals);
            // Not retryable: the listener never ran and no second copy shows up after the backoff window.
            verify(listener, never()).onMessage(any());
            dlt.poll(Duration.ofSeconds(3)).forEach(r -> seen.add(r.value()));
            assertThat(seen).filteredOn(poison::equals).hasSize(1);
        }
    }
}
