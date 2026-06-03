package com.example.fraud.domain.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record FraudInferenceResult(
        String transactionId,
        String model,
        String modelVersion,
        String featureService,
        BigDecimal fraudScore,
        FraudDecisionType decision,
        List<String> featuresUsed,
        Map<String, Object> metadata) {
}
