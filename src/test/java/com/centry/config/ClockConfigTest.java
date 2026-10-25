package com.centry.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class ClockConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ClockConfig.class);

    @Test
    void zoneDefaultsToSystemZone() {
        runner.run(ctx -> assertThat(ctx.getBean(Clock.class).getZone()).isEqualTo(ZoneId.systemDefault()));
    }

    @Test
    void zoneComesFromProperty() {
        runner.withPropertyValues("centry.zone=Pacific/Auckland")
                .run(ctx -> assertThat(ctx.getBean(Clock.class).getZone()).isEqualTo(ZoneId.of("Pacific/Auckland")));
    }
}
