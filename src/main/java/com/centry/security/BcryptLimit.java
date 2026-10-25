package com.centry.security;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

/**
 * Bcrypt rejects input over 72 bytes. {@code @Size} counts characters, so a 40-character password of 'é'
 * (80 UTF-8 bytes) would pass it and then fail inside the encoder. Null is valid (no password).
 */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = BcryptLimit.Validator.class)
public @interface BcryptLimit {

    int MAX_BYTES = 72;

    String message() default "must be at most " + MAX_BYTES + " bytes (UTF-8)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<BcryptLimit, String> {

        public static boolean fits(String password) {
            return password.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
        }

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || fits(value);
        }
    }
}
