import argparse
import os
from pathlib import Path

from feast import FeatureStore

from model_catalog import load_model_catalog


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="MODEL_B")
    parser.add_argument("--model-catalog", default="training/model_catalog.json")
    parser.add_argument("--repo", default="feast")
    parser.add_argument("--output", default="training/output/training_dataset.parquet")
    args = parser.parse_args()
    catalog = load_model_catalog(args.model_catalog)
    if args.model not in catalog:
        raise ValueError(f"Unknown model {args.model}; available models: {', '.join(sorted(catalog))}")

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
        features=catalog[args.model]["feature_refs"],
    ).to_df()

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    dataset.to_parquet(output, index=False)
    print(f"Wrote {len(dataset)} rows to {output}")


if __name__ == "__main__":
    main()
