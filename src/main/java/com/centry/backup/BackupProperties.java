package com.centry.backup;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Nightly backup settings. The schedule itself ({@code centry.backup.cron}) is read by {@code @Scheduled}.
 *
 * @param enabled whether the nightly job exists at all
 * @param keep how many {@code nightly-*.db} files to keep
 */
@ConfigurationProperties("centry.backup")
public record BackupProperties(@DefaultValue("true") boolean enabled, @DefaultValue("14") int keep) {
}
