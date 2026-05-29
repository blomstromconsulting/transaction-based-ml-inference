import argparse
from pathlib import Path

import joblib
import pandas as pd
from sklearn.metrics import average_precision_score, confusion_matrix, precision_recall_fscore_support, roc_auc_score


def decision(score: float) -> str:
    if score >= 0.85:
        return "DECLINE"
    if score >= 0.60:
        return "REVIEW"
    return "APPROVE"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-artifact", default="training/output/model_b.joblib")
    parser.add_argument("--input", default="training/output/training_dataset.parquet")
    parser.add_argument("--output", default="training/output/classified_dataset.parquet")
    args = parser.parse_args()

    artifact = joblib.load(args.model_artifact)
    df = pd.read_parquet(args.input)
    columns = artifact["columns"]["numeric"] + artifact["columns"]["categorical"]
    scores = artifact["pipeline"].predict_proba(df[columns])[:, 1]
    df["fraud_score"] = scores
    df["expected_decision"] = [decision(score) for score in scores]
    df["expected_is_fraud"] = (df["fraud_score"] >= 0.85).astype(int)

    y = df["is_fraud"]
    y_pred = df["expected_is_fraud"]
    precision, recall, f1, _ = precision_recall_fscore_support(y, y_pred, average="binary", zero_division=0)
    print(f"precision={precision:.4f} recall={recall:.4f} f1={f1:.4f}")
    if y.nunique() > 1:
        print(f"PR-AUC={average_precision_score(y, scores):.4f}")
        print(f"ROC-AUC={roc_auc_score(y, scores):.4f}")
    print("confusion_matrix=")
    print(confusion_matrix(y, y_pred))

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    df.to_parquet(output, index=False)
    print(f"Wrote classified rows to {output}")


if __name__ == "__main__":
    main()
