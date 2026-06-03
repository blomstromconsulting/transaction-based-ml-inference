# Agent Guide

This repository is a Quarkus-based transaction fraud inference demo with Feast,
Redis, Postgres, KServe, Helm, and training utilities. Use this file as the
starting context for future Codex/agent work.

## Architecture Boundaries

- Keep the Quarkus application hexagonal.
- Domain models and ports live under `src/main/java/com/example/fraud/domain`.
- Application orchestration lives under `src/main/java/com/example/fraud/application`.
- Adapters live under `src/main/java/com/example/fraud/adapter`.
- Infrastructure config lives under `src/main/java/com/example/fraud/infrastructure`.
- Do not let adapters leak Redis, JDBC, Feast, KServe, or HTTP details into
  domain models or ports.
- Prefer adding or extending ports before coupling application services to a
  concrete adapter.

## Online and Offline Feature Flow

- Redis application keys under `fraud:feature-state:*` are functional online
  state used to derive feature snapshots before inference.
- Feast Redis online-store keys are owned by Feast and should be written through
  Feast materialization or a Feast-compatible writer.
- Transformers read model features through Feast Feature Services, not directly
  from the application Redis feature-state keys.
- Postgres is the offline sink for historical transactions, historical feature
  rows, labels, processing state, and prediction audit records.
- Flyway owns Postgres schema changes. Add schema changes under
  `src/main/resources/db/migration` using monotonic `V*__*.sql` migrations.
- Keep the online feature contract, Feast feature views/services, training
  catalog, transformers, and documentation in sync when adding features.

## Model Feature Contracts

Model feature definitions are intentionally duplicated across runtime and
training surfaces. Update all relevant locations when changing features:

- `src/main/java/com/example/fraud/infrastructure/config/ModelFeatureServiceMapping.java`
- `src/main/resources/application.properties`
- `feast/feature_views.py`
- `feast/feature_services.py`
- `feast/writer/app.py`
- `feast/scripts/render_feature_store.py`
- `training/model_catalog.json`
- `training/scripts/load_sample_data.py`
- `kserve/transformer`
- `kserve/java-transformer`
- `charts/fraud-inference-demo`
- `README.md`

Model B currently includes merchant visit features derived from the Redis
merchant visit window. Changing `fraud.features.merchant-visit-window-days`
changes feature semantics and should be treated as a model-version concern.

## Verification

Run the smallest useful set for the files changed. For broad feature-contract
or deployment changes, use:

```bash
mvn test -q
(cd kserve/java-transformer && mvn test -q)
python kserve/transformer/test_transformer.py
python -m compileall -q feast training kserve/transformer
helm template fraud-demo ./charts/fraud-inference-demo \
  -f ./charts/fraud-inference-demo/values-real-feast-kserve-demo.yaml
```

For full local Kubernetes verification, build local images and deploy the chart:

```bash
docker build -f src/main/docker/Dockerfile.jvm -t transaction-events:local .
docker build -f feast/Dockerfile -t fraud-feast-repo:local .
docker build -f feast/writer/Dockerfile -t fraud-feast-writer:local .
docker build -f kserve/transformer/Dockerfile -t fraud-feature-transformer:local .
docker build -f kserve/java-transformer/Dockerfile -t fraud-java-transformer:local .

helm upgrade --install fraud-demo ./charts/fraud-inference-demo \
  --namespace fraud-demo \
  --create-namespace \
  -f ./charts/fraud-inference-demo/values-real-feast-kserve-demo.yaml \
  --wait \
  --timeout 10m

helm test fraud-demo -n fraud-demo --timeout 2m
```

## Documentation Expectations

- Keep `README.md` as the top-level architecture and operations guide.
- Keep `charts/fraud-inference-demo/README.md` aligned with Helm deployment
  behavior.
- Update data-flow diagrams when orchestration order or storage ownership
  changes.
- Include concrete build, deploy, port-forward, and inspection commands for
  operational changes.

## Git and Generated Files

- Do not commit build output under `target`, `__pycache__`, or generated local
  artifacts unless the repository already tracks them for a specific reason.
- Keep commits scoped to the requested change.
- Before committing, check `git status --short` and ensure unrelated user
  changes are not included.
