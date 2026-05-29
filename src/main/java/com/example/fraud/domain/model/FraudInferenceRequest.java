package com.example.fraud.domain.model;

public record FraudInferenceRequest(TransactionEvent transactionEvent, FraudModel model) {
    public FraudInferenceRequest(TransactionEvent transactionEvent) {
        this(transactionEvent, transactionEvent.requestedModel());
    }
}
