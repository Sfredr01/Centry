package com.centry.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteDataDirectoryPostProcessorTest {

    private final SqliteDataDirectoryPostProcessor processor = new SqliteDataDirectoryPostProcessor();

    @TempDir
    Path tmp;

    private void run(String url) {
        MockEnvironment env = new MockEnvironment();
        if (url != null) {
            env.setProperty("spring.datasource.url", url);
        }
        processor.postProcessEnvironment(env, null);
    }

    @Test
    void ignoresMissingUrl() {
        run(null);
    }

    @Test
    void ignoresNonSqliteUrl() {
        Path dir = tmp.resolve("other");

        run("jdbc:postgresql://" + dir + "/db");

        assertThat(Files.exists(dir)).isFalse();
    }

    @Test
    void createsParentDirectoriesAndStripsQueryString() {
        Path dir = tmp.resolve("a").resolve("b");

        run("jdbc:sqlite:" + dir.resolve("centry.db") + "?journal_mode=WAL");

        assertThat(Files.isDirectory(dir)).isTrue();
        assertThat(Files.exists(dir.resolve("centry.db"))).isFalse();
    }

    @Test
    void existingDirectoryIsLeftAlone() {
        run("jdbc:sqlite:" + tmp.resolve("centry.db"));

        assertThat(Files.isDirectory(tmp)).isTrue();
    }
}
