package com.example.transactions.model;

import java.util.Map;

public record ProcessingSummary(int total, Map<TransactionStatus, Long> counts,
                               java.util.List<TransactionResult> results) {}
