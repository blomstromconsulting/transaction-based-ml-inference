package com.example.fraud.transformer;

import java.util.List;

public final class ModelFeatureSchema {
    private static final List<String> MODEL_A_ONLINE_FEATURES = List.of(
            "customer_transaction_count_1h",
            "customer_transaction_count_24h",
            "customer_total_amount_24h",
            "customer_avg_amount_7d");

    private static final List<String> MODEL_B_ONLINE_FEATURES = List.of(
            "customer_transaction_count_1h",
            "customer_transaction_count_24h",
            "customer_total_amount_24h",
            "customer_avg_amount_7d",
            "customer_max_amount_7d",
            "customer_distinct_merchants_24h",
            "customer_cross_border_count_7d",
            "current_merchant_visit_count_30d",
            "current_merchant_visit_share_30d",
            "current_merchant_rank_30d",
            "is_current_merchant_top_visited_30d",
            "days_since_first_seen_current_merchant",
            "days_since_last_seen_current_merchant",
            "customer_distinct_merchants_30d",
            "is_new_merchant_for_customer",
            "top_visited_merchant_id_30d",
            "merchant_risk_score");

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

    public static List<String> onlineFeaturesForModel(String model) {
        return "MODEL_A".equalsIgnoreCase(model) ? MODEL_A_ONLINE_FEATURES : MODEL_B_ONLINE_FEATURES;
    }

    public static boolean isModelInputOnlineFeature(String field) {
        return !NON_MODEL_INPUT_FIELDS.contains(field);
    }
}
