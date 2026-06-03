import unittest
import sys
import types

fake_kserve = types.ModuleType("kserve")


class Model:
    def __init__(self, name):
        self.name = name


fake_kserve.Model = Model
fake_kserve.ModelServer = object
sys.modules.setdefault("kserve", fake_kserve)

fake_feast = types.ModuleType("feast")
fake_feast.FeatureStore = object
sys.modules.setdefault("feast", fake_feast)

from transformer import FeatureValidationError, FraudFeatureTransformer


class Field:
    def __init__(self, name):
        self.name = name


class Projection:
    def __init__(self, name, features):
        self.name = name
        self.features = [Field(feature) for feature in features]


class FeatureService:
    def __init__(self, projections):
        self.feature_view_projections = projections


class OnlineFeatures:
    def __init__(self, values):
        self.values = values

    def to_dict(self):
        return self.values


class FakeStore:
    def __init__(self, online_features):
        self.online_features = online_features

    def get_feature_service(self, feature_service):
        return FeatureService(
            [
                Projection(
                    "customer_transaction_stats_view",
                    [
                        "customer_transaction_count_1h",
                        "customer_transaction_count_24h",
                        "customer_total_amount_24h",
                        "customer_avg_amount_7d",
                        "customer_max_amount_7d",
                        "customer_distinct_merchants_24h",
                        "customer_cross_border_count_7d",
                        "current_merchant_visit_count_30d",
                        "current_merchant_visit_share_30d",
                        "current_merchant_rank_30d",
                        "is_current_merchant_top_visited_30d",
                        "days_since_first_seen_current_merchant",
                        "days_since_last_seen_current_merchant",
                        "customer_distinct_merchants_30d",
                        "is_new_merchant_for_customer",
                        "top_visited_merchant_id_30d",
                    ],
                ),
                Projection("merchant_risk_view", ["merchant_risk_score"]),
            ]
        )

    def get_online_features(self, features, entity_rows):
        return OnlineFeatures(self.online_features)


def transaction():
    return {
        "transaction_id": "tx-10001",
        "customer_id": "cust-123",
        "card_id": "card-456",
        "merchant_id": "merchant-789",
        "merchant_category": "electronics",
        "transaction_amount": 1299.99,
        "currency": "EUR",
        "transaction_country": "SE",
        "timestamp": "2026-05-29T12:00:00Z",
    }


def complete_online_features():
    return {
        "customer_transaction_count_1h": [1],
        "customer_transaction_count_24h": [3],
        "customer_total_amount_24h": [1499.99],
        "customer_avg_amount_7d": [100.25],
        "customer_max_amount_7d": [1299.99],
        "customer_distinct_merchants_24h": [2],
        "customer_cross_border_count_7d": [1],
        "current_merchant_visit_count_30d": [0],
        "current_merchant_visit_share_30d": [0.0],
        "current_merchant_rank_30d": [0],
        "is_current_merchant_top_visited_30d": [0],
        "days_since_first_seen_current_merchant": [-1.0],
        "days_since_last_seen_current_merchant": [-1.0],
        "customer_distinct_merchants_30d": [0],
        "is_new_merchant_for_customer": [1],
        "top_visited_merchant_id_30d": [""],
        "merchant_risk_score": [0.72],
    }


class FraudFeatureTransformerTest(unittest.TestCase):
    def test_preprocess_fails_when_required_online_feature_is_missing(self):
        features = complete_online_features()
        del features["merchant_risk_score"]
        transformer = FraudFeatureTransformer("fraud-feature-transformer", store=FakeStore(features))

        with self.assertRaisesRegex(FeatureValidationError, "merchant_risk_score"):
            transformer.preprocess(
                {
                    "model": "MODEL_B",
                    "featureService": "fraud_model_b_feature_service",
                    "transaction": transaction(),
                }
            )

    def test_preprocess_fails_when_required_online_feature_is_null(self):
        features = complete_online_features()
        features["customer_total_amount_24h"] = [None]
        transformer = FraudFeatureTransformer("fraud-feature-transformer", store=FakeStore(features))

        with self.assertRaisesRegex(FeatureValidationError, "customer_total_amount_24h"):
            transformer.preprocess(
                {
                    "model": "MODEL_B",
                    "featureService": "fraud_model_b_feature_service",
                    "transaction": transaction(),
                }
            )

    def test_preprocess_builds_model_input_when_all_required_features_exist(self):
        transformer = FraudFeatureTransformer(
            "fraud-feature-transformer",
            store=FakeStore(complete_online_features()),
        )

        result = transformer.preprocess(
            {
                "model": "MODEL_B",
                "featureService": "fraud_model_b_feature_service",
                "transaction": transaction(),
            }
        )

        instance = result["instances"][0]
        self.assertEqual(0.72, instance["merchant_risk_score"])
        self.assertEqual(1299.99, instance["transaction_amount"])
        self.assertEqual("fraud_model_b_feature_service", result["parameters"]["feature_service"])


if __name__ == "__main__":
    unittest.main()
