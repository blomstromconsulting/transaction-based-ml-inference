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

    private static final List<String> MODEL_A_ONLINE_FEATURES = List.of(
            "customer_transaction_count_1h",
            "customer_transaction_count_24h",
            "customer_total_amount_24h",
            "customer_avg_amount_7d");

    private static final List<String> MODEL_B_EXTRA_ONLINE_FEATURES = List.of(
            "customer_max_amount_7d",
            "customer_distinct_merchants_24h",
            "customer_cross_border_count_7d",
            "merchant_risk_score");

    private ModelFeatureSchema() {
    }

    public static List<String> requiredTransactionFields() {
        return REQUIRED_TRANSACTION_FIELDS;
    }

    public static List<String> requiredOnlineFeatures(FraudModel model) {
        if (model == FraudModel.MODEL_A) {
            return MODEL_A_ONLINE_FEATURES;
        }
        return java.util.stream.Stream.concat(
                        MODEL_A_ONLINE_FEATURES.stream(),
                        MODEL_B_EXTRA_ONLINE_FEATURES.stream())
                .toList();
    }
}
