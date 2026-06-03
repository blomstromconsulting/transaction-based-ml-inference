package com.example.fraud.domain.model;

import java.time.Instant;

public record FraudDecision(
        String transactionId,
        String customerId,
        String model,
        String modelVersion,
        FraudDecisionType decision,
        FraudInferenceResult inferenceResult,
        Instant decidedAt) {
}
