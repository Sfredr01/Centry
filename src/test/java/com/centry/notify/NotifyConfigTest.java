package com.centry.notify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class NotifyConfigTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(NotifyConfig.class);

    @Test
    void notifiersOnlyCreatedWhenConfigured() {
        runner.run(ctx -> assertThat(ctx.getBeansOfType(Notifier.class)).isEmpty());

        // ntfy needs both url and topic; blank values count as not configured.
        runner.withPropertyValues("centry.notify.ntfy.url=http://ntfy.test")
                .run(ctx -> assertThat(ctx.getBeansOfType(Notifier.class)).isEmpty());
        runner.withPropertyValues("centry.notify.ntfy.topic=alerts", "centry.notify.webhook.url=  ")
                .run(ctx -> assertThat(ctx.getBeansOfType(Notifier.class)).isEmpty());

        runner.withPropertyValues("centry.notify.ntfy.url=http://ntfy.test", "centry.notify.ntfy.topic=alerts")
                .run(ctx -> assertThat(ctx.getBeansOfType(Notifier.class).values())
                        .singleElement().isInstanceOf(NtfyNotifier.class));
        runner.withPropertyValues("centry.notify.webhook.url=http://hook.test/x")
                .run(ctx -> assertThat(ctx.getBeansOfType(Notifier.class).values())
                        .singleElement().isInstanceOf(WebhookNotifier.class));
        runner.withPropertyValues("centry.notify.ntfy.url=http://ntfy.test", "centry.notify.ntfy.topic=alerts",
                        "centry.notify.webhook.url=http://hook.test/x")
                .run(ctx -> assertThat(ctx.getBeansOfType(Notifier.class)).hasSize(2));
    }

    @Test
    void bindsPropertiesWithDefaults() {
        runner.run(ctx -> {
            NotifyProperties props = ctx.getBean(NotifyProperties.class);
            assertThat(props.retryInterval()).isEqualTo(Duration.ofMinutes(5));
            assertThat(props.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(props.readTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(props.ntfy().token()).isNull();
        });
        runner.withPropertyValues("centry.notify.ntfy.token=tk", "centry.notify.retry-interval=PT10S",
                        "centry.notify.read-timeout=2s", "centry.notify.connect-timeout=1s")
                .run(ctx -> {
                    NotifyProperties props = ctx.getBean(NotifyProperties.class);
                    assertThat(props.ntfy().token()).isEqualTo("tk");
                    assertThat(props.retryInterval()).isEqualTo(Duration.ofSeconds(10));
                    assertThat(props.readTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(props.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
                });
    }
}
