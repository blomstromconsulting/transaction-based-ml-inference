# fraud-inference-demo Helm Chart

This chart deploys the transaction-based fraud inference demo to Kubernetes:

- Quarkus `transaction-events` service
- Redis online feature/statistics store
- Optional Postgres Feast offline store for retraining
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
```

Deploy with the example values file:

```bash
helm upgrade --install fraud-demo ./charts/fraud-inference-demo \
  --namespace fraud-demo \
  --create-namespace \
  -f ./charts/fraud-inference-demo/values-real-feast-kserve-demo.yaml
```

This mode deploys a real Feast feature server, Feast feature writer, and real KServe `InferenceService` resources with the Feast transformer. The example still uses tiny Python predictor containers for Model A and Model B so the chart is runnable without external model images.

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

The Python transformer uses the KServe and Feast Python SDKs. The Java transformer is a Quarkus proxy transformer that calls Feast feature server REST, validates required online feature values, calls the predictor URL, and returns the same fraud response shape.

The same values file enables Postgres and configures Feast with:

```yaml
postgres:
  enabled: true

feast:
  offlineStore:
    type: postgres
```

Redis remains the Feast online store used by the transformer. Postgres is the Feast offline store used by retraining jobs for historical feature retrieval. When Postgres is enabled, the Quarkus service writes live transaction facts, historical feature rows, and prediction logs through its offline data sink. Feast reads those Postgres tables as offline data sources; labels still need to be supplied later from fraud outcomes.

For production-like use, replace `models.MODEL_A.predictorImage`, `models.MODEL_B.predictorImage`, `predictorCommand`, and `predictorArgs` with real model-serving images.

## Feast Online Store Note

Feast Redis online store entries must be written through Feast materialization or a Feast-compatible writer. This chart can deploy `featureWriter.enabled=true`, which exposes a small HTTP service that uses the Feast SDK to write rows into Feast's Redis online store format.

The Quarkus Redis adapter still writes easy-to-read aggregate keys such as `fraud:customer:{customer_id}:stats`; those keys are useful for debugging but are not the source read by the transformer. The transformer reads through Feast Feature Services.

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
