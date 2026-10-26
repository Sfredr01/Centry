package com.centry.notify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.CronTask;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DigestSchedulingTest {

    // Scheduling is on here (the test profile switches it off), so the cron and zone expressions are really parsed.
    @SpringBootTest(properties = {"centry.notify.retry-enabled=true", "centry.zone=America/Chicago",
            "centry.digest.cron=0 30 9 2 * *"})
    @ActiveProfiles("test")
    static class Enabled {

        @Autowired
        ApplicationContext context;

        @Test
        void digestIsScheduledWithTheConfiguredCron() {
            assertThat(cronExpressions(context)).contains("0 30 9 2 * *", "0 0 * * * *");
        }
    }

    @SpringBootTest(properties = {"centry.notify.retry-enabled=true", "centry.digest.enabled=false"})
    @ActiveProfiles("test")
    static class Disabled {

        @Autowired
        ApplicationContext context;

        @Test
        void disabledMeansNoSchedule() {
            assertThat(context.getBeanNamesForType(DigestService.class)).isEmpty();
            assertThat(cronExpressions(context)).isEmpty();
        }
    }

    static List<String> cronExpressions(ApplicationContext context) {
        return context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().stream()
                .map(t -> t.getTask())
                .filter(CronTask.class::isInstance)
                .map(t -> ((CronTask) t).getExpression())
                .toList();
    }
}
