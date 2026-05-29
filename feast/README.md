# Feast Repository

This directory is a minimal Feast repository for the demo.

Redis is configured as the Feast online store in `feature_store.yaml`. The Quarkus transaction-events service updates customer statistics in Redis with keys such as `fraud:customer:{customer_id}:stats`. Feast exposes those online values through Feature Services:

- `fraud_model_a_feature_service`
- `fraud_model_b_feature_service`

The Python feature definitions keep common customer features in `common_customer_features`, while Model B adds extended customer and merchant risk features.

Example commands:

```bash
cd feast
feast apply
feast serve --host 0.0.0.0 --port 6566
```

Build a deployable Feast image from the repository root:

```bash
docker build -f feast/Dockerfile -t fraud-feast-repo:local .
```

Build the feature writer image:

```bash
docker build -f feast/writer/Dockerfile -t fraud-feast-writer:local .
```

At runtime, `scripts/render_feature_store.py` sets the Redis online store connection from `FEAST_REDIS_CONNECTION_STRING` before running `feast apply` and `feast serve`.

For retraining, the same Feast repository can use Postgres as the offline store:

```bash
FEAST_OFFLINE_STORE_TYPE=postgres \
FEAST_POSTGRES_HOST=localhost \
FEAST_POSTGRES_PORT=5432 \
FEAST_POSTGRES_DATABASE=fraud_features \
FEAST_POSTGRES_USER=feast \
FEAST_POSTGRES_PASSWORD=feast \
FEAST_POSTGRES_SSLMODE=disable \
python feast/scripts/render_feature_store.py
```

When `FEAST_OFFLINE_STORE_TYPE=postgres`, `feature_views.py` uses `PostgreSQLSource` tables:

- `customer_transaction_stats`
- `merchant_risk_features`

Redis remains the online store; Postgres is used for historical, point-in-time training retrieval.

The feature writer exposes:

- `POST /materialize/customer`
- `POST /materialize/merchant`

It uses the Feast SDK to write rows to the Feast online store instead of writing application-specific Redis keys.
