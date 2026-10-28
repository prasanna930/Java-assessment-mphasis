package com.example.transactions.service;

import com.example.transactions.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TransactionProcessorTest {
    private TransactionProcessor processor;
    @BeforeEach void setUp() { processor = new TransactionProcessor(); }

    private TransactionRequest tx(String id, String req, String account, TransactionType type, String amount, long seq, String key) {
        return new TransactionRequest(id, req, account, type, new BigDecimal(amount), "USD", seq, null, key);
    }

    @Test void duplicateDoesNotApplyBalanceTwice() {
        var first = processor.processOne(tx("T1","R1","A1",TransactionType.CREDIT,"100",1,"K1"));
        var duplicate = processor.processOne(tx("T1","R2","A1",TransactionType.CREDIT,"100",1,"K2"));
        assertEquals(TransactionStatus.PROCESSED, first.status());
        assertEquals(TransactionStatus.DUPLICATE, duplicate.status());
        assertEquals(new BigDecimal("100"), processor.balance("A1"));
    }

    @Test void sortsOutOfOrderBatchByAccountAndSequence() {
        var results = processor.processBatch(List.of(
                tx("T2","R2","A1",TransactionType.DEBIT,"40",2,"K2"),
                tx("T1","R1","A1",TransactionType.CREDIT,"100",1,"K1")));
        assertEquals("T1", results.get(0).transactionId());
        assertEquals(TransactionStatus.PROCESSED, results.get(0).status());
        assertEquals(TransactionStatus.PROCESSED, results.get(1).status());
        assertEquals(new BigDecimal("60"), processor.balance("A1"));
    }

    @Test void invalidAmountIsRejectedWithoutStoppingBatch() {
        var results = processor.processBatch(List.of(
                tx("BAD","RB","A1",TransactionType.DEBIT,"-10",1,"KB"),
                tx("GOOD","RG","A2",TransactionType.CREDIT,"25",1,"KG")));
        assertEquals(TransactionStatus.FAILED, results.get(0).status());
        assertEquals(TransactionStatus.PROCESSED, results.get(1).status());
        assertEquals(new BigDecimal("25"), processor.balance("A2"));
    }

    @Test void transientFailureRetriesWithoutDuplicateEffect() {
        var result = processor.processOne(tx("TXN-2005","REQ-5","ACC-203",TransactionType.CREDIT,"500",1,"KEY-5"));
        assertEquals(TransactionStatus.PROCESSED, result.status());
        assertEquals(2, result.attempts());
        assertEquals(new BigDecimal("500"), processor.balance("ACC-203"));
        assertEquals(TransactionStatus.DUPLICATE, processor.processOne(tx("TXN-2005","REQ-5-REPLAY","ACC-203",TransactionType.CREDIT,"500",1,"KEY-5")).status());
        assertEquals(new BigDecimal("500"), processor.balance("ACC-203"));
    }

    @Test void insufficientFundsFailsWithoutChangingBalance() {
        var result = processor.processOne(tx("D1","R1","A1",TransactionType.DEBIT,"10",1,"K1"));
        assertEquals(TransactionStatus.FAILED, result.status());
        assertEquals(BigDecimal.ZERO, processor.balance("A1"));
    }
}
