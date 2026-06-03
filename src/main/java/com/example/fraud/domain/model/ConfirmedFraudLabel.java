package com.example.fraud.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record ConfirmedFraudLabel(
        String transactionId,
        boolean fraud,
        Instant labelTimestamp,
        String labelSource,
        BigDecimal labelConfidence,
        String annotatorId,
        String reasonCode) {
    public ConfirmedFraudLabel {
        transactionId = requireText(transactionId, "transactionId");
        labelTimestamp = Objects.requireNonNullElseGet(labelTimestamp, Instant::now);
        labelSource = requireText(labelSource, "labelSource").toLowerCase();
        labelConfidence = Objects.requireNonNullElse(labelConfidence, BigDecimal.ONE);
        if (labelConfidence.compareTo(BigDecimal.ZERO) < 0 || labelConfidence.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("labelConfidence must be between 0 and 1");
        }
        annotatorId = normalizeOptional(annotatorId);
        reasonCode = normalizeOptional(reasonCode);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
