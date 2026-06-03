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

Prerequisites:

- a Kubernetes cluster with KServe CRDs installed before applying the `kserve-model-*.yaml` manifests
- images in the manifests replaced with images that are reachable from the cluster
- Postgres credentials moved to a Secret for non-demo deployments

The KServe `InferenceService` resources attach the same transformer image to each model. The transformer reads the target model and Feature Service mapping, calls Feast for online features, then forwards the enriched payload to the model predictor.

Model endpoint, Feature Service, and model version values are centralized in `k8s/model-feature-config.yaml` and projected into the transaction service.
