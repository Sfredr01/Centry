package com.centry.backup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;

/**
 * A consistent copy of the database every night, written with {@code VACUUM INTO} so it is safe while the app
 * is running. Files are {@code nightly-YYYYMMDD.db} next to the database: the {@code nightly-} prefix keeps them
 * apart from the {@code centry-*.db} backups of scripts/upgrade.sh, which prune on their own count.
 * Whether it runs at all follows {@code centry.notify.retry-enabled} too, because that switch owns
 * {@code @EnableScheduling}.
 */
@Component
@EnableConfigurationProperties(BackupProperties.class)
@ConditionalOnProperty(name = "centry.backup.enabled", havingValue = "true", matchIfMissing = true)
public class NightlyBackup {

    private static final Logger log = LoggerFactory.getLogger(NightlyBackup.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String PREFIX = "nightly-";
    private static final String SUFFIX = ".db";

    private final JdbcClient jdbc;
    private final Clock clock;
    private final BackupProperties properties;

    NightlyBackup(JdbcClient jdbc, Clock clock, BackupProperties properties) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
    }

    /** Never throws: a failed backup must not kill the scheduler, and the next night tries again. */
    @Scheduled(cron = "${centry.backup.cron:0 30 3 * * *}",
            zone = "${centry.zone:#{T(java.time.ZoneId).systemDefault().getId()}}")
    public void run() {
        try {
            Path directory = backupDirectory();
            Path target = directory.resolve(PREFIX + DAY.format(LocalDate.now(clock)) + SUFFIX);
            if (Files.exists(target)) {
                log.info("Backup {} already exists, skipping", target.getFileName());
                return;
            }
            copyTo(target);
            log.info("Backed up the database to {}", target);
            prune(directory);
        } catch (Exception e) {
            log.error("Nightly backup failed", e);
        }
    }

    /** The directory of the database file itself, so backups follow wherever CENTRY_DATA_DIR points. */
    private Path backupDirectory() throws IOException {
        String database = jdbc.sql("SELECT file FROM pragma_database_list WHERE name = 'main'")
                .query(String.class).single();
        Path directory = Path.of(database).toAbsolutePath().getParent().resolve("backups");
        return Files.createDirectories(directory);
    }

    /** Written under a temporary name and moved into place, so a half-written file never counts as today's backup. */
    private void copyTo(Path target) throws IOException {
        Path partial = target.resolveSibling(target.getFileName() + ".partial");
        Files.deleteIfExists(partial);
        try {
            // VACUUM INTO fails if the file exists, hence the delete above.
            jdbc.sql("VACUUM INTO ?").param(partial.toString()).update();
            Files.move(partial, target);
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    private void prune(Path directory) throws IOException {
        List<Path> nightly;
        try (Stream<Path> files = Files.list(directory)) {
            nightly = files.filter(p -> p.getFileName().toString().startsWith(PREFIX)
                    && p.getFileName().toString().endsWith(SUFFIX)).sorted().toList();
        }
        for (Path old : nightly.subList(0, Math.max(0, nightly.size() - properties.keep()))) {
            Files.delete(old);
        }
    }
}
