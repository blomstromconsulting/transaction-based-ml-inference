package com.example.fraud.adapter.in.rest;

import com.example.fraud.domain.model.ConfirmedFraudLabel;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;

public record FraudLabelResponse(
        @JsonProperty("transaction_id") String transactionId,
        @JsonProperty("is_fraud") boolean fraud,
        @JsonProperty("label_timestamp") Instant labelTimestamp,
        @JsonProperty("label_source") String labelSource,
        @JsonProperty("label_confidence") BigDecimal labelConfidence,
        @JsonProperty("annotator_id") String annotatorId,
        @JsonProperty("reason_code") String reasonCode) {
    public static FraudLabelResponse from(ConfirmedFraudLabel label) {
        return new FraudLabelResponse(
                label.transactionId(),
                label.fraud(),
                label.labelTimestamp(),
                label.labelSource(),
                label.labelConfidence(),
                label.annotatorId(),
                label.reasonCode());
    }
}
