package com.centry.notify;

import com.centry.model.BudgetAlertEvent;
import com.centry.model.BudgetLevel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationMappingTest {

    private static BudgetAlertEvent alert(BudgetLevel level) {
        return new BudgetAlertEvent("food", new BigDecimal("120.00"), new BigDecimal("100.00"),
                new BigDecimal("80.00"), level, "the message");
    }

    @Test
    void limitExceededIsHighPriorityWithSirenTag() {
        assertThat(AlertDispatcher.toNotification(alert(BudgetLevel.LIMIT_EXCEEDED))).isEqualTo(
                new Notification("Centry budget alert: food", "the message", Notification.Priority.HIGH,
                        List.of("rotating_light")));
    }

    @Test
    void warningIsDefaultPriorityWithWarningTag() {
        assertThat(AlertDispatcher.toNotification(alert(BudgetLevel.WARNING_EXCEEDED))).isEqualTo(
                new Notification("Centry budget alert: food", "the message", Notification.Priority.DEFAULT,
                        List.of("warning")));
    }
}
