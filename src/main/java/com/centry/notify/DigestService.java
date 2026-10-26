package com.centry.notify;

import com.centry.repository.DigestQuery;
import com.centry.repository.DigestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends one summary of last month on the 1st. Inserting the {@code digest} row claims the send, so restarts,
 * the catch-up check and the cron never produce a second digest; only the inserter sends.
 *
 * <p>Delivery and retry follow {@link AlertDispatcher} and {@link AlertRetrySweeper}: {@code notified_at} is set
 * once every notifier accepted it, and an undelivered digest is retried while it is between one minute and 24
 * hours old. Off with {@code centry.digest.enabled=false}.
 */
@Service
@ConditionalOnProperty(name = "centry.digest.enabled", havingValue = "true", matchIfMissing = true)
public class DigestService {

    private static final Logger log = LoggerFactory.getLogger(DigestService.class);
    private static final Duration GRACE = Duration.ofMinutes(1);
    private static final Duration GIVE_UP_AFTER = Duration.ofHours(24);
    private static final Duration CATCH_UP_WINDOW = Duration.ofDays(3);

    private final DigestQuery query;
    private final DigestRepository digests;
    private final AlertDispatcher dispatcher;
    private final Clock clock;
    private final CronExpression cron;

    // An abandoned digest stays NULL forever; remember which were logged so each is reported once.
    private final Set<String> abandonedLogged = ConcurrentHashMap.newKeySet();

    public DigestService(DigestQuery query, DigestRepository digests, AlertDispatcher dispatcher, Clock clock,
                         @Value("${centry.digest.cron:0 0 8 1 * *}") String cron) {
        this.query = query;
        this.digests = digests;
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.cron = CronExpression.parse(cron);
    }

    // The zone is the household zone, like the Clock bean (see ClockConfig), so "08:00" is the family's 08:00.
    @Scheduled(cron = "${centry.digest.cron:0 0 8 1 * *}",
            zone = "${centry.zone:#{T(java.time.ZoneId).systemDefault().getId()}}")
    public void sendLastMonth() {
        send(YearMonth.now(clock).minusMonths(1));
    }

    /**
     * Covers a missed cron (the app was down at 08:00 on the 1st): from the scheduled time until three days
     * later, last month's digest is sent if no row exists yet. Hourly rather than at startup only, which keeps
     * it to one rule.
     */
    @Scheduled(cron = "0 0 * * * *", zone = "${centry.zone:#{T(java.time.ZoneId).systemDefault().getId()}}")
    public void catchUp() {
        LocalDateTime now = LocalDateTime.now(clock);
        YearMonth period = YearMonth.from(now).minusMonths(1);
        LocalDateTime due = cron.next(period.plusMonths(1).atDay(1).atStartOfDay().minusNanos(1));
        if (!now.isBefore(due) && now.isBefore(due.plus(CATCH_UP_WINDOW))) {
            send(period);
        }
    }

    @Scheduled(fixedDelayString = "${centry.notify.retry-interval:PT5M}")
    public void retryUnsent() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime oldest = now.minus(GIVE_UP_AFTER);

        for (String period : digests.findAbandoned(oldest)) {
            if (abandonedLogged.add(period)) {
                log.warn("Abandoning digest {}: still undelivered after {} hours", period, GIVE_UP_AFTER.toHours());
            }
        }
        for (String period : digests.findUnsent(oldest, now.minus(GRACE))) {
            log.info("Retrying delivery of digest {}", period);
            deliver(YearMonth.parse(period));
        }
    }

    private void send(YearMonth period) {
        if (digests.insertIfAbsent(period.toString(), LocalDateTime.now(clock))) {
            deliver(period);
        }
    }

    private void deliver(YearMonth period) {
        Notification digest = DigestMessage.of(query.forMonth(period));
        if (dispatcher.broadcast(digest, "digest " + period)) {
            digests.markNotified(period.toString(), LocalDateTime.now(clock));
        }
    }
}
