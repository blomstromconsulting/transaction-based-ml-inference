package com.example.fraud.domain.service;

import com.example.fraud.domain.model.TransactionEvent;

public class TransactionNormalizer {
    public TransactionEvent normalize(TransactionEvent event) {
        return new TransactionEvent(
                event.transactionId(),
                event.customerId(),
                event.cardId(),
                event.merchantId(),
                event.merchantCategory(),
                event.amount(),
                event.currency(),
                event.country(),
                event.timestamp(),
                event.requestedModel());
    }
}
