package com.example.fraud.domain.model;

public class TransactionNotFoundException extends RuntimeException {
    public TransactionNotFoundException(String transactionId) {
        super("Transaction was not found: " + transactionId);
    }
}
