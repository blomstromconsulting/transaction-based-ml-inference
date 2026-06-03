package com.example.fraud.adapter.in.rest;

import com.example.fraud.domain.model.FraudDecision;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

public record FraudDecisionResponse(
        @JsonProperty("transaction_id") String transactionId,
        String model,
        @JsonProperty("model_version") String modelVersion,
        @JsonProperty("feature_service") String featureService,
        @JsonProperty("fraud_score") BigDecimal fraudScore,
        String decision,
        @JsonProperty("features_used") List<String> featuresUsed) {

    public static FraudDecisionResponse from(FraudDecision decision) {
        return new FraudDecisionResponse(
                decision.transactionId(),
                decision.model(),
                decision.modelVersion(),
                decision.inferenceResult().featureService(),
                decision.inferenceResult().fraudScore(),
                decision.decision().name(),
                decision.inferenceResult().featuresUsed());
    }
}
