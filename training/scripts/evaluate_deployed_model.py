import argparse
import json

from sklearn.metrics import average_precision_score, confusion_matrix, precision_recall_fscore_support, roc_auc_score

from db import connect


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="MODEL_B")
    parser.add_argument("--model-version", required=True)
    parser.add_argument("--threshold", type=float, default=0.85)
    args = parser.parse_args()

    with connect() as conn:
        rows = conn.execute(
            """
            SELECT
                p.transaction_id,
                p.fraud_score,
                l.is_fraud
            FROM fraud_prediction_logs p
            JOIN fraud_labels l ON l.transaction_id = p.transaction_id
            WHERE p.model = %s
              AND p.model_version = %s
            ORDER BY p.inference_timestamp
            """,
            (args.model, args.model_version),
        ).fetchall()

    if not rows:
        raise ValueError(f"No labeled prediction rows found for {args.model} version {args.model_version}")

    scores = [float(row[1]) for row in rows]
    labels = [int(row[2]) for row in rows]
    predictions = [1 if score >= args.threshold else 0 for score in scores]
    precision, recall, f1, _ = precision_recall_fscore_support(labels, predictions, average="binary", zero_division=0)
    metrics = {
        "rows": len(rows),
        "model": args.model,
        "model_version": args.model_version,
        "threshold": args.threshold,
        "precision": float(precision),
        "recall": float(recall),
        "f1": float(f1),
        "confusion_matrix": confusion_matrix(labels, predictions).tolist(),
    }
    if len(set(labels)) > 1:
        metrics["pr_auc"] = float(average_precision_score(labels, scores))
        metrics["roc_auc"] = float(roc_auc_score(labels, scores))
    print(json.dumps(metrics, indent=2))


if __name__ == "__main__":
    main()
