import os
import argparse
from typing import Dict, List

import kserve
from feast import FeatureStore


MODEL_FEATURE_SERVICES = {
    "MODEL_A": os.getenv("MODEL_A_FEATURE_SERVICE", "fraud_model_a_feature_service"),
    "MODEL_B": os.getenv("MODEL_B_FEATURE_SERVICE", "fraud_model_b_feature_service"),
}


class FraudFeatureTransformer(kserve.Model):
    def __init__(self, name: str):
        super().__init__(name)
        self.name = name
        self.ready = True
        self.store = FeatureStore(repo_path=os.getenv("FEAST_REPO_PATH", "/mnt/feast"))

    def preprocess(self, payload: Dict, headers: Dict[str, str] = None) -> Dict:
        model_name = payload.get("model", os.getenv("TARGET_MODEL", "MODEL_A"))
        feature_service = payload.get("featureService") or MODEL_FEATURE_SERVICES[model_name]
        transaction = payload["transaction"]

        entity_rows = [
            {
                "customer_id": transaction["customer_id"],
                "merchant_id": transaction.get("merchant_id"),
            }
        ]

        feature_refs = self._feature_refs(feature_service)
        online_features = self.store.get_online_features(
            features=feature_refs,
            entity_rows=entity_rows,
        ).to_dict()
        flattened_features = {name: values[0] for name, values in online_features.items()}

        model_input = self._model_input(model_name, transaction, flattened_features)
        self._last_context = {
            "model": model_name,
            "feature_service": feature_service,
            "features_used": list(model_input.keys()),
        }
        return {
            "instances": [model_input],
            "parameters": {
                "transaction_id": transaction["transaction_id"],
                **self._last_context,
            },
        }

    def postprocess(self, response: Dict, headers: Dict[str, str] = None) -> Dict:
        prediction = response.get("predictions", [{}])[0]
        score = float(prediction.get("fraud_score", prediction.get("score", 0.0)))
        decision = "DECLINE" if score >= 0.85 else "REVIEW" if score >= 0.60 else "APPROVE"
        parameters = response.get("parameters") or getattr(self, "_last_context", {})
        return {
            "fraudScore": score,
            "decision": decision,
            "featuresUsed": parameters.get("features_used", []),
            "metadata": {
                "model": parameters.get("model"),
                "feature_service": parameters.get("feature_service"),
                "source": "kserve-transformer-feast",
            },
        }

    def _model_input(self, model_name: str, transaction: Dict, features: Dict) -> Dict:
        base = {
            "transaction_amount": transaction["transaction_amount"],
            "transaction_country": transaction["transaction_country"],
            "merchant_category": transaction["merchant_category"],
            "customer_transaction_count_1h": features.get("customer_transaction_count_1h", 0),
            "customer_transaction_count_24h": features.get("customer_transaction_count_24h", 0),
            "customer_total_amount_24h": features.get("customer_total_amount_24h", 0.0),
            "customer_avg_amount_7d": features.get("customer_avg_amount_7d", 0.0),
        }
        if model_name == "MODEL_B":
            base.update(
                {
                    "customer_max_amount_7d": features.get("customer_max_amount_7d", 0.0),
                    "customer_distinct_merchants_24h": features.get("customer_distinct_merchants_24h", 0),
                    "customer_cross_border_count_7d": features.get("customer_cross_border_count_7d", 0),
                    "merchant_risk_score": features.get("merchant_risk_score", 0.0),
                }
            )
        return base

    def _feature_refs(self, feature_service: str) -> List[str]:
        service = self.store.get_feature_service(feature_service)
        return [
            f"{projection.name}:{field.name}"
            for projection in service.feature_view_projections
            for field in projection.features
        ]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--model_name", default=os.getenv("TRANSFORMER_NAME", "fraud-feature-transformer"))
    args, _ = parser.parse_known_args()

    model = FraudFeatureTransformer(args.model_name)
    kserve.ModelServer().start([model])
