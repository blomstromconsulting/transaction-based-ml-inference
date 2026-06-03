package com.example.fraud.transformer;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelInputBuilderTest {
    private final ModelInputBuilder builder = new ModelInputBuilder(true);

    @Test
    void failsWhenRequiredModelBFeatureIsMissing() {
        Map<String, Object> features = completeFeatures();
        features.remove("merchant_risk_score");

        FeatureValidationException exception = assertThrows(
                FeatureValidationException.class,
                () -> builder.build(FraudModel.MODEL_B, transaction(), features));

        assertEquals(true, exception.getMessage().contains("merchant_risk_score"));
    }

    @Test
    void failsWhenRequiredTransactionFieldIsMissing() {
        Map<String, Object> transaction = transaction();
        transaction.remove("customer_id");

        FeatureValidationException exception = assertThrows(
                FeatureValidationException.class,
                () -> builder.build(FraudModel.MODEL_B, transaction, completeFeatures()));

        assertEquals(true, exception.getMessage().contains("customer_id"));
    }

    @Test
    void buildsModelBInputWhenAllRequiredValuesExist() {
        Map<String, Object> input = builder.build(FraudModel.MODEL_B, transaction(), completeFeatures());

        assertEquals(1299.99, input.get("transaction_amount"));
        assertEquals(0.72, input.get("merchant_risk_score"));
        assertEquals(11, input.size());
    }

    private Map<String, Object> transaction() {
        Map<String, Object> transaction = new HashMap<>();
        transaction.put("transaction_id", "tx-10001");
        transaction.put("customer_id", "cust-123");
        transaction.put("merchant_id", "merchant-789");
        transaction.put("merchant_category", "electronics");
        transaction.put("transaction_amount", 1299.99);
        transaction.put("transaction_country", "SE");
        return transaction;
    }

    private Map<String, Object> completeFeatures() {
        Map<String, Object> features = new HashMap<>();
        features.put("customer_transaction_count_1h", 1);
        features.put("customer_transaction_count_24h", 3);
        features.put("customer_total_amount_24h", 1499.99);
        features.put("customer_avg_amount_7d", 100.25);
        features.put("customer_max_amount_7d", 1299.99);
        features.put("customer_distinct_merchants_24h", 2);
        features.put("customer_cross_border_count_7d", 1);
        features.put("merchant_risk_score", 0.72);
        return features;
    }
}
