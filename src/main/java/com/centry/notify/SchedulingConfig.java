package com.centry.notify;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on {@code @Scheduled}; tests switch it off with {@code centry.notify.retry-enabled=false}. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "centry.notify.retry-enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
