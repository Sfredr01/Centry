package com.centry.kafka;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListenerConfigurer;
import org.springframework.kafka.config.KafkaListenerEndpointRegistrar;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * Makes {@code @Valid} on {@code @KafkaListener} payloads effective (Spring Kafka does not validate by
 * default). A violation raises a non-retryable exception, so the record goes straight to the DLT.
 */
@Configuration
@Profile("kafka")
@RequiredArgsConstructor
public class KafkaValidationConfig implements KafkaListenerConfigurer {

    private final LocalValidatorFactoryBean validator;

    @Override
    public void configureKafkaListeners(KafkaListenerEndpointRegistrar registrar) {
        registrar.setValidator(validator);
    }
}
