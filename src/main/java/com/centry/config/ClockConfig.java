package com.centry.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/** Single source of "now" for the app; the zone is {@code centry.zone}, default the system zone. */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock(@Value("${centry.zone:#{T(java.time.ZoneId).systemDefault().getId()}}") ZoneId zone) {
        return Clock.system(zone);
    }
}
