import os
import argparse
from typing import Dict, List

import kserve
from feast import FeatureStore


MODEL_FEATURE_SERVICES = {
    "MODEL_A": os.getenv("MODEL_A_FEATURE_SERVICE", "fraud_model_a_feature_service"),
    "MODEL_B": os.getenv("MODEL_B_FEATURE_SERVICE", "fraud_model_b_feature_service"),
}

STRICT_FEATURE_VALIDATION = os.getenv("STRICT_FEATURE_VALIDATION", "true").lower() in {"1", "true", "yes"}

REQUIRED_TRANSACTION_FIELDS = {
    "transaction_id",
    "customer_id",
    "merchant_id",
    "merchant_category",
    "transaction_amount",
    "transaction_country",
}


class FeatureValidationError(ValueError):
    pass


class FraudFeatureTransformer(kserve.Model):
    def __init__(self, name: str, store: FeatureStore = None):
        super().__init__(name)
        self.name = name
        self.ready = True
        self.store = store or FeatureStore(repo_path=os.getenv("FEAST_REPO_PATH", "/mnt/feast"))

    def preprocess(self, payload: Dict, headers: Dict[str, str] = None) -> Dict:
        model_name = payload.get("model", os.getenv("TARGET_MODEL", "MODEL_A"))
        feature_service = payload.get("featureService") or MODEL_FEATURE_SERVICES[model_name]
        transaction = payload["transaction"]
        self._validate_transaction(transaction)

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
        required_features = self._feature_names(feature_refs)
        self._validate_online_features(feature_service, transaction, required_features, flattened_features)

        model_input = self._model_input(transaction, flattened_features, required_features)
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

    def _model_input(self, transaction: Dict, features: Dict, required_features: List[str]) -> Dict:
        model_input = {
            "transaction_amount": transaction["transaction_amount"],
            "transaction_country": transaction["transaction_country"],
            "merchant_category": transaction["merchant_category"],
        }
        for feature in required_features:
            model_input[feature] = self._feature(features, feature, 0)
        return model_input

    def _feature_refs(self, feature_service: str) -> List[str]:
        service = self.store.get_feature_service(feature_service)
        return [
            f"{projection.name}:{field.name}"
            for projection in service.feature_view_projections
            for field in projection.features
        ]

    def _feature_names(self, feature_refs: List[str]) -> List[str]:
        return [feature_ref.split(":", 1)[1] for feature_ref in feature_refs]

    def _feature(self, features: Dict, name: str, default):
        if STRICT_FEATURE_VALIDATION:
            return features[name]
        return features.get(name, default)

    def _validate_transaction(self, transaction: Dict) -> None:
        if not STRICT_FEATURE_VALIDATION:
            return

        missing_fields = [
            field
            for field in sorted(REQUIRED_TRANSACTION_FIELDS)
            if field not in transaction or transaction[field] is None or transaction[field] == ""
        ]
        if missing_fields:
            raise FeatureValidationError(
                f"Missing required transaction fields for feature enrichment: {', '.join(missing_fields)}"
            )

    def _validate_online_features(
            self,
            feature_service: str,
            transaction: Dict,
            required_features: List[str],
            features: Dict) -> None:
        if not STRICT_FEATURE_VALIDATION:
            return

        missing_features = [
            feature
            for feature in required_features
            if feature not in features or features[feature] is None
        ]
        if missing_features:
            transaction_id = transaction.get("transaction_id", "<unknown>")
            customer_id = transaction.get("customer_id", "<unknown>")
            merchant_id = transaction.get("merchant_id", "<unknown>")
            raise FeatureValidationError(
                "Missing required online features from Feast: "
                f"{', '.join(sorted(missing_features))}; "
                f"feature_service={feature_service}; "
                f"transaction_id={transaction_id}; "
                f"customer_id={customer_id}; "
                f"merchant_id={merchant_id}"
            )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--model_name", default=os.getenv("TRANSFORMER_NAME", "fraud-feature-transformer"))
    args, _ = parser.parse_known_args()

    model = FraudFeatureTransformer(args.model_name)
    kserve.ModelServer().start([model])
