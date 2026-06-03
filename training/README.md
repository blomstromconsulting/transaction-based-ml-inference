# Offline Retraining With Feast and Postgres

This directory shows the production-like retraining path for the fraud inference demo.

The online path still uses Redis for low-latency inference. The offline path uses Postgres as the Feast offline store so training jobs can build point-in-time correct datasets from historical transactions, labels, and feature values.

When `fraud.offline-store.enabled=true`, the Quarkus application writes live transactions, historical feature rows, and prediction logs to Postgres through its offline data sink. Feast reads those Postgres tables as offline data sources for historical retrieval; Feast is not the writer for this Postgres path. The sample loader in this directory is still useful for local demos because it inserts labels; production labels usually arrive later from review, chargeback, dispute, or case-management systems.

## Data Needed

- `fraud_transactions`: historical transaction facts.
- `fraud_labels`: latest confirmed fraud or legitimate outcome per transaction.
- `fraud_label_events`: immutable annotation history for label changes.
- `customer_transaction_stats`: historical customer aggregate feature values.
- `merchant_risk_features`: historical merchant feature values.
- `fraud_prediction_logs`: optional model score and decision audit trail.
- `fraud_transaction_processing`: processing status for live inference attempts.

`fraud_training_examples` joins transactions and latest labels into the entity dataframe used by Feast historical retrieval. The view includes label metadata such as `label_timestamp`, `label_source`, and `label_confidence`.

Model-specific Feast feature references and training columns are defined in [model_catalog.json](./model_catalog.json). Add a new model there before building a dataset or training artifact for it.

## Local Setup

Start Postgres:

```bash
docker run --rm --name fraud-postgres \
  -p 5432:5432 \
  -e POSTGRES_DB=fraud_features \
  -e POSTGRES_USER=feast \
  -e POSTGRES_PASSWORD=feast \
  postgres:16-alpine
```

Install Python dependencies:

```bash
python -m venv .venv-training
source .venv-training/bin/activate
pip install -r training/requirements.txt
```

Load schema and sample labeled data:

```bash
TRAINING_DATABASE_URL=postgresql://feast:feast@localhost:5432/fraud_features \
python training/scripts/load_sample_data.py
```

For live application data, labels are ingested through the Quarkus API after the transaction has been processed:

```bash
curl -X PUT http://localhost:8080/transactions/tx-10001/label \
  -H 'Content-Type: application/json' \
  -d '{
    "is_fraud": true,
    "label_timestamp": "2026-06-03T08:30:00Z",
    "label_source": "chargeback",
    "label_confidence": 1.0,
    "annotator_id": "chargeback-system",
    "reason_code": "confirmed_cardholder_dispute"
  }'
```

Render Feast for Postgres offline retrieval:

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

Build a point-in-time training dataset:

```bash
FEAST_OFFLINE_STORE_TYPE=postgres \
python training/scripts/build_training_dataset.py \
  --model MODEL_B \
  --min-label-age-days 14 \
  --output training/output/model_b_training.parquet
```

Train and evaluate a candidate model:

```bash
python training/scripts/train_model.py \
  --model MODEL_B \
  --input training/output/model_b_training.parquet \
  --output training/output/model_b.joblib

python training/scripts/evaluate.py \
  --model-artifact training/output/model_b.joblib \
  --input training/output/model_b_training.parquet \
  --output training/output/model_b_classified.parquet
```

The classified output includes:

- `fraud_score`
- `expected_decision`
- `expected_is_fraud`

Use those columns to compare a newly trained candidate against the confirmed `is_fraud` label and against the currently deployed model.

## Production Notes

- Use a time-based train/validation/test split for fraud. Random splits often leak future behavior into training.
- Labels should include `label_timestamp`; use `--min-label-age-days` so training only uses mature outcomes.
- Keep online and offline feature definitions aligned in Feast.
- Promote a model only after comparing precision, recall, PR-AUC, false positive cost, and false negative fraud loss.
