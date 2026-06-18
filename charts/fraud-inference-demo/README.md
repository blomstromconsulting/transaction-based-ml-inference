# fraud-inference-demo Helm Chart

This chart deploys the transaction-based fraud inference demo to Kubernetes:

- Quarkus `transaction-events` service
- Redis online feature/statistics store
- Optional Postgres Feast offline store for retraining
- Optional single-node RustFS S3-compatible artifact store
- Optional MLflow tracking server backed by the same Postgres instance
- Optional training job that registers MLflow models
- Feast feature server
- Feast feature writer for online materialization
- KServe `InferenceService` resources for Model A and Model B
- KServe Transformer configuration for Feast enrichment
- ConfigMap for model-to-Feature-Service mappings

## Install

```bash
helm upgrade --install fraud-demo ./charts/fraud-inference-demo \
  --namespace fraud-demo \
  --create-namespace
```

## Render Locally

```bash
helm template fraud-demo ./charts/fraud-inference-demo
```

## Disable KServe Resources

Use this when KServe CRDs are not installed in the cluster:

```bash
helm upgrade --install fraud-demo ./charts/fraud-inference-demo \
  --set kserve.enabled=false
```

For a local smoke test without real model images, enable mock predictors:

```bash
helm upgrade --install fraud-demo ./charts/fraud-inference-demo \
  --set kserve.enabled=false \
  --set mockPredictors.enabled=true
```

## Override Images

```bash
helm upgrade --install fraud-demo ./charts/fraud-inference-demo \
  --set transactionEvents.image.repository=registry.example.com/transaction-events \
  --set transactionEvents.image.tag=1.0.0 \
  --set kserve.transformer.image.repository=registry.example.com/fraud-feature-transformer \
  --set kserve.transformer.image.tag=1.0.0
```

## Real Feast and KServe Demo Mode

Build local images from the repository root:

```bash
docker build -f src/main/docker/Dockerfile.jvm -t transaction-events:local .
docker build -f feast/Dockerfile -t fraud-feast-repo:local .
docker build -f feast/writer/Dockerfile -t fraud-feast-writer:local .
docker build -f kserve/transformer/Dockerfile -t fraud-feature-transformer:local .
docker build -f kserve/java-transformer/Dockerfile -t fraud-java-transformer:local .
docker build -f training/Dockerfile -t fraud-training:local .
docker build -f training/mlflow/Dockerfile -t fraud-mlflow:local .
```

Deploy with the example values file:

```bash
helm upgrade --install fraud-demo ./charts/fraud-inference-demo \
  --namespace fraud-demo \
  --create-namespace \
  -f ./charts/fraud-inference-demo/values-real-feast-kserve-demo.yaml
```

This mode deploys Postgres, Redis, single-node RustFS, MLflow, a real Feast feature server, Feast feature writer, and real KServe `InferenceService` resources with the Feast transformer. The example starts with tiny Python predictor containers for Model A and Model B so the chart is runnable before a model has been trained.

The chart supports two transformer implementations:

```yaml
kserve:
  transformer:
    implementation: python
```

or:

```yaml
kserve:
  transformer:
    implementation: java
```

The Python transformer uses the KServe and Feast Python SDKs. The Java transformer is a Quarkus proxy transformer that calls Feast feature server REST, validates required online feature values, calls the predictor URL, and returns the same fraud response shape. The real demo values use the Java transformer.

The same values file enables Postgres and configures Feast with:

```yaml
postgres:
  enabled: true

feast:
  offlineStore:
    type: postgres
```

Redis remains the Feast online store used by the transformer. Postgres is the Feast offline store used by retraining jobs for historical feature retrieval. When Postgres is enabled, the Quarkus service writes live transaction facts, historical feature rows, and prediction logs through its offline data sink. Feast reads those Postgres tables as offline data sources; labels still need to be supplied later from fraud outcomes.

MLflow uses the same Postgres instance for tracking metadata and RustFS for model artifacts:

```yaml
rustfs:
  enabled: true

mlflow:
  enabled: true
  artifactRoot: s3://mlflow-artifacts
```

Run a one-shot in-cluster training job:

```bash
helm upgrade fraud-demo ./charts/fraud-inference-demo \
  --namespace fraud-demo \
  -f ./charts/fraud-inference-demo/values-real-feast-kserve-demo.yaml \
  --set trainingJob.enabled=true \
  --set-string trainingJob.runId="$(date +%s)" \
  --set-string trainingJob.model=MODEL_B \
  --set-string trainingJob.registeredModelName=fraud-MODEL_B
```

Or run the full clean deployment, training, promotion, scoring, and evaluation flow:

```bash
scripts/run_e2e_mlflow_demo.sh
```

The full demo deletes and recreates the namespace unless `RESET=false` is set.

## Access MLflow and RustFS Artifacts

With the default release name and namespace from the examples, port-forward MLflow:

```bash
kubectl port-forward -n fraud-demo svc/fraud-demo-fraud-inference-demo-mlflow 5000:5000
```

Open [http://localhost:5000](http://localhost:5000). The MLflow UI exposes the training run history, parameters, metrics, logged artifacts, and model registry entries. For the full demo, inspect the latest run and the `fraud-MODEL_B` registered model. The logged artifacts include the training dataset fetched through Feast, validation rows with fraud scores, the confusion matrix, and the pyfunc model artifact that is later packaged into the KServe predictor image.

The chart stores MLflow artifacts in the RustFS bucket configured by `mlflow.artifactRoot`, which defaults to `s3://mlflow-artifacts`. To inspect the bucket in the RustFS console:

```bash
kubectl port-forward -n fraud-demo svc/fraud-demo-fraud-inference-demo-rustfs 9001:9001
```

Open [http://localhost:9001](http://localhost:9001). The default credentials are:

```text
username: mlflow
password: mlflow-secret
```

If those values were overridden, read the generated secret:

```bash
kubectl get secret -n fraud-demo fraud-demo-fraud-inference-demo-rustfs-credentials \
  -o jsonpath='{.data.accessKey}' | base64 --decode
echo
kubectl get secret -n fraud-demo fraud-demo-fraud-inference-demo-rustfs-credentials \
  -o jsonpath='{.data.secretKey}' | base64 --decode
echo
```

For CLI inspection, port-forward the RustFS S3 API and use the same credentials:

```bash
kubectl port-forward -n fraud-demo svc/fraud-demo-fraud-inference-demo-rustfs 9000:9000

AWS_ACCESS_KEY_ID=mlflow \
AWS_SECRET_ACCESS_KEY=mlflow-secret \
AWS_DEFAULT_REGION=us-east-1 \
aws --endpoint-url http://localhost:9000 s3 ls s3://mlflow-artifacts --recursive
```

When `postgres.enabled=true`, the chart configures the Quarkus datasource and sets `QUARKUS_FLYWAY_MIGRATE_AT_START=true`. Schema migrations run from the application image's `db/migration` resources before the transaction service starts accepting traffic. The Postgres password is projected from a Kubernetes Secret; set `postgres.existingSecret` and `postgres.passwordSecretKey` to use a pre-created Secret.

## Connect to the Deployed Postgres Database

With the default release name and namespace from the examples, port-forward the chart-managed Postgres service:

```bash
kubectl port-forward -n fraud-demo svc/fraud-demo-fraud-inference-demo-postgres 15432:5432
```

In another terminal, read the generated password and connect with `psql`:

```bash
export PGPASSWORD="$(
  kubectl get secret -n fraud-demo fraud-demo-fraud-inference-demo-postgres-credentials \
    -o jsonpath='{.data.password}' | base64 --decode
)"

psql "host=localhost port=15432 dbname=fraud_features user=feast sslmode=disable"
```

Useful inspection queries:

```sql
select version, description, success
from flyway_schema_history
order by installed_rank;

select *
from fraud_transactions
order by event_timestamp desc
limit 10;

select *
from customer_transaction_stats
order by event_timestamp desc
limit 10;
```

If you override `postgres.database`, `postgres.user`, `postgres.passwordSecretKey`, `postgres.existingSecret`, the release name, or the namespace, adjust the service name, Secret name, jsonpath key, and connection string accordingly. For production deployments, prefer `postgres.existingSecret` over storing the password in Helm values.

For production-like use, replace `models.MODEL_A.predictorImage`, `models.MODEL_B.predictorImage`, `predictorCommand`, and `predictorArgs` with real model-serving images.

## Feast Online Store Note

Feast Redis online store entries must be written through Feast materialization or a Feast-compatible writer. This chart can deploy `featureWriter.enabled=true`, which exposes a small HTTP service that uses the Feast SDK to write rows into Feast's Redis online store format.

The Quarkus Redis adapter stores functional online feature state for rolling customer windows and merchant visits. Those keys are not read by the transformer; the transformer reads materialized features through Feast Feature Services. Configure the merchant visit window with `transactionEvents.merchantVisitWindowDays`.

## Model Feature Service Mapping

The chart centralizes model-to-Feature-Service mapping in `values.yaml`:

```yaml
models:
  MODEL_A:
    kserveUrl: http://fraud-model-a.default/v1/models/fraud-model-a:predict
    featureService: fraud_model_a_feature_service
    modelVersion: demo-a-v1
  MODEL_B:
    kserveUrl: http://fraud-model-b.default/v1/models/fraud-model-b:predict
    featureService: fraud_model_b_feature_service
    modelVersion: demo-b-v1
```

These values are projected into the Quarkus application and the KServe transformer through a generated ConfigMap. The Quarkus transaction service uses configured model ids, so adding a model is a values/config change as long as the Feature Service and predictor are deployed.
