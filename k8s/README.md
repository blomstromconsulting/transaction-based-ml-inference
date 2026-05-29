# Kubernetes Manifests

Apply order for the demo:

```bash
kubectl apply -f k8s/redis.yaml
kubectl apply -f k8s/postgres.yaml
kubectl apply -f k8s/model-feature-config.yaml
kubectl apply -f k8s/feast-feature-server.yaml
kubectl apply -f k8s/kserve-model-a.yaml
kubectl apply -f k8s/kserve-model-b.yaml
kubectl apply -f k8s/transaction-events.yaml
```

The KServe `InferenceService` resources attach the same transformer image to each model. The transformer reads the target model and Feature Service mapping, calls Feast for online features, then forwards the enriched payload to the model predictor.
