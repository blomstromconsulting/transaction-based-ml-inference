package com.example.fraud.domain.model;

public record FraudInferenceRequest(TransactionEvent transactionEvent, String model) {
    public FraudInferenceRequest(TransactionEvent transactionEvent) {
        this(transactionEvent, transactionEvent.requestedModel());
    }
}
