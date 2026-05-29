package com.example.fraud.adapter.out.feast;

import com.example.fraud.domain.model.MerchantFeatureRow;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;

public record MerchantFeatureMaterializationRequest(
        @JsonProperty("merchant_id") String merchantId,
        @JsonProperty("event_timestamp") Instant eventTimestamp,
        @JsonProperty("merchant_risk_score") BigDecimal merchantRiskScore,
        @JsonProperty("merchant_category") String merchantCategory) {

    public static MerchantFeatureMaterializationRequest from(MerchantFeatureRow row) {
        return new MerchantFeatureMaterializationRequest(
                row.merchantId(),
                row.eventTimestamp(),
                row.merchantRiskScore(),
                row.merchantCategory());
    }
}
