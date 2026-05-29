package com.example.fraud.domain.model;

import java.time.Instant;

public record FraudDecision(
        String transactionId,
        String customerId,
        FraudModel model,
        FraudDecisionType decision,
        FraudInferenceResult inferenceResult,
        Instant decidedAt) {
}
