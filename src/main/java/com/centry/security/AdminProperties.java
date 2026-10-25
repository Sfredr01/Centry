package com.centry.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** First-run admin; the password comes from {@code CENTRY_ADMIN_PASSWORD} and is only read while no user exists. */
@ConfigurationProperties(prefix = "centry.admin")
public record AdminProperties(@DefaultValue("admin") String username, String password) {
}
