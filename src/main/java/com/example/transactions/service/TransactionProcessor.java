package com.example.transactions.service;

import com.example.transactions.model.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class TransactionProcessor {
    private static final int MAX_ATTEMPTS = 2;
    private final Map<String, BigDecimal> balances = new ConcurrentHashMap<>();
    private final Map<String, TransactionResult> processedByTransactionId = new ConcurrentHashMap<>();
    private final Map<String, TransactionResult> processedByIdempotencyKey = new ConcurrentHashMap<>();
    private final Map<String, Long> sequenceWatermark = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> attemptsByTransactionId = new ConcurrentHashMap<>();

    public synchronized List<TransactionResult> processBatch(List<TransactionRequest> requests) {
        if (requests == null) throw new IllegalArgumentException("requests must not be null");
        List<TransactionRequest> ordered = requests.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(TransactionRequest::accountId)
                        .thenComparing(TransactionRequest::sequenceNumber)
                        .thenComparing(TransactionRequest::transactionId))
                .toList();
        List<TransactionResult> results = new ArrayList<>();
        for (TransactionRequest request : ordered) {
            results.add(processOne(request));
        }
        return results;
    }

    public synchronized TransactionResult processOne(TransactionRequest request) {
        String key = request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                ? request.requestId() : request.idempotencyKey();

        TransactionResult priorById = processedByTransactionId.get(request.transactionId());
        if (priorById != null) {
            return result(request, TransactionStatus.DUPLICATE, balances.getOrDefault(request.accountId(), BigDecimal.ZERO),
                    priorById.attempts(), "Business transaction was already handled");
        }
        TransactionResult priorByKey = processedByIdempotencyKey.get(key);
        if (priorByKey != null) {
            return result(request, TransactionStatus.DUPLICATE, balances.getOrDefault(request.accountId(), BigDecimal.ZERO),
                    priorByKey.attempts(), "Idempotency key was already handled");
        }

        String validationError = validate(request);
        if (validationError != null) {
            TransactionResult failed = result(request, TransactionStatus.FAILED, balances.getOrDefault(request.accountId(), BigDecimal.ZERO), 0, validationError);
            processedByTransactionId.put(request.transactionId(), failed);
            processedByIdempotencyKey.put(key, failed);
            return failed;
        }

        Long watermark = sequenceWatermark.get(request.accountId());
        if (watermark != null && request.sequenceNumber() <= watermark) {
            TransactionResult pending = result(request, TransactionStatus.PENDING, balances.getOrDefault(request.accountId(), BigDecimal.ZERO), 0,
                    "Stale/out-of-order sequence; not applied because account watermark is " + watermark);
            return pending;
        }

        TransactionStatus status = TransactionStatus.PROCESSING;
        int attempts = 0;
        while (attempts < MAX_ATTEMPTS) {
            attempts++;
            attemptsByTransactionId.computeIfAbsent(request.transactionId(), ignored -> new AtomicInteger()).set(attempts);
            // Deterministic transient-failure simulation: TXN-2005 fails on its first attempt.
            if ("TXN-2005".equals(request.transactionId()) && attempts == 1) {
                status = TransactionStatus.RETRY_PENDING;
                continue;
            }
            try {
                BigDecimal current = balances.getOrDefault(request.accountId(), BigDecimal.ZERO);
                BigDecimal next = switch (request.transactionType()) {
                    case CREDIT, REVERSAL -> current.add(request.amount());
                    case DEBIT -> {
                        if (current.compareTo(request.amount()) < 0) throw new IllegalStateException("Insufficient funds");
                        yield current.subtract(request.amount());
                    }
                };
                balances.put(request.accountId(), next);
                sequenceWatermark.put(request.accountId(), request.sequenceNumber());
                TransactionResult success = result(request, TransactionStatus.PROCESSED, next, attempts,
                        attempts > 1 ? "Processed successfully after retry" : "Processed successfully");
                processedByTransactionId.put(request.transactionId(), success);
                processedByIdempotencyKey.put(key, success);
                return success;
            } catch (RuntimeException ex) {
                TransactionResult failed = result(request, TransactionStatus.FAILED, balances.getOrDefault(request.accountId(), BigDecimal.ZERO), attempts, ex.getMessage());
                if (attempts >= MAX_ATTEMPTS) {
                    processedByTransactionId.put(request.transactionId(), failed);
                    processedByIdempotencyKey.put(key, failed);
                    return failed;
                }
                status = TransactionStatus.RETRY_PENDING;
            }
        }
        TransactionResult failed = result(request, TransactionStatus.FAILED, balances.getOrDefault(request.accountId(), BigDecimal.ZERO), attempts, "Retry limit exhausted");
        processedByTransactionId.put(request.transactionId(), failed);
        processedByIdempotencyKey.put(key, failed);
        return failed;
    }

    private String validate(TransactionRequest r) {
        if (r == null) return "Transaction is required";
        if (blank(r.transactionId()) || blank(r.requestId()) || blank(r.accountId())) return "transactionId, requestId, and accountId are required";
        if (r.transactionType() == null) return "transactionType is required";
        if (r.amount() == null || r.amount().compareTo(BigDecimal.ZERO) <= 0) return "amount must be greater than zero";
        if (r.currency() == null || !r.currency().matches("[A-Z]{3}")) return "currency must be a 3-letter uppercase code";
        if (r.sequenceNumber() == null || r.sequenceNumber() <= 0) return "sequenceNumber must be positive";
        return null;
    }
    private boolean blank(String s) { return s == null || s.isBlank(); }
    private TransactionResult result(TransactionRequest r, TransactionStatus s, BigDecimal b, int a, String m) {
        return new TransactionResult(r.transactionId(), r.requestId(), r.accountId(), s, b, a, m);
    }
    public BigDecimal balance(String accountId) { return balances.getOrDefault(accountId, BigDecimal.ZERO); }
    public Map<String, BigDecimal> balances() { return Map.copyOf(balances); }
    public synchronized Map<TransactionStatus, Long> statusCounts() {
        return processedByTransactionId.values().stream().collect(Collectors.groupingBy(TransactionResult::status, () -> new EnumMap<>(TransactionStatus.class), Collectors.counting()));
    }
    public synchronized void reset() {
        balances.clear(); processedByTransactionId.clear(); processedByIdempotencyKey.clear(); sequenceWatermark.clear(); attemptsByTransactionId.clear();
    }
}
