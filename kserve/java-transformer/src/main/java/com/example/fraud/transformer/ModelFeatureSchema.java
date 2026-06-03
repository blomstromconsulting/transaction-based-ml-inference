package com.example.fraud.transformer;

import java.util.List;

public final class ModelFeatureSchema {
    private static final List<String> REQUIRED_TRANSACTION_FIELDS = List.of(
            "transaction_id",
            "customer_id",
            "merchant_id",
            "merchant_category",
            "transaction_amount",
            "transaction_country");

    private static final List<String> NON_MODEL_INPUT_FIELDS = List.of(
            "customer_id",
            "merchant_id",
            "event_timestamp",
            "created_timestamp");

    private ModelFeatureSchema() {
    }

    public static List<String> requiredTransactionFields() {
        return REQUIRED_TRANSACTION_FIELDS;
    }

    public static boolean isModelInputOnlineFeature(String field) {
        return !NON_MODEL_INPUT_FIELDS.contains(field);
    }
}
