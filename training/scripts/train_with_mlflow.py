import argparse
import json
import os
import runpy
import subprocess
import tempfile
from pathlib import Path

import joblib
import mlflow
import mlflow.pyfunc
import pandas as pd
from feast import FeatureStore
from mlflow.tracking import MlflowClient
from sklearn.compose import ColumnTransformer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (
    average_precision_score,
    confusion_matrix,
    precision_recall_fscore_support,
    roc_auc_score,
)
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler

from fraud_pyfunc import FraudProbabilityModel
from model_catalog import load_model_catalog


DEFAULT_THRESHOLD = 0.85


def prepare_feature_store(repo_path: str) -> FeatureStore:
    repo = Path(repo_path).resolve()
    os.environ["FEAST_REPO_PATH"] = str(repo)
    render_script = repo / "scripts" / "render_feature_store.py"
    if render_script.exists():
        runpy.run_path(str(render_script), run_name="__main__")
    subprocess.run(["feast", "apply"], cwd=repo, check=True)
    return FeatureStore(repo_path=str(repo))


def build_dataset(args: argparse.Namespace, catalog: dict) -> pd.DataFrame:
    os.environ.setdefault("FEAST_OFFLINE_STORE_TYPE", "postgres")
    store = prepare_feature_store(args.repo)
    label_maturity_filter = ""
    if args.min_label_age_days > 0:
        label_maturity_filter = f"""
        WHERE label_timestamp <= now() - interval '{args.min_label_age_days} days'
        """
    entity_query = f"""
        SELECT
            transaction_id,
            customer_id,
            merchant_id,
            event_timestamp,
            transaction_amount,
            transaction_country,
            merchant_category,
            is_fraud,
            label_timestamp,
            label_source,
            label_confidence
        FROM fraud_training_examples
        {label_maturity_filter}
    """
    return store.get_historical_features(
        entity_df=entity_query,
        features=catalog[args.model]["feature_refs"],
    ).to_df()


def train_pipeline(df: pd.DataFrame, model_config: dict) -> tuple[Pipeline, dict, dict]:
    df = df.sort_values("event_timestamp")
    columns = {
        "numeric": model_config["numeric_columns"],
        "categorical": model_config["categorical_columns"],
    }
    X = df[columns["numeric"] + columns["categorical"]]
    y = df["is_fraud"]

    if len(df) < 4:
        raise ValueError("Training requires at least four labeled rows for the demo train/test split")
    if y.nunique() < 2:
        raise ValueError("Training requires both fraud and legitimate labels")

    split_index = max(1, min(len(df) - 1, int(len(df) * 0.70)))
    X_train, X_test = X.iloc[:split_index], X.iloc[split_index:]
    y_train, y_test = y.iloc[:split_index], y.iloc[split_index:]
    if y_train.nunique() < 2:
        raise ValueError("Training split contains only one label class; add more mixed labeled transactions")

    preprocessor = ColumnTransformer(
        transformers=[
            ("numeric", StandardScaler(), columns["numeric"]),
            ("categorical", OneHotEncoder(handle_unknown="ignore"), columns["categorical"]),
        ]
    )
    pipeline = Pipeline(
        steps=[
            ("preprocessor", preprocessor),
            ("classifier", LogisticRegression(max_iter=1000, class_weight="balanced")),
        ]
    )
    pipeline.fit(X_train, y_train)

    scores = pipeline.predict_proba(X_test)[:, 1]
    predictions = (scores >= DEFAULT_THRESHOLD).astype(int)
    precision, recall, f1, _ = precision_recall_fscore_support(
        y_test, predictions, average="binary", zero_division=0
    )
    metrics = {
        "precision": float(precision),
        "recall": float(recall),
        "f1": float(f1),
        "test_rows": float(len(y_test)),
        "train_rows": float(len(y_train)),
        "positive_labels": float(y.sum()),
        "negative_labels": float(len(y) - y.sum()),
    }
    if y_test.nunique() > 1:
        metrics["pr_auc"] = float(average_precision_score(y_test, scores))
        metrics["roc_auc"] = float(roc_auc_score(y_test, scores))

    classified = X_test.copy()
    classified["is_fraud"] = y_test.values
    classified["fraud_score"] = scores
    classified["expected_is_fraud"] = predictions
    return pipeline, columns, {"metrics": metrics, "classified": classified, "y_test": y_test, "predictions": predictions}


def latest_registered_version(client: MlflowClient, registered_model_name: str, run_id: str) -> str:
    versions = client.search_model_versions(f"name = '{registered_model_name}'")
    run_versions = [version for version in versions if version.run_id == run_id]
    if not run_versions:
        return ""
    return max(run_versions, key=lambda version: int(version.version)).version


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="MODEL_B")
    parser.add_argument("--registered-model-name", default="fraud-MODEL_B")
    parser.add_argument("--model-catalog", default="training/model_catalog.json")
    parser.add_argument("--repo", default="feast")
    parser.add_argument("--experiment-name", default="fraud-retraining")
    parser.add_argument("--min-label-age-days", type=int, default=0)
    parser.add_argument("--output-dir", default="training/output/mlflow")
    args = parser.parse_args()

    catalog = load_model_catalog(args.model_catalog)
    if args.model not in catalog:
        raise ValueError(f"Unknown model {args.model}; available models: {', '.join(sorted(catalog))}")

    Path(args.output_dir).mkdir(parents=True, exist_ok=True)
    mlflow.set_experiment(args.experiment_name)

    dataset = build_dataset(args, catalog)
    if dataset.empty:
        raise ValueError("No labeled training rows returned from Feast offline store")

    model_config = catalog[args.model]
    pipeline, columns, evaluation = train_pipeline(dataset, model_config)
    metrics = evaluation["metrics"]

    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        dataset_path = tmp_path / "training_dataset.parquet"
        classified_path = tmp_path / "classified_validation_rows.parquet"
        bundle_path = tmp_path / "model_bundle.joblib"
        columns_path = tmp_path / "columns.json"
        metrics_path = tmp_path / "metrics.json"
        confusion_path = tmp_path / "confusion_matrix.json"

        dataset.to_parquet(dataset_path, index=False)
        evaluation["classified"].to_parquet(classified_path, index=False)
        joblib.dump(
            {
                "model": args.model,
                "model_version": model_config["model_version"],
                "pipeline": pipeline,
                "columns": columns,
                "threshold": DEFAULT_THRESHOLD,
            },
            bundle_path,
        )
        columns_path.write_text(json.dumps(columns, indent=2), encoding="utf-8")
        metrics_path.write_text(json.dumps(metrics, indent=2), encoding="utf-8")
        confusion = confusion_matrix(evaluation["y_test"], evaluation["predictions"]).tolist()
        confusion_path.write_text(json.dumps(confusion, indent=2), encoding="utf-8")

        input_example = dataset[columns["numeric"] + columns["categorical"]].head(1)
        with mlflow.start_run(run_name=f"{args.model}-training") as run:
            mlflow.log_params(
                {
                    "model": args.model,
                    "catalog_model_version": model_config["model_version"],
                    "feature_service": "fraud_model_b_feature_service" if args.model == "MODEL_B" else "fraud_model_a_feature_service",
                    "threshold": DEFAULT_THRESHOLD,
                    "min_label_age_days": args.min_label_age_days,
                    "row_count": len(dataset),
                }
            )
            mlflow.log_dict({"feature_refs": model_config["feature_refs"]}, "feature_refs.json")
            mlflow.log_metrics(metrics)
            mlflow.log_artifact(dataset_path, artifact_path="data")
            mlflow.log_artifact(classified_path, artifact_path="evaluation")
            mlflow.log_artifact(columns_path, artifact_path="model")
            mlflow.log_artifact(metrics_path, artifact_path="evaluation")
            mlflow.log_artifact(confusion_path, artifact_path="evaluation")
            mlflow.pyfunc.log_model(
                artifact_path="model",
                python_model=FraudProbabilityModel(),
                artifacts={"model_bundle": str(bundle_path)},
                code_paths=["training/scripts/fraud_pyfunc.py"],
                input_example=input_example,
                registered_model_name=args.registered_model_name,
            )
            run_id = run.info.run_id

    client = MlflowClient()
    model_version = latest_registered_version(client, args.registered_model_name, run_id)
    print(f"MLFLOW_RUN_ID={run_id}")
    if model_version:
        print(f"MLFLOW_REGISTERED_MODEL={args.registered_model_name}")
        print(f"MLFLOW_MODEL_VERSION={model_version}")
    print(json.dumps(metrics, indent=2))


if __name__ == "__main__":
    main()
