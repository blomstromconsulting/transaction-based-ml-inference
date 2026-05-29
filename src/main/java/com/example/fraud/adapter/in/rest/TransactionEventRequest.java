package com.example.fraud.adapter.in.rest;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionEventRequest(
        @JsonProperty("transaction_id") String transactionId,
        @JsonProperty("customer_id") String customerId,
        @JsonProperty("card_id") String cardId,
        @JsonProperty("merchant_id") String merchantId,
        @JsonProperty("merchant_category") String merchantCategory,
        BigDecimal amount,
        String currency,
        String country,
        Instant timestamp,
        @JsonProperty("requested_model") String requestedModel) {
}
