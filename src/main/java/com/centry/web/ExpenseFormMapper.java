package com.centry.web;

import com.centry.dto.ExpenseEvent;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Turns what an expense form submitted into the {@link ExpenseEvent} of {@code POST /api/v1/expenses} and
 * validates it with the same rules (positive, at most two decimals, length limits). Quick-add and edit share it.
 */
@Component
@RequiredArgsConstructor
class ExpenseFormMapper {

    static final String UNKNOWN_SPENDER = "must be a household member";

    private final Validator validator;
    private final Clock clock;

    LocalDate today() {
        return LocalDate.now(clock);
    }

    /** Builds the event; everything wrong with the input ends up in {@code errors}, keyed by form field. */
    ExpenseEvent toEvent(ExpenseForm form, Map<String, String> errors) {
        BigDecimal amount = null;
        try {
            amount = new BigDecimal(form.amount());
        } catch (NumberFormatException e) {
            errors.put("amount", "must be a number, like 12.50");
        }
        LocalDate date = today();
        if (!form.date().isEmpty()) {
            try {
                date = LocalDate.parse(form.date());
            } catch (DateTimeParseException e) {
                errors.put("date", "must be a valid date");
            }
        }
        // Today is left to the service, which stamps "now" once when it creates the row. Stamping it here would
        // give a retried request a different timestamp and the service would reject it as a conflicting replay.
        // Any other date is recorded at the start of that day.
        LocalDateTime timestamp = date.equals(today()) ? null : date.atStartOfDay();
        Long spentBy = null;
        if (!form.spentBy().isEmpty()) {
            try {
                spentBy = Long.valueOf(form.spentBy());
            } catch (NumberFormatException e) {
                errors.put("spentBy", UNKNOWN_SPENDER);
            }
        }
        ExpenseEvent event = ExpenseEvent.builder()
                .transactionId(form.clientRef().isEmpty() ? null : form.clientRef())
                .merchant(form.merchant().isEmpty() ? form.category() : form.merchant())
                .amount(amount)
                .category(form.category())
                .timestamp(timestamp)
                .spentBy(spentBy)
                .build();
        for (ConstraintViolation<ExpenseEvent> violation : validator.validate(event)) {
            errors.putIfAbsent(violation.getPropertyPath().toString(), violation.getMessage());
        }
        return event;
    }
}
