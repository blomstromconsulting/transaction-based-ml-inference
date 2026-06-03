CREATE TABLE IF NOT EXISTS fraud_transactions (
    transaction_id TEXT PRIMARY KEY,
    customer_id TEXT NOT NULL,
    card_id TEXT NOT NULL,
    merchant_id TEXT NOT NULL,
    merchant_category TEXT NOT NULL,
    amount DOUBLE PRECISION NOT NULL,
    currency TEXT NOT NULL,
    country TEXT NOT NULL,
    event_timestamp TIMESTAMPTZ NOT NULL,
    created_timestamp TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS fraud_labels (
    transaction_id TEXT PRIMARY KEY REFERENCES fraud_transactions(transaction_id),
    is_fraud INTEGER NOT NULL CHECK (is_fraud IN (0, 1)),
    label_timestamp TIMESTAMPTZ NOT NULL,
    label_source TEXT NOT NULL,
    label_confidence DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    annotator_id TEXT,
    reason_code TEXT,
    created_timestamp TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_timestamp TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS fraud_label_events (
    id BIGSERIAL PRIMARY KEY,
    transaction_id TEXT NOT NULL REFERENCES fraud_transactions(transaction_id),
    is_fraud INTEGER NOT NULL CHECK (is_fraud IN (0, 1)),
    label_timestamp TIMESTAMPTZ NOT NULL,
    label_source TEXT NOT NULL,
    label_confidence DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    annotator_id TEXT,
    reason_code TEXT,
    created_timestamp TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS customer_transaction_stats (
    customer_id TEXT NOT NULL,
    event_timestamp TIMESTAMPTZ NOT NULL,
    created_timestamp TIMESTAMPTZ NOT NULL DEFAULT now(),
    customer_transaction_count_1h BIGINT NOT NULL,
    customer_transaction_count_24h BIGINT NOT NULL,
    customer_total_amount_24h DOUBLE PRECISION NOT NULL,
    customer_avg_amount_7d DOUBLE PRECISION NOT NULL,
    customer_max_amount_7d DOUBLE PRECISION NOT NULL,
    customer_distinct_merchants_24h BIGINT NOT NULL,
    customer_cross_border_count_7d BIGINT NOT NULL,
    PRIMARY KEY (customer_id, event_timestamp)
);

CREATE TABLE IF NOT EXISTS merchant_risk_features (
    merchant_id TEXT NOT NULL,
    event_timestamp TIMESTAMPTZ NOT NULL,
    created_timestamp TIMESTAMPTZ NOT NULL DEFAULT now(),
    merchant_risk_score REAL NOT NULL,
    merchant_category TEXT NOT NULL,
    PRIMARY KEY (merchant_id, event_timestamp)
);

CREATE TABLE IF NOT EXISTS fraud_prediction_logs (
    transaction_id TEXT NOT NULL,
    model TEXT NOT NULL,
    model_version TEXT NOT NULL,
    fraud_score DOUBLE PRECISION NOT NULL,
    decision TEXT NOT NULL,
    features_used JSONB NOT NULL,
    inference_timestamp TIMESTAMPTZ NOT NULL,
    created_timestamp TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (transaction_id, model, model_version)
);

CREATE TABLE IF NOT EXISTS fraud_transaction_processing (
    transaction_id TEXT PRIMARY KEY,
    model TEXT NOT NULL,
    status TEXT NOT NULL,
    error_message TEXT,
    started_timestamp TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_timestamp TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE OR REPLACE VIEW fraud_training_examples AS
SELECT
    t.transaction_id,
    t.customer_id,
    t.merchant_id,
    t.event_timestamp,
    t.amount AS transaction_amount,
    t.country AS transaction_country,
    t.merchant_category,
    l.is_fraud,
    l.label_timestamp,
    l.label_source,
    l.label_confidence,
    l.annotator_id,
    l.reason_code
FROM fraud_transactions t
JOIN fraud_labels l ON l.transaction_id = t.transaction_id
WHERE l.label_timestamp >= t.event_timestamp;
