package com.centry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

// Context must start with no external broker or on-disk database (CI and offline dev).
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = "raw-expenses")
class CentryApplicationTests {

	@Autowired
	private DataSource dataSource;

	@Autowired
	private KafkaProperties kafkaProperties;

	@Autowired
	private EmbeddedKafkaBroker embeddedKafka;

	@Test
	void contextLoads() {
	}

	@Test
	void usesInMemoryDatabase() throws Exception {
		try (Connection connection = dataSource.getConnection()) {
			assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:");
		}
	}

	@Test
	void usesEmbeddedKafkaBroker() {
		assertThat(kafkaProperties.getBootstrapServers())
				.containsExactly(embeddedKafka.getBrokersAsString());
	}
}
