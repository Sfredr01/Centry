package com.centry.security;

import com.centry.CentryApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Each test boots the real application on its own SQLite file (command-line args beat application-test.yml).
class AdminBootstrapTest {

    @TempDir
    Path dir;

    private ConfigurableApplicationContext start(String... args) {
        String[] all = new String[args.length + 3];
        all[0] = "--spring.datasource.url=jdbc:sqlite:" + dir.resolve("centry.db");
        all[1] = "--spring.profiles.active=test";
        all[2] = "--server.port=0";
        System.arraycopy(args, 0, all, 3, args.length);
        return new SpringApplicationBuilder(CentryApplication.class).web(WebApplicationType.SERVLET).run(all);
    }

    @Test
    void createsAdminFromPropertyWhenNoUsers() {
        try (ConfigurableApplicationContext ctx =
                     start("--centry.admin.username=boss", "--centry.admin.password=a-long-enough-pw")) {
            Map<String, Object> row = ctx.getBean(JdbcClient.class)
                    .sql("SELECT username, role, display_name, password_hash, created_at FROM users")
                    .query().singleRow();
            assertThat(row).containsEntry("username", "boss").containsEntry("role", "ADMIN");
            assertThat((String) row.get("password_hash")).startsWith("{bcrypt}");
            assertThat(ctx.getBean(PasswordEncoder.class).matches("a-long-enough-pw", (String) row.get("password_hash")))
                    .isTrue();
            assertThat((String) row.get("display_name")).isNotBlank();
            assertThat((String) row.get("created_at")).startsWith("20");
        }
    }

    @Test
    void defaultsUsernameToAdmin() {
        try (ConfigurableApplicationContext ctx = start("--centry.admin.password=a-long-enough-pw")) {
            assertThat(ctx.getBean(JdbcClient.class).sql("SELECT username FROM users").query(String.class).single())
                    .isEqualTo("admin");
        }
    }

    @Test
    void failsStartupWhenNoUsersAndNoPassword() {
        assertThatThrownBy(() -> start("--centry.admin.password="))
                .hasStackTraceContaining("CENTRY_ADMIN_PASSWORD");
    }

    @Test
    void failsStartupWhenPasswordTooShort() {
        assertThatThrownBy(() -> start("--centry.admin.password=short-pw-11"))
                .hasStackTraceContaining("at least 12 characters");
    }

    @Test
    void failsStartupWhenPasswordOver72Bytes() {
        assertThatThrownBy(() -> start("--centry.admin.password=" + "é".repeat(40)))
                .hasStackTraceContaining("at most 72 bytes");
    }

    @Test
    void ignoresPropertyWhenUsersExist() {
        start("--centry.admin.password=first-password-123").close();

        // Second boot: a different (even too short) password is ignored and nothing is overwritten.
        try (ConfigurableApplicationContext ctx = start("--centry.admin.password=x")) {
            JdbcClient jdbc = ctx.getBean(JdbcClient.class);
            assertThat(jdbc.sql("SELECT count(*) FROM users").query(Integer.class).single()).isEqualTo(1);
            String hash = jdbc.sql("SELECT password_hash FROM users").query(String.class).single();
            assertThat(ctx.getBean(PasswordEncoder.class).matches("first-password-123", hash)).isTrue();
        }
        // And with the property absent entirely.
        try (ConfigurableApplicationContext ctx = start("--centry.admin.password=")) {
            assertThat(ctx.getBean(JdbcClient.class).sql("SELECT count(*) FROM users").query(Integer.class).single())
                    .isEqualTo(1);
        }
    }
}
