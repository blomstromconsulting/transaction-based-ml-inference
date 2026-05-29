from datetime import timedelta

from feast import Field, FeatureView, FileSource
from feast.types import Float32, Float64, Int64, String

from entities import customer, merchant

# Placeholder batch sources document schemas for Feast apply/materialize.
# The online values are written to Redis by the transaction-events service in this demo.
customer_stats_source = FileSource(
    name="customer_stats_source",
    path="data/customer_stats.parquet",
    timestamp_field="event_timestamp",
)

merchant_risk_source = FileSource(
    name="merchant_risk_source",
    path="data/merchant_risk.parquet",
    timestamp_field="event_timestamp",
)

customer_transaction_stats_view = FeatureView(
    name="customer_transaction_stats_view",
    entities=[customer],
    ttl=timedelta(days=8),
    schema=[
        Field(name="customer_transaction_count_1h", dtype=Int64),
        Field(name="customer_transaction_count_24h", dtype=Int64),
        Field(name="customer_total_amount_24h", dtype=Float64),
        Field(name="customer_avg_amount_7d", dtype=Float64),
        Field(name="customer_max_amount_7d", dtype=Float64),
        Field(name="customer_distinct_merchants_24h", dtype=Int64),
        Field(name="customer_cross_border_count_7d", dtype=Int64),
    ],
    online=True,
    source=customer_stats_source,
    tags={"store": "redis", "aggregation_key": "customer_id"},
)

merchant_risk_view = FeatureView(
    name="merchant_risk_view",
    entities=[merchant],
    ttl=timedelta(days=7),
    schema=[
        Field(name="merchant_risk_score", dtype=Float32),
        Field(name="merchant_category", dtype=String),
    ],
    online=True,
    source=merchant_risk_source,
    tags={"store": "redis", "aggregation_key": "merchant_id"},
)
