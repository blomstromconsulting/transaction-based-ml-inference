package com.example.fraud.adapter.in.rest;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;

public record FraudLabelRequest(
        @JsonProperty("is_fraud") Boolean fraud,
        @JsonProperty("label_timestamp") Instant labelTimestamp,
        @JsonProperty("label_source") String labelSource,
        @JsonProperty("label_confidence") BigDecimal labelConfidence,
        @JsonProperty("annotator_id") String annotatorId,
        @JsonProperty("reason_code") String reasonCode) {
}
