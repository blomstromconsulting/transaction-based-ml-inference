# Agent Guide

This directory contains KServe transformer implementations.

## Transformer Contract

- Transformers receive a transaction request, fetch model-specific online
  features from Feast, build the predictor input, call the predictor, and return
  the normalized fraud response.
- Keep Python and Java transformer behavior aligned unless a change explicitly
  targets only one implementation.
- Required feature validation should distinguish truly missing values from valid
  zero, false, or empty-string categorical values.
- Feature names must match Feast Feature Services and `training/model_catalog.json`.

## Verification

```bash
python kserve/transformer/test_transformer.py
python -m compileall -q kserve/transformer
(cd kserve/java-transformer && mvn test -q)
docker build -f kserve/transformer/Dockerfile -t fraud-feature-transformer:local .
docker build -f kserve/java-transformer/Dockerfile -t fraud-java-transformer:local .
```
