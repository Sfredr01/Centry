package com.centry.backup;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.CronTask;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

class NightlyBackupWiringTest {

    @Nested
    @SpringBootTest(properties = "centry.notify.retry-enabled=true")
    @ActiveProfiles("test")
    class Enabled {

        @Autowired
        private ScheduledAnnotationBeanPostProcessor scheduler;

        @Test
        void runsAtHalfPastThreeByDefault() {
            assertThat(scheduler.getScheduledTasks()).anySatisfy(scheduled -> {
                assertThat(scheduled.getTask()).isInstanceOf(CronTask.class);
                CronTask cron = (CronTask) scheduled.getTask();
                // Spring wraps the runnable; its toString names the target method.
                assertThat(cron.toString()).contains("NightlyBackup.run");
                assertThat(cron.getExpression()).isEqualTo("0 30 3 * * *");
            });
        }
    }

    @Nested
    @SpringBootTest(properties = "centry.backup.enabled=false")
    @ActiveProfiles("test")
    class Disabled {

        @Autowired
        private ApplicationContext context;

        @Test
        void noBeanMeansNothingIsScheduled() {
            assertThat(context.getBeanNamesForType(NightlyBackup.class)).isEmpty();
        }
    }
}
