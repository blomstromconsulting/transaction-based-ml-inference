# Agent Guide

This directory contains the Feast repository and the small Feast writer service.

## Feast Conventions

- `feature_views.py` defines reusable feature views.
- `feature_services.py` composes model-specific online feature sets.
- `scripts/render_feature_store.py` renders local Feast configuration and
  bootstrap data used by containerized demos.
- `writer/app.py` accepts feature rows from Quarkus and writes them through the
  Feast SDK into the Redis online store format.
- Do not write Feast online-store Redis keys directly from Quarkus or shell
  scripts; use Feast materialization or the Feast-compatible writer.

## Feature Changes

When adding or renaming a feature, update the corresponding Java feature row,
Postgres migration/sink, Feast view, Feast service, writer payload, training
catalog, transformer contract, and README examples together.

## Verification

```bash
python -m compileall -q feast
docker build -f feast/Dockerfile -t fraud-feast-repo:local .
docker build -f feast/writer/Dockerfile -t fraud-feast-writer:local .
```

For chart-driven checks, also render or deploy
`charts/fraud-inference-demo/values-real-feast-kserve-demo.yaml`.
