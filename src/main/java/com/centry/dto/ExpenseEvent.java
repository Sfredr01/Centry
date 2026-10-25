package com.centry.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExpenseEvent {

    /** Idempotency key: replaying the same id with the same payload is a no-op. */
    @Size(max = 100)
    private String transactionId;

    @NotBlank
    @Size(max = 200)
    private String merchant;

    // Two decimal places at most: money is stored as integer cents.
    @NotNull
    @Positive
    @Digits(integer = 9, fraction = 2)
    private BigDecimal amount;

    @NotBlank
    @Size(max = 100)
    private String category;

    private LocalDateTime timestamp;
}
