import argparse
import json

import numpy as np
import pandas as pd

from db import connect


NUMERIC_FEATURES = [
    "customer_transaction_count_1h",
    "customer_transaction_count_24h",
    "customer_total_amount_24h",
    "customer_avg_amount_7d",
    "customer_max_amount_7d",
    "customer_distinct_merchants_24h",
    "customer_cross_border_count_7d",
    "current_merchant_visit_count_30d",
    "current_merchant_visit_share_30d",
    "merchant_risk_score",
]


def psi(expected: pd.Series, actual: pd.Series, buckets: int = 10) -> float:
    expected = expected.dropna().astype(float)
    actual = actual.dropna().astype(float)
    if expected.empty or actual.empty:
        return 0.0
    quantiles = np.unique(np.quantile(expected, np.linspace(0, 1, buckets + 1)))
    if len(quantiles) < 3:
        return 0.0
    expected_counts, _ = np.histogram(expected, bins=quantiles)
    actual_counts, _ = np.histogram(actual, bins=quantiles)
    expected_pct = np.maximum(expected_counts / max(expected_counts.sum(), 1), 0.0001)
    actual_pct = np.maximum(actual_counts / max(actual_counts.sum(), 1), 0.0001)
    return float(np.sum((actual_pct - expected_pct) * np.log(actual_pct / expected_pct)))


def load_feature_window(days_back_start: int, days_back_end: int) -> pd.DataFrame:
    query = """
        SELECT
            c.customer_transaction_count_1h,
            c.customer_transaction_count_24h,
            c.customer_total_amount_24h,
            c.customer_avg_amount_7d,
            c.customer_max_amount_7d,
            c.customer_distinct_merchants_24h,
            c.customer_cross_border_count_7d,
            c.current_merchant_visit_count_30d,
            c.current_merchant_visit_share_30d,
            m.merchant_risk_score
        FROM customer_transaction_stats c
        LEFT JOIN merchant_risk_features m
          ON m.event_timestamp = c.event_timestamp
        WHERE c.event_timestamp >= now() - (%s || ' days')::interval
          AND c.event_timestamp < now() - (%s || ' days')::interval
    """
    with connect() as conn:
        rows = conn.execute(query, (days_back_start, days_back_end)).fetchall()
    return pd.DataFrame(rows, columns=NUMERIC_FEATURES)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline-start-days-ago", type=int, default=60)
    parser.add_argument("--baseline-end-days-ago", type=int, default=14)
    parser.add_argument("--current-start-days-ago", type=int, default=14)
    parser.add_argument("--current-end-days-ago", type=int, default=0)
    parser.add_argument("--psi-threshold", type=float, default=0.20)
    args = parser.parse_args()

    baseline = load_feature_window(args.baseline_start_days_ago, args.baseline_end_days_ago)
    current = load_feature_window(args.current_start_days_ago, args.current_end_days_ago)
    if baseline.empty or current.empty:
        raise ValueError("Need both baseline and current feature rows to compute drift")

    feature_psi = {feature: psi(baseline[feature], current[feature]) for feature in NUMERIC_FEATURES}
    drifted = {feature: value for feature, value in feature_psi.items() if value >= args.psi_threshold}
    result = {
        "baseline_rows": len(baseline),
        "current_rows": len(current),
        "psi_threshold": args.psi_threshold,
        "feature_psi": feature_psi,
        "drifted_features": drifted,
        "retrain_recommended": bool(drifted),
    }
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
