package com.example.fraud.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record TransactionEvent(
        String transactionId,
        String customerId,
        String cardId,
        String merchantId,
        String merchantCategory,
        BigDecimal amount,
        String currency,
        String country,
        Instant timestamp,
        FraudModel requestedModel) {

    public TransactionEvent {
        transactionId = requireText(transactionId, "transactionId");
        customerId = requireText(customerId, "customerId");
        cardId = requireText(cardId, "cardId");
        merchantId = requireText(merchantId, "merchantId");
        merchantCategory = requireText(merchantCategory, "merchantCategory").toLowerCase();
        amount = Objects.requireNonNull(amount, "amount is required");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        currency = requireText(currency, "currency").toUpperCase();
        country = requireText(country, "country").toUpperCase();
        timestamp = Objects.requireNonNull(timestamp, "timestamp is required");
        requestedModel = Objects.requireNonNullElse(requestedModel, FraudModel.MODEL_A);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
