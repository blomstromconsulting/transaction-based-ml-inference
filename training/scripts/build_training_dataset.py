import argparse
import os
from pathlib import Path

from feast import FeatureStore


MODEL_FEATURES = {
    "MODEL_A": [
        "customer_transaction_stats_view:customer_transaction_count_1h",
        "customer_transaction_stats_view:customer_transaction_count_24h",
        "customer_transaction_stats_view:customer_total_amount_24h",
        "customer_transaction_stats_view:customer_avg_amount_7d",
    ],
    "MODEL_B": [
        "customer_transaction_stats_view:customer_transaction_count_1h",
        "customer_transaction_stats_view:customer_transaction_count_24h",
        "customer_transaction_stats_view:customer_total_amount_24h",
        "customer_transaction_stats_view:customer_avg_amount_7d",
        "customer_transaction_stats_view:customer_max_amount_7d",
        "customer_transaction_stats_view:customer_distinct_merchants_24h",
        "customer_transaction_stats_view:customer_cross_border_count_7d",
        "merchant_risk_view:merchant_risk_score",
    ],
}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", choices=MODEL_FEATURES, default="MODEL_B")
    parser.add_argument("--repo", default="feast")
    parser.add_argument("--output", default="training/output/training_dataset.parquet")
    args = parser.parse_args()

    os.environ.setdefault("FEAST_OFFLINE_STORE_TYPE", "postgres")
    store = FeatureStore(repo_path=args.repo)
    entity_query = """
        SELECT
            transaction_id,
            customer_id,
            merchant_id,
            event_timestamp,
            transaction_amount,
            transaction_country,
            merchant_category,
            is_fraud
        FROM fraud_training_examples
    """
    dataset = store.get_historical_features(
        entity_df=entity_query,
        features=MODEL_FEATURES[args.model],
    ).to_df()

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    dataset.to_parquet(output, index=False)
    print(f"Wrote {len(dataset)} rows to {output}")


if __name__ == "__main__":
    main()
