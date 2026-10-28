package com.example.transactions.model;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;

public record TransactionRequest(
        @NotBlank String transactionId,
        @NotBlank String requestId,
        @NotBlank String accountId,
        @NotNull TransactionType transactionType,
        @NotNull @DecimalMin(value = "0.01", message = "amount must be greater than zero") BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter uppercase code") String currency,
        @NotNull @Positive Long sequenceNumber,
        Instant eventTime,
        String idempotencyKey
) {}
