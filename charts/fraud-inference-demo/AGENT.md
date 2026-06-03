# Agent Guide

This directory contains the Helm chart for the fraud inference demo.

## Chart Conventions

- Keep values configurable through `values.yaml`; avoid hard-coded image names,
  model names, ports, or credentials in templates.
- When adding an application configuration value, update:
  - `values.yaml`
  - the relevant template under `templates/`
  - `README.md` in this directory
  - the top-level `README.md` if it affects deployment behavior
- For secrets, prefer `existingSecret` options and document the expected key
  names. Do not add production passwords as defaults.
- The Postgres schema is created by Flyway in the transaction-events image, not
  by Helm SQL hooks or init scripts.

## Feature and Model Mapping

- Model-to-Feature-Service mapping is projected into both Quarkus and KServe
  transformer configuration.
- Keep mock predictor `features_used` aligned with the feature services used by
  the demo models.
- If feature columns are added by a new Flyway migration, update any Postgres
  readiness checks that depend on the schema being ready.

## Verification

Render the chart after template changes:

```bash
helm template fraud-demo ./charts/fraud-inference-demo
helm template fraud-demo ./charts/fraud-inference-demo \
  -f ./charts/fraud-inference-demo/values-real-feast-kserve-demo.yaml
```

For deployment validation:

```bash
helm upgrade --install fraud-demo ./charts/fraud-inference-demo \
  --namespace fraud-demo \
  --create-namespace \
  -f ./charts/fraud-inference-demo/values-real-feast-kserve-demo.yaml \
  --wait \
  --timeout 10m

kubectl get pods,deploy,inferenceservices -n fraud-demo
helm test fraud-demo -n fraud-demo --timeout 2m
```
