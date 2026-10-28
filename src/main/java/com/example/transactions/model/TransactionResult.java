package com.example.transactions.model;

import java.math.BigDecimal;

public record TransactionResult(
        String transactionId, String requestId, String accountId,
        TransactionStatus status, BigDecimal resultingBalance,
        int attempts, String message
) {}
