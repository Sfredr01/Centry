package com.centry.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** A recurring charge. {@code merchantKey} is {@link Keys#of} of the merchant and never changes; {@code reviewedOn} is null until reviewed. */
public record Subscription(long id, String merchantKey, String name, SubscriptionCadence cadence,
        BigDecimal expected, LocalDate nextDueOn, SubscriptionStatus status, LocalDate reviewedOn,
        LocalDateTime createdAt) {
}
