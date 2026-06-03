package com.example.fraud.domain.model;

public class TransactionAlreadyProcessedException extends RuntimeException {
    public TransactionAlreadyProcessedException(String transactionId) {
        super("Transaction has already been processed: " + transactionId);
    }
}
