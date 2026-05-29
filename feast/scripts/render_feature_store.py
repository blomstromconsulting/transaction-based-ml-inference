import os
from pathlib import Path

import pandas as pd
import yaml


repo = Path(__file__).resolve().parents[1]
feature_store = repo / "feature_store.yaml"
data_dir = repo / "data"

config = yaml.safe_load(feature_store.read_text())
config.setdefault("online_store", {})
config["online_store"]["type"] = "redis"
config["online_store"]["connection_string"] = os.getenv(
    "FEAST_REDIS_CONNECTION_STRING",
    config["online_store"].get("connection_string", "redis:6379"),
)

feature_store.write_text(yaml.safe_dump(config, sort_keys=False))

data_dir.mkdir(exist_ok=True)

customer_stats = data_dir / "customer_stats.parquet"
if not customer_stats.exists():
    pd.DataFrame(
        [
            {
                "customer_id": "bootstrap-customer",
                "event_timestamp": pd.Timestamp.utcnow(),
                "customer_transaction_count_1h": 0,
                "customer_transaction_count_24h": 0,
                "customer_total_amount_24h": 0.0,
                "customer_avg_amount_7d": 0.0,
                "customer_max_amount_7d": 0.0,
                "customer_distinct_merchants_24h": 0,
                "customer_cross_border_count_7d": 0,
            }
        ]
    ).to_parquet(customer_stats, index=False)

merchant_risk = data_dir / "merchant_risk.parquet"
if not merchant_risk.exists():
    pd.DataFrame(
        [
            {
                "merchant_id": "bootstrap-merchant",
                "event_timestamp": pd.Timestamp.utcnow(),
                "merchant_risk_score": 0.0,
                "merchant_category": "bootstrap",
            }
        ]
    ).to_parquet(merchant_risk, index=False)
