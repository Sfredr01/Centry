package com.centry.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;

import java.io.File;

/**
 * SQLite creates the database file but not its parent directory. This runs before any bean exists,
 * so the directory is there by the time the connection pool opens the file.
 */
public class SqliteDataDirectoryPostProcessor implements EnvironmentPostProcessor {

    private static final String URL_PROPERTY = "spring.datasource.url";
    private static final String SQLITE_PREFIX = "jdbc:sqlite:";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String url = environment.getProperty(URL_PROPERTY);
        if (url == null || !url.startsWith(SQLITE_PREFIX)) {
            return;
        }
        String path = url.substring(SQLITE_PREFIX.length());
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        // Result ignored on purpose: if the directory can't be made, SQLite reports the real cause on open.
        new File(path).getAbsoluteFile().getParentFile().mkdirs();
    }
}
