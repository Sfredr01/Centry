package com.centry.persistence;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// The URL comes in through the inlined test properties so it is visible to the environment
// post-processor that creates the directory (DynamicPropertySource is applied too late for that).
@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:${centry.test.data-dir}/centry.db")
@ActiveProfiles("test")
class SqliteConfigTest {

    // Parent directory does not exist yet: the app has to create it before the pool opens.
    private static final Path DATA_DIR =
            Path.of(System.getProperty("java.io.tmpdir"), "centry-cfg-" + UUID.randomUUID(), "nested");

    static {
        System.setProperty("centry.test.data-dir", DATA_DIR.toString());
    }

    @AfterAll
    static void clearProperty() {
        System.clearProperty("centry.test.data-dir");
    }

    @Autowired
    private JdbcClient jdbc;

    @Test
    void walBusyTimeoutAndForeignKeysAreOn() {
        assertThat(jdbc.sql("PRAGMA journal_mode").query(String.class).single()).isEqualToIgnoringCase("wal");
        assertThat(jdbc.sql("PRAGMA busy_timeout").query(Integer.class).single()).isEqualTo(5000);
        assertThat(jdbc.sql("PRAGMA foreign_keys").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void createsMissingDataDirectory() {
        assertThat(Files.isRegularFile(DATA_DIR.resolve("centry.db"))).isTrue();
    }
}
