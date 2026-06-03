# Agent Guide

This directory contains offline training utilities and model feature metadata.

## Training Data Contract

- `model_catalog.json` is the source for model training columns and feature
  references used by training scripts.
- Training examples should be point-in-time correct and built from historical
  Postgres/Feast data, not from live Redis state.
- Fraud labels are delayed outcomes from review, chargeback, dispute, or case
  systems. Do not infer labels from model decisions.
- Keep categorical and numeric feature lists aligned with transformer inputs and
  Feast Feature Services.

## Schema and Sample Data

- If a feature is added to `customer_transaction_stats` or
  `merchant_risk_features`, update sample data loaders and README examples.
- Do not create application tables in training scripts. Flyway migrations under
  `src/main/resources/db/migration` own schema creation.

## Verification

```bash
python -m compileall -q training
```

For feature-contract changes, also run the root Java tests and transformer
tests from the repository root.
