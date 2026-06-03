from datetime import datetime, timedelta, timezone
from db import connect


def main() -> None:
    now = datetime(2026, 5, 29, 12, 0, tzinfo=timezone.utc)

    transactions = [
        ("tx-train-001", "cust-123", "card-456", "merchant-789", "electronics", 1299.99, "EUR", "SE", now - timedelta(days=10), 1),
        ("tx-train-002", "cust-123", "card-456", "merchant-222", "grocery", 42.10, "EUR", "SE", now - timedelta(days=9), 0),
        ("tx-train-003", "cust-999", "card-999", "merchant-789", "electronics", 980.00, "EUR", "NO", now - timedelta(days=8), 1),
        ("tx-train-004", "cust-222", "card-222", "merchant-333", "travel", 310.00, "EUR", "DE", now - timedelta(days=7), 0),
        ("tx-train-005", "cust-123", "card-456", "merchant-444", "jewelry", 2250.00, "EUR", "US", now - timedelta(days=6), 1),
        ("tx-train-006", "cust-222", "card-222", "merchant-222", "grocery", 18.25, "EUR", "DE", now - timedelta(days=5), 0),
        ("tx-train-007", "cust-999", "card-999", "merchant-555", "fuel", 63.40, "EUR", "NO", now - timedelta(days=4), 0),
        ("tx-train-008", "cust-555", "card-555", "merchant-789", "electronics", 1499.00, "EUR", "SE", now - timedelta(days=3), 1),
    ]

    with connect() as conn:
        with conn.cursor() as cur:
            for row in transactions:
                tx = row[:-1]
                label = row[-1]
                cur.execute(
                    """
                    INSERT INTO fraud_transactions (
                        transaction_id, customer_id, card_id, merchant_id, merchant_category,
                        amount, currency, country, event_timestamp
                    )
                    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
                    ON CONFLICT (transaction_id) DO NOTHING
                    """,
                    tx,
                )
                cur.execute(
                    """
                    INSERT INTO fraud_labels (transaction_id, is_fraud, label_timestamp, label_source)
                    VALUES (%s, %s, %s, %s)
                    ON CONFLICT (transaction_id) DO UPDATE
                    SET is_fraud = EXCLUDED.is_fraud,
                        label_timestamp = EXCLUDED.label_timestamp,
                        label_source = EXCLUDED.label_source
                    """,
                    (row[0], label, row[8] + timedelta(days=2), "sample"),
                )
                cur.execute(
                    """
                    INSERT INTO fraud_label_events (
                        transaction_id, is_fraud, label_timestamp, label_source
                    )
                    SELECT %s, %s, %s, %s
                    WHERE NOT EXISTS (
                        SELECT 1
                        FROM fraud_label_events
                        WHERE transaction_id = %s
                          AND label_timestamp = %s
                          AND label_source = %s
                    )
                    """,
                    (
                        row[0],
                        label,
                        row[8] + timedelta(days=2),
                        "sample",
                        row[0],
                        row[8] + timedelta(days=2),
                        "sample",
                    ),
                )
                write_customer_features(cur, row)
                write_merchant_features(cur, row)
        conn.commit()


def write_customer_features(cur, row) -> None:
    transaction_id, customer_id, _, _, _, amount, _, country, event_timestamp, label = row
    cross_border = 1 if country not in {"SE", "DE", "NO"} else 0
    cur.execute(
        """
        INSERT INTO customer_transaction_stats (
            customer_id, event_timestamp, customer_transaction_count_1h,
            customer_transaction_count_24h, customer_total_amount_24h,
            customer_avg_amount_7d, customer_max_amount_7d,
            customer_distinct_merchants_24h, customer_cross_border_count_7d
        )
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
        ON CONFLICT (customer_id, event_timestamp) DO UPDATE
        SET customer_transaction_count_1h = EXCLUDED.customer_transaction_count_1h,
            customer_transaction_count_24h = EXCLUDED.customer_transaction_count_24h,
            customer_total_amount_24h = EXCLUDED.customer_total_amount_24h,
            customer_avg_amount_7d = EXCLUDED.customer_avg_amount_7d,
            customer_max_amount_7d = EXCLUDED.customer_max_amount_7d,
            customer_distinct_merchants_24h = EXCLUDED.customer_distinct_merchants_24h,
            customer_cross_border_count_7d = EXCLUDED.customer_cross_border_count_7d
        """,
        (
            customer_id,
            event_timestamp,
            2 + label,
            5 + label,
            float(amount) * (1.3 + label),
            float(amount) / (2.5 - min(label, 1)),
            float(amount),
            2 + label,
            cross_border + label,
        ),
    )


def write_merchant_features(cur, row) -> None:
    _, _, _, merchant_id, merchant_category, _, _, _, event_timestamp, label = row
    risk = 0.85 if label else 0.25
    cur.execute(
        """
        INSERT INTO merchant_risk_features (
            merchant_id, event_timestamp, merchant_risk_score, merchant_category
        )
        VALUES (%s, %s, %s, %s)
        ON CONFLICT (merchant_id, event_timestamp) DO UPDATE
        SET merchant_risk_score = EXCLUDED.merchant_risk_score,
            merchant_category = EXCLUDED.merchant_category
        """,
        (merchant_id, event_timestamp, risk, merchant_category),
    )


if __name__ == "__main__":
    main()
