import argparse
from pathlib import Path

import joblib
import pandas as pd
from sklearn.compose import ColumnTransformer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import average_precision_score, classification_report, roc_auc_score
from sklearn.model_selection import train_test_split
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler


MODEL_COLUMNS = {
    "MODEL_A": {
        "numeric": [
            "transaction_amount",
            "customer_transaction_count_1h",
            "customer_transaction_count_24h",
            "customer_total_amount_24h",
            "customer_avg_amount_7d",
        ],
        "categorical": ["transaction_country", "merchant_category"],
    },
    "MODEL_B": {
        "numeric": [
            "transaction_amount",
            "customer_transaction_count_1h",
            "customer_transaction_count_24h",
            "customer_total_amount_24h",
            "customer_avg_amount_7d",
            "customer_max_amount_7d",
            "customer_distinct_merchants_24h",
            "customer_cross_border_count_7d",
            "merchant_risk_score",
        ],
        "categorical": ["transaction_country", "merchant_category"],
    },
}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", choices=MODEL_COLUMNS, default="MODEL_B")
    parser.add_argument("--input", default="training/output/training_dataset.parquet")
    parser.add_argument("--output", default="training/output/model_b.joblib")
    args = parser.parse_args()

    df = pd.read_parquet(args.input).sort_values("event_timestamp")
    columns = MODEL_COLUMNS[args.model]
    X = df[columns["numeric"] + columns["categorical"]]
    y = df["is_fraud"]

    stratify = y if y.nunique() > 1 and y.value_counts().min() > 1 else None
    X_train, X_test, y_train, y_test = train_test_split(
        X,
        y,
        test_size=0.30,
        random_state=42,
        shuffle=True,
        stratify=stratify,
    )

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
    predictions = (scores >= 0.85).astype(int)
    if y_test.nunique() > 1:
        print(f"PR-AUC: {average_precision_score(y_test, scores):.4f}")
        print(f"ROC-AUC: {roc_auc_score(y_test, scores):.4f}")
    print(classification_report(y_test, predictions, zero_division=0))

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    joblib.dump({"model": args.model, "pipeline": pipeline, "columns": columns}, output)
    print(f"Wrote model artifact to {output}")


if __name__ == "__main__":
    main()
