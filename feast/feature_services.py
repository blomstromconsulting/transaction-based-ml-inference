from feast import FeatureService

from feature_views import customer_transaction_stats_view, merchant_risk_view

common_customer_features = [
    customer_transaction_stats_view[["customer_transaction_count_1h"]],
    customer_transaction_stats_view[["customer_transaction_count_24h"]],
    customer_transaction_stats_view[["customer_total_amount_24h"]],
    customer_transaction_stats_view[["customer_avg_amount_7d"]],
]

extended_customer_features = [
    customer_transaction_stats_view[["customer_max_amount_7d"]],
    customer_transaction_stats_view[["customer_distinct_merchants_24h"]],
    customer_transaction_stats_view[["customer_cross_border_count_7d"]],
    customer_transaction_stats_view[["current_merchant_visit_count_30d"]],
    customer_transaction_stats_view[["current_merchant_visit_share_30d"]],
    customer_transaction_stats_view[["current_merchant_rank_30d"]],
    customer_transaction_stats_view[["is_current_merchant_top_visited_30d"]],
    customer_transaction_stats_view[["days_since_first_seen_current_merchant"]],
    customer_transaction_stats_view[["days_since_last_seen_current_merchant"]],
    customer_transaction_stats_view[["customer_distinct_merchants_30d"]],
    customer_transaction_stats_view[["is_new_merchant_for_customer"]],
    customer_transaction_stats_view[["top_visited_merchant_id_30d"]],
]

fraud_model_a_feature_service = FeatureService(
    name="fraud_model_a_feature_service",
    features=[
        *common_customer_features,
    ],
    tags={"model": "MODEL_A", "purpose": "baseline_fraud_scoring"},
)

fraud_model_b_feature_service = FeatureService(
    name="fraud_model_b_feature_service",
    features=[
        *common_customer_features,
        *extended_customer_features,
        merchant_risk_view[["merchant_risk_score"]],
    ],
    tags={"model": "MODEL_B", "purpose": "extended_fraud_scoring"},
)
