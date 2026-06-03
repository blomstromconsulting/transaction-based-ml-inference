import os
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path
from typing import Any

import pandas as pd
from fastapi import FastAPI
from feast import FeatureStore
from pydantic import BaseModel, Field


class CustomerFeatureRow(BaseModel):
    customer_id: str
    event_timestamp: datetime
    customer_transaction_count_1h: int
    customer_transaction_count_24h: int
    customer_total_amount_24h: Decimal
    customer_avg_amount_7d: Decimal
    customer_max_amount_7d: Decimal
    customer_distinct_merchants_24h: int
    customer_cross_border_count_7d: int
    current_merchant_visit_count_30d: int
    current_merchant_visit_share_30d: Decimal
    current_merchant_rank_30d: int
    is_current_merchant_top_visited_30d: int
    days_since_first_seen_current_merchant: Decimal
    days_since_last_seen_current_merchant: Decimal
    customer_distinct_merchants_30d: int
    is_new_merchant_for_customer: int
    top_visited_merchant_id_30d: str


class MerchantFeatureRow(BaseModel):
    merchant_id: str
    event_timestamp: datetime
    merchant_risk_score: Decimal = Field(default=Decimal("0.50"))
    merchant_category: str


app = FastAPI(title="Fraud Feast Feature Writer")
store = FeatureStore(repo_path=os.getenv("FEAST_REPO_PATH", "/feast"))


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/materialize/customer")
def materialize_customer(row: CustomerFeatureRow) -> dict[str, Any]:
    df = _dataframe(row.model_dump())
    store.write_to_online_store("customer_transaction_stats_view", df)
    return {"status": "materialized", "feature_view": "customer_transaction_stats_view", "rows": 1}


@app.post("/materialize/merchant")
def materialize_merchant(row: MerchantFeatureRow) -> dict[str, Any]:
    df = _dataframe(row.model_dump())
    store.write_to_online_store("merchant_risk_view", df)
    return {"status": "materialized", "feature_view": "merchant_risk_view", "rows": 1}


def _dataframe(row: dict[str, Any]) -> pd.DataFrame:
    normalized = {
        key: float(value) if isinstance(value, Decimal) else value
        for key, value in row.items()
    }
    timestamp = normalized["event_timestamp"]
    if timestamp.tzinfo is None:
        timestamp = timestamp.replace(tzinfo=timezone.utc)
    normalized["event_timestamp"] = timestamp
    normalized["created_timestamp"] = datetime.now(timezone.utc)
    return pd.DataFrame([normalized])
