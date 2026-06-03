CREATE TABLE fraud_transactions (
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

CREATE INDEX idx_fraud_transactions_event_timestamp
    ON fraud_transactions (event_timestamp);

CREATE TABLE customer_transaction_stats (
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

CREATE TABLE merchant_risk_features (
    merchant_id TEXT NOT NULL,
    event_timestamp TIMESTAMPTZ NOT NULL,
    created_timestamp TIMESTAMPTZ NOT NULL DEFAULT now(),
    merchant_risk_score REAL NOT NULL,
    merchant_category TEXT NOT NULL,
    PRIMARY KEY (merchant_id, event_timestamp)
);

CREATE TABLE fraud_prediction_logs (
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

CREATE INDEX idx_fraud_prediction_logs_inference_timestamp
    ON fraud_prediction_logs (inference_timestamp);

CREATE TABLE fraud_transaction_processing (
    transaction_id TEXT PRIMARY KEY,
    model TEXT NOT NULL,
    status TEXT NOT NULL,
    error_message TEXT,
    started_timestamp TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_timestamp TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_fraud_transaction_processing_status
    ON fraud_transaction_processing (status);
