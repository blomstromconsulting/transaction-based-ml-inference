# Transaction-Based ML Inference Demo

This repository contains a Kubernetes-deployable Quarkus demo for credit card fraud detection with transaction-driven feature updates, Feast online feature lookup backed by Redis, and KServe model inference with a transformer.

## Reproduce the Full Training and Promotion Scenario

The main demo scenario is intentionally scriptable: start from a clean Kubernetes namespace, generate transaction traffic, mark fraud outcomes, train a new model from Feast/Postgres offline data, register the model in MLflow, package the MLflow artifact into a predictor image, deploy it through KServe, send new transactions through the live flow, and evaluate the deployed model from prediction logs plus labels.

Run the full scenario with one command:

```bash
scripts/run_e2e_mlflow_demo.sh
```

For local clusters where images must be loaded explicitly:

```bash
KIND_CLUSTER=kind scripts/run_e2e_mlflow_demo.sh

MINIKUBE_PROFILE=minikube scripts/run_e2e_mlflow_demo.sh
```

The script deletes and recreates the `fraud-demo` namespace by default, so the run does not depend on historical data:

```bash
RESET=true scripts/run_e2e_mlflow_demo.sh
```

Set `RESET=false` only when you intentionally want to keep the namespace and reuse existing deployed infrastructure:

```bash
RESET=false scripts/run_e2e_mlflow_demo.sh
```

What the scenario does:

1. Builds the Java transaction service, Java KServe transformer, Feast images, MLflow image, training image, and model-serving image.
2. Deploys Postgres, Redis, RustFS, MLflow, Feast, the feature writer, and KServe resources through Helm.
3. Sends training transactions through the public transaction API.
4. Marks a labeled set as fraud or non-fraud through the label API.
5. Runs an in-cluster training job that retrieves point-in-time features from Feast/Postgres and logs the run to MLflow.
6. Stores model artifacts in the RustFS S3-compatible bucket used by MLflow.
7. Simulates the CI promotion step with `scripts/simulate_model_ci.sh`, which builds a predictor image from the MLflow artifact and deploys it to KServe.
8. Sends new scoring transactions through the same online path: transaction service, Redis feature state, Feast online store, KServe transformer, and trained predictor.
9. Evaluates deployed-model performance by joining `fraud_prediction_logs` with `fraud_labels`.

Successful runs end with:

```text
E2E_DEMO_SUCCEEDED run_id=<run_id> model_version=mlflow-<version>
```

Use the script as the default demo launcher. It is more repeatable than asking an agent to execute each step manually because it fixes the order, inputs, labels, waits, cleanup, and evaluation in source control. An agent is still useful for explaining failures, inspecting MLflow/Feast/Postgres afterward, or changing the scenario, but the scripted path should remain the reproducible acceptance test.

After the scenario finishes, inspect the generated artifacts:

- MLflow runs and registered model: `kubectl port-forward -n fraud-demo svc/fraud-demo-fraud-inference-demo-mlflow 5000:5000`, then open [http://localhost:5000](http://localhost:5000).
- RustFS model artifacts: `kubectl port-forward -n fraud-demo svc/fraud-demo-fraud-inference-demo-rustfs 9001:9001`, then open [http://localhost:9001](http://localhost:9001).
- Feast feature mappings: `scripts/start_feast_ui.sh`, then open [http://127.0.0.1:8888](http://127.0.0.1:8888).
- Postgres training, label, and prediction tables: port-forward the chart-managed Postgres service as described in the Postgres inspection section.

## End-to-end Flow

The deployed demo has two data paths:

- **Online inference path**: update rolling features, materialize them to Feast's Redis online store, enrich the KServe request, and score the transaction.
- **Offline training path**: persist normalized transactions, historical feature rows, and model decisions through a Postgres offline data sink so labels can be joined later for Feast historical retrieval and retraining.

```text
Transaction Event
  -> transaction-events Quarkus service
  -> validate and normalize
  -> update Redis online feature state
  -> derive rolling and merchant-visit feature snapshot
  -> write normalized transaction facts to Postgres offline data sink
  -> materialize aggregate feature rows through Feast feature writer
  -> Feast writes online features to Redis using its online store format
  -> write historical customer and merchant feature rows to Postgres offline data sink
  -> trigger KServe InferenceService
  -> KServe Transformer
  -> Feast Feature Service lookup
  -> Feast reads online features from Redis
  -> Transformer creates model input
  -> KServe Predictor: Model A or Model B
  -> fraud score and decision metadata
  -> write prediction audit row to Postgres offline data sink
  -> fraud decision event is logged/published
```

## Data Flow Diagram

```mermaid
flowchart LR
    event["Incoming transaction event"] --> rest["transaction-events service<br/>REST or messaging adapter"]
    rest --> app["Application use cases<br/>validate, normalize, orchestrate"]

    app -- "1. record transaction" --> offlineTx[("Postgres offline data sink<br/>fraud_transactions")]
    offlineTx -. "later joined with labels" .-> labels[("fraud_labels<br/>chargebacks, disputes, review")]

    app -- "2. update + snapshot state" --> redisAdapter["Redis online feature state adapter"]
    redisAdapter --> featureState[("Redis feature state<br/>rolling windows + merchant visits")]
    app -- "3. materialize online features" --> writer["Feast feature writer<br/>materialize feature rows"]
    writer --> feastOnline[("Redis Feast online store<br/>Feast internal format")]
    app -- "4. record feature rows" --> offlineFeatures[("Postgres offline data sink<br/>customer_transaction_stats<br/>merchant_risk_features")]
    app -- "5. trigger inference" --> kserveAdapter["KServe inference adapter"]

    kserveAdapter --> isvc["KServe InferenceService<br/>Model A or Model B"]
    isvc --> transformer["KServe Transformer<br/>feature enrichment"]
    transformer --> feast["Feast Feature Service lookup<br/>model-specific feature set"]
    feast --> feastOnline
    feastOnline --> feast
    feast --> transformer
    transformer --> payload["Enriched model input<br/>transaction fields + online features"]
    payload --> predictor{"Selected predictor"}
    predictor --> modelA["Fraud Model A<br/>baseline feature service"]
    predictor --> modelB["Fraud Model B<br/>extended feature service"]
    modelA --> result["Fraud score + metadata"]
    modelB --> result
    result --> offlinePredictions[("Postgres offline data sink<br/>fraud_prediction_logs")]
    result --> decision["Fraud decision event<br/>APPROVE, REVIEW, or DECLINE"]
    decision --> publisher["Decision publisher/log"]

    labels --> training["Feast historical retrieval<br/>point-in-time training set"]
    offlineFeatures --> training
    training --> retrain["Train/evaluate candidate model"]
```

## Request Swimlane

This swimlane follows the deployed real Feast, KServe, Redis, and Postgres path. Each note shows the main content carried by the request or persisted at that step.

```mermaid
sequenceDiagram
    autonumber
    participant Client as Client
    participant TxSvc as transaction-events<br/>Quarkus service
    participant Offline as Postgres<br/>offline data sink
    participant FeatureState as Redis<br/>online feature state
    participant Writer as Feast feature writer
    participant FeastRedis as Redis<br/>Feast online store
    participant KServe as KServe<br/>InferenceService
    participant Transformer as KServe Transformer
    participant Feast as Feast SDK<br/>Feature Service lookup
    participant Predictor as KServe Predictor<br/>Model A or B
    participant Publisher as Decision publisher/log

    Client->>TxSvc: POST /transactions
    Note over Client,TxSvc: TransactionEvent: transaction_id, customer_id, card_id, merchant_id, merchant_category, amount, currency, country, timestamp, requested_model

    TxSvc->>TxSvc: Validate and normalize
    Note over TxSvc: Normalized model: amount as transaction_amount, country as transaction_country, model defaults if omitted

    TxSvc->>Offline: INSERT fraud_transactions
    Note over TxSvc,Offline: Historical fact row: transaction_id, customer_id, card_id, merchant_id, merchant_category, amount, currency, country, event_timestamp

    TxSvc->>FeatureState: Update and snapshot online feature state
    Note over TxSvc,FeatureState: Keys: fraud:feature-state:customer:{customer_id}:tx-events, tx-details, and merchant visit day buckets

    TxSvc->>Writer: POST /materialize/customer
    Note over TxSvc,Writer: CustomerFeatureRow: rolling counts, amounts, cross-border stats, merchant visit counts/rank/top/new-merchant features

    Writer->>FeastRedis: FeatureStore.write_to_online_store(customer_transaction_stats_view)
    Note over Writer,FeastRedis: Feast-formatted online values keyed by customer_id, including event_timestamp and created_timestamp

    TxSvc->>Offline: UPSERT customer_transaction_stats
    Note over TxSvc,Offline: Same customer feature row persisted for historical retrieval and retraining

    TxSvc->>Writer: POST /materialize/merchant
    Note over TxSvc,Writer: MerchantFeatureRow: merchant_id, event_timestamp, merchant_risk_score, merchant_category

    Writer->>FeastRedis: FeatureStore.write_to_online_store(merchant_risk_view)
    Note over Writer,FeastRedis: Feast-formatted online values keyed by merchant_id, including event_timestamp and created_timestamp

    TxSvc->>Offline: UPSERT merchant_risk_features
    Note over TxSvc,Offline: Merchant feature row persisted for model training and audit

    TxSvc->>KServe: POST /v1/models/{model}:predict
    Note over TxSvc,KServe: KServe envelope: model, featureService, transaction fields

    KServe->>Transformer: Invoke transformer preprocess
    Note over Transformer: Resolve Feature Service: MODEL_A -> fraud_model_a_feature_service, MODEL_B -> fraud_model_b_feature_service

    Transformer->>Feast: get_feature_service(featureService)
    Note over Transformer,Feast: Feature refs come from Feast Feature Service, not hard-coded application logic

    Transformer->>Feast: get_online_features(entity_rows)
    Note over Transformer,Feast: Entity rows: customer_id and merchant_id

    Feast->>FeastRedis: Read online features
    FeastRedis-->>Feast: Feature values
    Feast-->>Transformer: Online feature map
    Note over Feast,Transformer: Customer rolling stats, merchant visit features, and optional merchant risk from Redis through Feast

    Transformer->>Predictor: Forward enriched request
    Note over Transformer,Predictor: instances[0]: transaction fields, customer stats, merchant visit features, merchant_risk_score for Model B

    Predictor-->>Transformer: Prediction response
    Note over Predictor,Transformer: predictions[0].fraud_score

    Transformer-->>KServe: Fraud response
    Note over Transformer,KServe: fraudScore, decision, featuresUsed, metadata

    KServe-->>TxSvc: Inference result
    TxSvc->>Offline: INSERT fraud_prediction_logs
    Note over TxSvc,Offline: Prediction audit row: transaction_id, model, fraud_score, decision, features_used, created_timestamp

    TxSvc->>Publisher: Publish/log fraud decision
    Note over TxSvc,Publisher: FraudDecision: transaction_id, model, fraud_score, decision, features_used

    TxSvc-->>Client: 202 Accepted
    Note over TxSvc,Client: API response: transaction_id, model, fraud_score, decision, features_used
```

The online path and offline path intentionally store different shapes:

- Redis feature-state keys are functional online state used to derive the feature snapshot before inference.
- Redis Feast online-store keys are owned by Feast and are read by the KServe transformer through Feast APIs.
- Postgres stores historical facts, feature rows, labels, and prediction logs for retraining and audit. Quarkus writes these rows directly through `OfflineDataSinkPort`; Feast reads the feature tables as configured offline data sources.

The application service does not hard-code every model feature. It triggers the requested model and passes the configured Feast Feature Service name. Model-specific feature sets live in Feast:

- `fraud_model_a_feature_service`
- `fraud_model_b_feature_service`

## Architecture

The Java code follows Hexagonal Architecture:

- `domain`: models, input ports, output ports, and domain-only normalization
- `application`: use case orchestration
- `adapter.in`: REST and optional messaging entry points
- `adapter.out`: Redis, Feast, KServe, and decision publishing adapters
- `infrastructure`: configuration and deployment-related support

The domain layer has no Redis, Feast, KServe, Kafka, HTTP, or Kubernetes dependencies.

The transaction service treats the requested model as a configured model id string, not a source-code enum. To add another model, add its `fraud.model.<MODEL_ID>.kserve-url`, `feature-service`, and `model-version` entries, then deploy the matching Feature Service and predictor.

## Models and Feature Services

Model A is the baseline model. It combines event-time transaction fields with common customer features:

- `transaction_amount`
- `transaction_country`
- `merchant_category`
- `customer_transaction_count_1h`
- `customer_transaction_count_24h`
- `customer_total_amount_24h`
- `customer_avg_amount_7d`

Model B extends Model A with more customer and merchant features:

- `customer_max_amount_7d`
- `customer_distinct_merchants_24h`
- `customer_cross_border_count_7d`
- `current_merchant_visit_count_30d`
- `current_merchant_visit_share_30d`
- `current_merchant_rank_30d`
- `is_current_merchant_top_visited_30d`
- `days_since_first_seen_current_merchant`
- `days_since_last_seen_current_merchant`
- `customer_distinct_merchants_30d`
- `is_new_merchant_for_customer`
- `top_visited_merchant_id_30d`
- `merchant_risk_score`

The Feast repository under [feast](./feast) defines common reusable customer features and composes them into separate Feature Services for each model. Training scripts read [training/model_catalog.json](./training/model_catalog.json) so model feature references and training columns stay centralized for the offline path.

### Feast UI

Use the helper script to inspect Feast Feature Views, Feature Services, entities, data sources, and their relationships:

```bash
scripts/start_feast_ui.sh
```

Open [http://127.0.0.1:8888](http://127.0.0.1:8888), then inspect the `fraud_model_a_feature_service` and `fraud_model_b_feature_service` Feature Services. The script creates a local virtualenv outside the repository, runs `feast apply`, and starts the Feast beta Web UI against the local Feast registry.

To run the UI in the background:

```bash
DETACH=true scripts/start_feast_ui.sh
```

Use `PORT` to avoid conflicts:

```bash
PORT=8890 scripts/start_feast_ui.sh
```

`feast apply` writes a local registry under `feast/data/`; that directory is ignored by Git because it is generated runtime state.

## Redis Online Feature State

`RedisOnlineFeatureStateAdapter` implements `OnlineFeatureStatePort`. It stores functional online feature state, not debug-only data. The current implementation uses these key patterns:

- `fraud:feature-state:customer:{customer_id}:tx-events`
- `fraud:feature-state:customer:{customer_id}:tx-details`
- `fraud:feature-state:customer:{customer_id}:merchant-counts:{yyyy-mm-dd}`
- `fraud:feature-state:customer:{customer_id}:merchant-first-seen:{yyyy-mm-dd}`
- `fraud:feature-state:customer:{customer_id}:merchant-last-seen:{yyyy-mm-dd}`

The adapter uses a Redis sorted set plus transaction detail hash for event-time rolling transaction windows and bucketed hashes for configurable merchant-visit windows. Rolling-window updates use transaction-id idempotency markers so a retried transaction is not counted twice. The default merchant visit window is 30 days and can be configured with `fraud.features.merchant-visit-window-days`; changing that value changes feature semantics and should be coordinated with the model version using those features.

Merchant-visit features are calculated from prior state before the current transaction is added to the merchant visit bucket. This preserves novelty signals such as `is_new_merchant_for_customer`. After computing the feature row, the application calls the Feast feature writer, which uses the Feast SDK to write the row to Feast's Redis online store format. The transformer reads features through Feast, not directly from the Redis feature-state keys.

For higher-volume production systems, keep the same output contract but move feature computation to a dedicated service or stream processor when needed. Redis remains valid as an online feature state store for novelty and merchant-visit features; the important boundary is that model serving reads materialized feature rows through Feast or a Feature-Service-compatible API.

## KServe Transformer

The transformer scaffold is in [kserve/transformer](./kserve/transformer). It:

1. Receives the inference request containing the transaction event and target model.
2. Resolves the model's configured Feast Feature Service.
3. Extracts entity keys such as `customer_id` and `merchant_id`.
4. Calls Feast online serving.
5. Feast reads Redis-backed online feature values.
6. Validates that every feature required by the Feature Service was returned and is non-null.
7. Merges required transaction fields with Feature-Service-defined online features.
8. Builds an explicit model input payload.
9. Lets KServe forward the payload to the predictor.
10. Converts predictor output into a fraud decision response.

The transformer is intentionally strict by default. Missing transaction fields, missing Feast values, or null Feast values raise a clear `FeatureValidationError` instead of silently defaulting to zero. This makes a missing materialization job, stale feature pipeline, or mismatched Feature Service visible during smoke tests and production monitoring. Set `STRICT_FEATURE_VALIDATION=false` only for local experiments where fallback defaults are acceptable.

Two transformer implementations are provided:

- [kserve/transformer](./kserve/transformer): Python implementation using the KServe Python SDK and Feast Python SDK.
- [kserve/java-transformer](./kserve/java-transformer): Quarkus Java implementation that exposes the same prediction endpoint, calls Feast feature server REST, validates the feature response, calls the predictor, and returns the same fraud response shape.

The Python transformer is the default because Feast's Python SDK can resolve Feature Service definitions directly. The Java transformer is useful when JVM operational consistency, type checking, or latency predictability is more important; it uses Feast feature server REST and keeps the same request contract.

## Configuration

Main config lives in [src/main/resources/application.properties](./src/main/resources/application.properties):

```properties
fraud.redis.host=redis
fraud.redis.port=6379
fraud.feast.url=http://feast-feature-server:6566

fraud.model.MODEL_A.kserve-url=http://fraud-model-a.default/v1/models/fraud-model-a:predict
fraud.model.MODEL_A.feature-service=fraud_model_a_feature_service
fraud.model.MODEL_A.model-version=demo-a-v1

fraud.model.MODEL_B.kserve-url=http://fraud-model-b.default/v1/models/fraud-model-b:predict
fraud.model.MODEL_B.feature-service=fraud_model_b_feature_service
fraud.model.MODEL_B.model-version=demo-b-v1

fraud.features.home-country=SE
fraud.features.merchant-visit-window-days=30
fraud.kserve.connect-timeout-ms=2000
fraud.kserve.read-timeout-ms=5000
```

## Run Locally

Start Redis and the local mock predictor:

```bash
docker run --rm -p 6379:6379 redis:7-alpine
```

In another terminal:

```bash
python scripts/local_mock_predictor.py --port 18081
```

Run Quarkus:

```bash
FRAUD_MODEL_MODEL_A_KSERVE_URL=http://localhost:18081/v1/models/fraud-model-a:predict \
FRAUD_MODEL_MODEL_B_KSERVE_URL=http://localhost:18081/v1/models/fraud-model-b:predict \
./mvnw quarkus:dev
```

If Maven wrapper is not present, use:

```bash
FRAUD_MODEL_MODEL_A_KSERVE_URL=http://localhost:18081/v1/models/fraud-model-a:predict \
FRAUD_MODEL_MODEL_B_KSERVE_URL=http://localhost:18081/v1/models/fraud-model-b:predict \
mvn quarkus:dev
```

Submit a demo transaction:

```bash
curl -X POST http://localhost:8080/transactions \
  -H 'Content-Type: application/json' \
  -d '{
    "transaction_id": "tx-10001",
    "customer_id": "cust-123",
    "card_id": "card-456",
    "merchant_id": "merchant-789",
    "merchant_category": "electronics",
    "amount": 1299.99,
    "currency": "EUR",
    "country": "SE",
    "timestamp": "2026-05-29T12:00:00Z",
    "requested_model": "MODEL_B"
  }'
```

Example response:

```json
{
  "transaction_id": "tx-10001",
  "model": "MODEL_B",
  "model_version": "demo-b-v1",
  "feature_service": "fraud_model_b_feature_service",
  "fraud_score": 0.91,
  "decision": "DECLINE",
  "features_used": [
    "transaction_amount",
    "customer_transaction_count_1h",
    "customer_transaction_count_24h",
    "customer_total_amount_24h",
    "customer_avg_amount_7d",
    "customer_max_amount_7d",
    "customer_distinct_merchants_24h",
    "customer_cross_border_count_7d",
    "current_merchant_visit_count_30d",
    "current_merchant_visit_share_30d",
    "current_merchant_rank_30d",
    "is_current_merchant_top_visited_30d",
    "days_since_first_seen_current_merchant",
    "days_since_last_seen_current_merchant",
    "customer_distinct_merchants_30d",
    "is_new_merchant_for_customer",
    "top_visited_merchant_id_30d",
    "merchant_risk_score"
  ]
}
```

## Example Enrichment

Incoming KServe request:

```json
{
  "model": "MODEL_B",
  "featureService": "fraud_model_b_feature_service",
  "transaction": {
    "transaction_id": "tx-10001",
    "customer_id": "cust-123",
    "merchant_id": "merchant-789",
    "transaction_amount": 1299.99,
    "transaction_country": "SE",
    "merchant_category": "electronics"
  }
}
```

Transformer model input:

```json
{
  "instances": [
    {
      "transaction_amount": 1299.99,
      "transaction_country": "SE",
      "merchant_category": "electronics",
      "customer_transaction_count_1h": 3,
      "customer_transaction_count_24h": 8,
      "customer_total_amount_24h": 2440.50,
      "customer_avg_amount_7d": 180.25,
      "customer_max_amount_7d": 1299.99,
      "customer_distinct_merchants_24h": 5,
      "customer_cross_border_count_7d": 1,
      "current_merchant_visit_count_30d": 2,
      "current_merchant_visit_share_30d": 0.25,
      "current_merchant_rank_30d": 1,
      "is_current_merchant_top_visited_30d": 1,
      "days_since_first_seen_current_merchant": 12.5,
      "days_since_last_seen_current_merchant": 2.0,
      "customer_distinct_merchants_30d": 6,
      "is_new_merchant_for_customer": 0,
      "top_visited_merchant_id_30d": "merchant-789",
      "merchant_risk_score": 0.72
    }
  ]
}
```

Predictor response:

```json
{
  "predictions": [
    {
      "fraud_score": 0.91
    }
  ]
}
```

## Deploy to Kubernetes

Build and publish images for:

- Quarkus transaction-events service
- Feast repository image
- Fraud feature transformer
- Model A predictor
- Model B predictor

Example image build commands:

```bash
export REGISTRY=registry.example.com/fraud-demo
export TAG=1.0.0

docker build -f src/main/docker/Dockerfile.jvm -t $REGISTRY/transaction-events:$TAG .
docker build -f feast/Dockerfile -t $REGISTRY/fraud-feast-repo:$TAG .
docker build -f feast/writer/Dockerfile -t $REGISTRY/fraud-feast-writer:$TAG .
docker build -f kserve/transformer/Dockerfile -t $REGISTRY/fraud-feature-transformer:$TAG .
docker build -f kserve/java-transformer/Dockerfile -t $REGISTRY/fraud-java-transformer:$TAG .

docker push $REGISTRY/transaction-events:$TAG
docker push $REGISTRY/fraud-feast-repo:$TAG
docker push $REGISTRY/fraud-feast-writer:$TAG
docker push $REGISTRY/fraud-feature-transformer:$TAG
docker push $REGISTRY/fraud-java-transformer:$TAG
```

Then apply manifests:

```bash
kubectl apply -f k8s/redis.yaml
kubectl apply -f k8s/model-feature-config.yaml
kubectl apply -f k8s/feast-feature-server.yaml
kubectl apply -f k8s/kserve-model-a.yaml
kubectl apply -f k8s/kserve-model-b.yaml
kubectl apply -f k8s/transaction-events.yaml
```

The KServe manifests illustrate transformer-first inference. Each `InferenceService` attaches the transformer, which performs Feast enrichment before KServe invokes the selected predictor.

## Deploy with Helm

A Helm chart is available in [charts/fraud-inference-demo](./charts/fraud-inference-demo). It deploys the Quarkus service, Redis, Feast feature server, KServe `InferenceService` resources for Model A and Model B, the transformer configuration, and the model-to-Feature-Service ConfigMap.

Render the manifests:

```bash
helm template fraud-demo ./charts/fraud-inference-demo
```

Install or upgrade:

```bash
helm upgrade --install fraud-demo ./charts/fraud-inference-demo \
  --namespace fraud-demo \
  --create-namespace
```

If KServe CRDs are not installed in the target cluster, disable KServe resources while testing the rest of the deployment:

```bash
helm upgrade --install fraud-demo ./charts/fraud-inference-demo \
  --namespace fraud-demo \
  --create-namespace \
  --set kserve.enabled=false
```

For a real Feast + KServe deployment path using locally built demo images:

```bash
docker build -f src/main/docker/Dockerfile.jvm -t transaction-events:local .
docker build -f feast/Dockerfile -t fraud-feast-repo:local .
docker build -f feast/writer/Dockerfile -t fraud-feast-writer:local .
docker build -f kserve/transformer/Dockerfile -t fraud-feature-transformer:local .
docker build -f kserve/java-transformer/Dockerfile -t fraud-java-transformer:local .

helm upgrade --install fraud-demo ./charts/fraud-inference-demo \
  --namespace fraud-demo \
  --create-namespace \
  -f ./charts/fraud-inference-demo/values-real-feast-kserve-demo.yaml
```

The values file defaults to the Python transformer. To deploy the Java transformer instead:

```bash
helm upgrade --install fraud-demo ./charts/fraud-inference-demo \
  --namespace fraud-demo \
  --create-namespace \
  -f ./charts/fraud-inference-demo/values-real-feast-kserve-demo.yaml \
  --set kserve.transformer.implementation=java
```

The real Feast/KServe example deploys a Feast feature server, Feast feature writer, and KServe transformer. It still uses tiny Python demo predictors for Model A and Model B; replace those with real model-serving images for production-like testing.

The same values file also enables Postgres as the Feast offline store for retraining examples:

```yaml
postgres:
  enabled: true

feast:
  offlineStore:
    type: postgres
```

Postgres stores historical transactions, historical feature values, and prediction logs written by the Quarkus offline data sink. Feast treats those Postgres tables as offline data sources for historical retrieval; it is not the writer for this Postgres path. Fraud labels still arrive later from chargebacks, disputes, manual review, or another outcome system. Redis remains the online store used by low-latency inference.

To connect to the Helm-deployed Postgres database, port-forward the chart-managed service:

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

Useful checks after connecting:

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

After deployment, port-forward the Quarkus service:

```bash
kubectl port-forward -n fraud-demo svc/fraud-demo-fraud-inference-demo-transaction-events 18080:8080
```

Submit a Model B transaction through the deployed real path:

```bash
curl -sS -i -X POST http://localhost:18080/transactions \
  -H 'Content-Type: application/json' \
  -d '{
    "transaction_id": "tx-real-10006",
    "customer_id": "cust-123",
    "card_id": "card-456",
    "merchant_id": "merchant-789",
    "merchant_category": "electronics",
    "amount": 1299.99,
    "currency": "EUR",
    "country": "SE",
    "timestamp": "2026-05-29T12:20:00Z",
    "requested_model": "MODEL_B"
  }'
```

Expected response shape:

```json
{
  "model": "MODEL_B",
  "model_version": "demo-b-v1",
  "feature_service": "fraud_model_b_feature_service",
  "decision": "DECLINE",
  "transaction_id": "tx-real-10006",
  "fraud_score": 0.91,
  "features_used": [
    "transaction_amount",
    "transaction_country",
    "merchant_category",
    "customer_transaction_count_1h",
    "customer_transaction_count_24h",
    "customer_total_amount_24h",
    "customer_avg_amount_7d",
    "customer_max_amount_7d",
    "customer_distinct_merchants_24h",
    "customer_cross_border_count_7d",
    "current_merchant_visit_count_30d",
    "current_merchant_visit_share_30d",
    "current_merchant_rank_30d",
    "is_current_merchant_top_visited_30d",
    "days_since_first_seen_current_merchant",
    "days_since_last_seen_current_merchant",
    "customer_distinct_merchants_30d",
    "is_new_merchant_for_customer",
    "top_visited_merchant_id_30d",
    "merchant_risk_score"
  ]
}
```

## Project Structure

```text
src/main/java/com/example/fraud
  domain/model
  domain/port/in
  domain/port/out
  domain/service
  application/usecase
  adapter/in/rest
  adapter/in/messaging
  adapter/out/redis
  adapter/out/feast
  adapter/out/kserve
  adapter/out/event
  infrastructure/config
feast
training
kserve/transformer
k8s
charts/fraud-inference-demo
```

## Offline Retraining With Postgres

The online inference path needs Redis, but retraining needs an offline store with historical facts and labels. This demo uses Postgres as the Feast offline store for that path.

When `fraud.offline-store.enabled=true`, the Quarkus `transaction-events` service writes live inference traffic into Postgres through `OfflineDataSinkPort` and `PostgresOfflineDataSinkAdapter`:

- normalized transaction facts into `fraud_transactions`
- customer aggregate feature rows into `customer_transaction_stats`
- merchant risk feature rows into `merchant_risk_features`
- model scores, decisions, and feature names into `fraud_prediction_logs`
- processing status and retry state into `fraud_transaction_processing`

The application does not create fraud labels. Labels are delayed outcomes and must be loaded into `fraud_labels` from review, chargeback, or dispute systems before a transaction can be used as a supervised training example.

Flyway owns the Postgres schema. Quarkus runs migrations from [src/main/resources/db/migration](./src/main/resources/db/migration) at startup when `fraud.offline-store.enabled=true`. The Postgres adapter only reads and writes application data; it does not create or alter tables.

Feast is responsible for defining the feature views, querying those Postgres tables for point-in-time correct training datasets, and materializing features to the online store when needed. In this Postgres demo path, Feast is not responsible for inserting the offline rows.

The Flyway migrations define:

- `fraud_transactions`: historical transaction facts.
- `fraud_labels`: confirmed fraud or legitimate outcomes.
- `customer_transaction_stats`: historical customer aggregate features.
- `merchant_risk_features`: historical merchant features.
- `fraud_prediction_logs`: optional model score and decision audit records.
- `fraud_transaction_processing`: processing ledger for started, failed, prediction-recorded, and decision-published states.
- `fraud_label_events`: immutable label annotation history.
- `fraud_training_examples`: transaction and latest-label view used as the Feast entity dataframe.

### Annotate Confirmed Fraud

Confirmed labels can be attached to historical transactions after review, chargeback, or dispute systems produce an outcome. The transaction must already exist in `fraud_transactions`.

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

The service upserts the current label in `fraud_labels` and appends every annotation to `fraud_label_events`. Unknown transaction ids return `404`.

For a standalone local retraining demo, start Postgres and run the application once with the offline store enabled so Flyway applies migrations:

```bash
docker run --rm --name fraud-postgres \
  -p 5432:5432 \
  -e POSTGRES_DB=fraud_features \
  -e POSTGRES_USER=feast \
  -e POSTGRES_PASSWORD=feast \
  postgres:16-alpine

python -m venv .venv-training
source .venv-training/bin/activate
pip install -r training/requirements.txt
```

In another terminal, run Quarkus with the offline store enabled:

```bash
FRAUD_OFFLINE_STORE_ENABLED=true \
FRAUD_OFFLINE_STORE_JDBC_URL=jdbc:postgresql://localhost:5432/fraud_features \
FRAUD_OFFLINE_STORE_USER=feast \
FRAUD_OFFLINE_STORE_PASSWORD=feast \
FRAUD_MODEL_MODEL_A_KSERVE_URL=http://localhost:18081/v1/models/fraud-model-a:predict \
FRAUD_MODEL_MODEL_B_KSERVE_URL=http://localhost:18081/v1/models/fraud-model-b:predict \
mvn quarkus:dev
```

Then load sample labeled data:

```bash
TRAINING_DATABASE_URL=postgresql://feast:feast@localhost:5432/fraud_features \
python training/scripts/load_sample_data.py
```

Build a point-in-time correct Feast training dataset:

```bash
FEAST_OFFLINE_STORE_TYPE=postgres \
FEAST_POSTGRES_HOST=localhost \
FEAST_POSTGRES_PORT=5432 \
FEAST_POSTGRES_DATABASE=fraud_features \
FEAST_POSTGRES_USER=feast \
FEAST_POSTGRES_PASSWORD=feast \
FEAST_POSTGRES_SSLMODE=disable \
python feast/scripts/render_feature_store.py

FEAST_OFFLINE_STORE_TYPE=postgres \
python training/scripts/build_training_dataset.py \
  --model MODEL_B \
  --min-label-age-days 14 \
  --output training/output/model_b_training.parquet
```

Train and classify expected outcomes for the training set. The training script sorts by `event_timestamp` and uses the oldest 70% of rows for training and the newest 30% for testing so the demo evaluation follows the same time-aware assumption as Feast historical retrieval:

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

The classified dataset adds `fraud_score`, `expected_decision`, and `expected_is_fraud`. Compare those columns with `is_fraud` to measure precision, recall, PR-AUC, false positives, and false negatives before promoting a new model to KServe.

## MLflow, RustFS, and Model Promotion Demo

The Helm demo can also run the full retraining loop:

```text
transactions -> Postgres offline store + Feast feature rows
labels -> fraud_labels
training job -> Feast historical retrieval -> MLflow run
MLflow artifacts -> RustFS S3-compatible bucket
promotion script -> OCI predictor image -> KServe InferenceService
new transactions -> Java transformer -> trained predictor -> prediction logs
evaluation job -> fraud_prediction_logs joined with fraud_labels
```

The automated demo starts with a clean namespace by default, so historical data is not kept between runs:

```bash
scripts/run_e2e_mlflow_demo.sh
```

For kind or minikube, load local images into the cluster:

```bash
KIND_CLUSTER=kind scripts/run_e2e_mlflow_demo.sh

MINIKUBE_PROFILE=minikube scripts/run_e2e_mlflow_demo.sh
```

The script builds the Java transaction service, Java KServe transformer, Feast images, MLflow image, training image, and the promoted model-serving image. It creates labeled transactions through the public API, trains from Feast/Postgres offline data, registers the model in MLflow, stores artifacts in RustFS, deploys the trained model as a KServe predictor image, runs more labeled transactions, and prints deployed-model metrics.

### Inspect MLflow Runs and Artifacts

After the full demo or an in-cluster training job has run, port-forward the MLflow service:

```bash
kubectl port-forward -n fraud-demo svc/fraud-demo-fraud-inference-demo-mlflow 5000:5000
```

Open [http://localhost:5000](http://localhost:5000). The MLflow UI shows the training runs, parameters, metrics, artifacts, and registered model versions. For the default demo, inspect the `fraud-MODEL_B` registered model and the latest run under the default experiment. Each run logs the Feast historical training dataset, validation rows, confusion matrix, metrics, and the pyfunc model artifact used by the promotion script.

The model artifacts are stored in the single-node RustFS bucket configured as the MLflow artifact root:

```text
s3://mlflow-artifacts
```

To inspect the bucket through the RustFS console:

```bash
kubectl port-forward -n fraud-demo svc/fraud-demo-fraud-inference-demo-rustfs 9001:9001
```

Open [http://localhost:9001](http://localhost:9001). With the default chart values, sign in with:

```text
username: mlflow
password: mlflow-secret
```

If the chart values were changed, read the active credentials from the RustFS secret:

```bash
kubectl get secret -n fraud-demo fraud-demo-fraud-inference-demo-rustfs-credentials \
  -o jsonpath='{.data.accessKey}' | base64 --decode
echo
kubectl get secret -n fraud-demo fraud-demo-fraud-inference-demo-rustfs-credentials \
  -o jsonpath='{.data.secretKey}' | base64 --decode
echo
```

You can also inspect the artifacts with an S3-compatible CLI:

```bash
kubectl port-forward -n fraud-demo svc/fraud-demo-fraud-inference-demo-rustfs 9000:9000

AWS_ACCESS_KEY_ID=mlflow \
AWS_SECRET_ACCESS_KEY=mlflow-secret \
AWS_DEFAULT_REGION=us-east-1 \
aws --endpoint-url http://localhost:9000 s3 ls s3://mlflow-artifacts --recursive
```

To run only the promotion step after a training job has registered a model:

```bash
MODEL_VERSION=1 \
scripts/simulate_model_ci.sh \
  --model MODEL_B \
  --registered-model-name fraud-MODEL_B \
  --model-version 1 \
  --image-repository fraud-model-b
```

To check whether retraining is needed due to feature drift:

```bash
TRAINING_DATABASE_URL=postgresql://feast:feast@localhost:5432/fraud_features \
python training/scripts/check_data_drift.py
```

## Verification

Run unit tests:

```bash
mvn test

cd kserve/java-transformer
mvn test
cd ../..

cd kserve/transformer
python -m unittest test_transformer.py
cd ../..

helm template fraud-demo ./charts/fraud-inference-demo --namespace fraud-demo
```
