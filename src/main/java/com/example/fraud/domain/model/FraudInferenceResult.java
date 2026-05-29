package com.example.fraud.domain.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record FraudInferenceResult(
        String transactionId,
        FraudModel model,
        BigDecimal fraudScore,
        FraudDecisionType decision,
        List<String> featuresUsed,
        Map<String, Object> metadata) {
}
