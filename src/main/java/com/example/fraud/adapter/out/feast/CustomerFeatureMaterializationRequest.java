package com.example.fraud.adapter.out.feast;

import com.example.fraud.domain.model.CustomerFeatureRow;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;

public record CustomerFeatureMaterializationRequest(
        @JsonProperty("customer_id") String customerId,
        @JsonProperty("event_timestamp") Instant eventTimestamp,
        @JsonProperty("customer_transaction_count_1h") long customerTransactionCount1h,
        @JsonProperty("customer_transaction_count_24h") long customerTransactionCount24h,
        @JsonProperty("customer_total_amount_24h") BigDecimal customerTotalAmount24h,
        @JsonProperty("customer_avg_amount_7d") BigDecimal customerAvgAmount7d,
        @JsonProperty("customer_max_amount_7d") BigDecimal customerMaxAmount7d,
        @JsonProperty("customer_distinct_merchants_24h") long customerDistinctMerchants24h,
        @JsonProperty("customer_cross_border_count_7d") long customerCrossBorderCount7d) {

    public static CustomerFeatureMaterializationRequest from(CustomerFeatureRow row) {
        return new CustomerFeatureMaterializationRequest(
                row.customerId(),
                row.eventTimestamp(),
                row.customerTransactionCount1h(),
                row.customerTransactionCount24h(),
                row.customerTotalAmount24h(),
                row.customerAvgAmount7d(),
                row.customerMaxAmount7d(),
                row.customerDistinctMerchants24h(),
                row.customerCrossBorderCount7d());
    }
}
