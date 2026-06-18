# Offline Retraining With Feast and Postgres

This directory shows the production-like retraining path for the fraud inference demo.

The online path still uses Redis for low-latency inference. The offline path uses Postgres as the Feast offline store so training jobs can build point-in-time correct datasets from historical transactions, labels, and feature values.

When `fraud.offline-store.enabled=true`, Quarkus runs Flyway migrations from `src/main/resources/db/migration` and writes live transactions, historical feature rows, and prediction logs to Postgres through its offline data sink. Feast reads those Postgres tables as offline data sources for historical retrieval; Feast is not the writer for this Postgres path. The sample loader in this directory is still useful for local demos because it inserts data and labels after Flyway has created the schema; production labels usually arrive later from review, chargeback, dispute, or case-management systems.

## Data Needed

- `fraud_transactions`: historical transaction facts.
- `fraud_labels`: latest confirmed fraud or legitimate outcome per transaction.
- `fraud_label_events`: immutable annotation history for label changes.
- `customer_transaction_stats`: historical customer aggregate and merchant-visit feature values.
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

Apply Flyway migrations by starting the Quarkus app with the offline store enabled:

```bash
FRAUD_OFFLINE_STORE_ENABLED=true \
FRAUD_OFFLINE_STORE_JDBC_URL=jdbc:postgresql://localhost:5432/fraud_features \
FRAUD_OFFLINE_STORE_USER=feast \
FRAUD_OFFLINE_STORE_PASSWORD=feast \
FRAUD_MODEL_MODEL_A_KSERVE_URL=http://localhost:18081/v1/models/fraud-model-a:predict \
FRAUD_MODEL_MODEL_B_KSERVE_URL=http://localhost:18081/v1/models/fraud-model-b:predict \
mvn quarkus:dev
```

Load sample labeled data:

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

Train and evaluate a candidate model. The script sorts by `event_timestamp`, trains on the oldest 70% of rows, and evaluates on the newest 30% so the demo avoids random-split leakage:

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

## MLflow Training

For the Kubernetes demo, train through MLflow instead of writing only a local `.joblib` file:

```bash
MLFLOW_TRACKING_URI=http://localhost:5000 \
MLFLOW_S3_ENDPOINT_URL=http://localhost:9000 \
AWS_ACCESS_KEY_ID=mlflow \
AWS_SECRET_ACCESS_KEY=mlflow-secret \
FEAST_OFFLINE_STORE_TYPE=postgres \
python training/scripts/train_with_mlflow.py \
  --model MODEL_B \
  --registered-model-name fraud-MODEL_B
```

The MLflow run logs:

- parameters for the model, threshold, feature service, and label maturity window
- precision, recall, F1, PR-AUC, ROC-AUC when labels allow it
- the Feast historical training dataset
- validation rows with fraud scores
- confusion matrix
- a pyfunc model artifact that returns `fraud_score`

The Helm chart can run the same script as a Kubernetes `Job`. Model artifacts are stored in RustFS and model registry metadata is stored in the same Postgres instance as the fraud offline store.

Inspect the generated runs by forwarding the MLflow tracking server:

```bash
kubectl port-forward -n fraud-demo svc/fraud-demo-fraud-inference-demo-mlflow 5000:5000
```

Open [http://localhost:5000](http://localhost:5000), then inspect the latest run and the `fraud-MODEL_B` registered model. The run artifact browser shows the logged datasets, validation outputs, confusion matrix, and pyfunc model artifact.

Inspect the artifact bucket directly through RustFS:

```bash
kubectl port-forward -n fraud-demo svc/fraud-demo-fraud-inference-demo-rustfs 9001:9001
```

Open [http://localhost:9001](http://localhost:9001) and use the default credentials `mlflow` / `mlflow-secret`. The default artifact bucket is `mlflow-artifacts`.

For S3-compatible CLI access:

```bash
kubectl port-forward -n fraud-demo svc/fraud-demo-fraud-inference-demo-rustfs 9000:9000

AWS_ACCESS_KEY_ID=mlflow \
AWS_SECRET_ACCESS_KEY=mlflow-secret \
AWS_DEFAULT_REGION=us-east-1 \
aws --endpoint-url http://localhost:9000 s3 ls s3://mlflow-artifacts --recursive
```

After a model is registered, package and deploy it with:

```bash
scripts/simulate_model_ci.sh \
  --model MODEL_B \
  --registered-model-name fraud-MODEL_B \
  --model-version 1 \
  --image-repository fraud-model-b
```

Evaluate deployed predictions once new transactions have labels:

```bash
TRAINING_DATABASE_URL=postgresql://feast:feast@localhost:5432/fraud_features \
python training/scripts/evaluate_deployed_model.py \
  --model MODEL_B \
  --model-version mlflow-1
```

Check whether recent feature distributions have drifted enough to retrain:

```bash
TRAINING_DATABASE_URL=postgresql://feast:feast@localhost:5432/fraud_features \
python training/scripts/check_data_drift.py
```

## Production Notes

- Use a time-based train/validation/test split for fraud. Random splits often leak future behavior into training.
- Labels should include `label_timestamp`; use `--min-label-age-days` so training only uses mature outcomes.
- Keep online and offline feature definitions aligned in Feast.
- Promote a model only after comparing precision, recall, PR-AUC, false positive cost, and false negative fraud loss.
