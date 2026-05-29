package com.example.fraud.application.usecase;

import com.example.fraud.domain.model.FraudModel;
import com.example.fraud.domain.model.TransactionEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransactionEventTest {
    @Test
    void normalizesCountryCurrencyAndMerchantCategory() {
        TransactionEvent event = new TransactionEvent(
                "tx-1", "cust-1", "card-1", "merchant-1", "Electronics",
                new BigDecimal("42.00"), "eur", "se", Instant.parse("2026-05-29T12:00:00Z"), null);

        assertEquals("EUR", event.currency());
        assertEquals("SE", event.country());
        assertEquals("electronics", event.merchantCategory());
        assertEquals(FraudModel.MODEL_A, event.requestedModel());
    }

    @Test
    void rejectsNonPositiveAmount() {
        assertThrows(IllegalArgumentException.class, () -> new TransactionEvent(
                "tx-1", "cust-1", "card-1", "merchant-1", "electronics",
                BigDecimal.ZERO, "EUR", "SE", Instant.parse("2026-05-29T12:00:00Z"), FraudModel.MODEL_A));
    }
}
