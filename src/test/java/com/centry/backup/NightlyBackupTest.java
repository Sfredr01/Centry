package com.centry.backup;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class NightlyBackupTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-15T08:30:00Z"), ZoneOffset.UTC);

    @TempDir
    Path dataDir;

    private SingleConnectionDataSource dataSource;
    private JdbcClient jdbc;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void database() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite:" + dataDir.resolve("centry.db"), true);
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("CREATE TABLE expense (id INTEGER PRIMARY KEY, merchant TEXT)").update();
        jdbc.sql("INSERT INTO expense (merchant) VALUES ('Market')").update();
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(NightlyBackup.class)).addAppender(logs);
    }

    @AfterEach
    void cleanUp() {
        ((Logger) LoggerFactory.getLogger(NightlyBackup.class)).detachAppender(logs);
        dataSource.destroy();
    }

    private NightlyBackup backup(int keep) {
        return new NightlyBackup(jdbc, CLOCK, new BackupProperties(true, keep));
    }

    private Path backups() {
        return dataDir.resolve("backups");
    }

    private void touch(String name) throws IOException {
        Files.createDirectories(backups());
        Files.writeString(backups().resolve(name), name);
    }

    private long names() throws IOException {
        try (Stream<Path> files = Files.list(backups())) {
            return files.count();
        }
    }

    @Test
    void writesNightlyFileThatOpensAsSqliteWithTheData() throws Exception {
        backup(14).run();

        Path file = backups().resolve("nightly-20260315.db");
        assertThat(file).isRegularFile();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             var rows = connection.createStatement().executeQuery("SELECT merchant FROM expense")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("Market");
        }
        assertThat(names()).isEqualTo(1);
    }

    @Test
    void skipsWhenTodayExists() throws Exception {
        touch("nightly-20260315.db");

        backup(14).run();

        assertThat(backups().resolve("nightly-20260315.db")).hasContent("nightly-20260315.db");
        assertThat(logs.list).noneMatch(event -> event.getLevel() == Level.ERROR);
    }

    @Test
    void prunesOldestBeyondKeepButNeverTouchesUpgradeBackups() throws Exception {
        touch("nightly-20260101.db");
        touch("nightly-20260102.db");
        touch("nightly-20260103.db");
        touch("centry-20200101T000000-v1.db");
        touch("pre-restore-20200101T000000.db");

        backup(2).run();

        try (Stream<Path> files = Files.list(backups())) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder(
                    "nightly-20260103.db", "nightly-20260315.db",
                    "centry-20200101T000000-v1.db", "pre-restore-20200101T000000.db");
        }
    }

    @Test
    void failureIsLoggedNotThrown() throws Exception {
        // A plain file where the backups directory should be makes the backup impossible.
        Files.writeString(backups(), "in the way");

        backup(14).run();

        assertThat(logs.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains("Nightly backup failed");
        });
    }
}
