package com.example.transactions.controller;

import com.example.transactions.model.*;
import com.example.transactions.service.TransactionProcessor;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/api")
public class TransactionController {
    private final TransactionProcessor processor;
    public TransactionController(TransactionProcessor processor) { this.processor = processor; }

    @PostMapping("/transactions")
    public List<TransactionResult> submitBatch(@RequestBody List<@Valid TransactionRequest> requests) {
        try { return processor.processBatch(requests); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage()); }
    }

    @PostMapping("/transactions/single")
    public TransactionResult submitOne(@Valid @RequestBody TransactionRequest request) {
        return processor.processOne(request);
    }

    @GetMapping("/accounts/{accountId}/balance")
    public Map<String, Object> balance(@PathVariable String accountId) {
        return Map.of("accountId", accountId, "balance", processor.balance(accountId));
    }

    @GetMapping("/accounts")
    public Map<String, BigDecimal> balances() { return processor.balances(); }

    @GetMapping("/summary")
    public ProcessingSummary summary() {
        Map<TransactionStatus, Long> counts = processor.statusCounts();
        int total = counts.values().stream().mapToInt(Long::intValue).sum();
        return new ProcessingSummary(total, counts, List.of());
    }

    @PostMapping("/reset")
    public Map<String, String> reset() {
        processor.reset();
        return Map.of("status", "reset");
    }
}
