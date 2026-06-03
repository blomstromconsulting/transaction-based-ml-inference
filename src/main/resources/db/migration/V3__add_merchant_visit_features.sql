ALTER TABLE customer_transaction_stats
    ADD COLUMN IF NOT EXISTS current_merchant_visit_count_30d BIGINT NOT NULL DEFAULT 0;

ALTER TABLE customer_transaction_stats
    ADD COLUMN IF NOT EXISTS current_merchant_visit_share_30d DOUBLE PRECISION NOT NULL DEFAULT 0.0;

ALTER TABLE customer_transaction_stats
    ADD COLUMN IF NOT EXISTS current_merchant_rank_30d BIGINT NOT NULL DEFAULT 0;

ALTER TABLE customer_transaction_stats
    ADD COLUMN IF NOT EXISTS is_current_merchant_top_visited_30d BIGINT NOT NULL DEFAULT 0;

ALTER TABLE customer_transaction_stats
    ADD COLUMN IF NOT EXISTS days_since_first_seen_current_merchant DOUBLE PRECISION NOT NULL DEFAULT -1.0;

ALTER TABLE customer_transaction_stats
    ADD COLUMN IF NOT EXISTS days_since_last_seen_current_merchant DOUBLE PRECISION NOT NULL DEFAULT -1.0;

ALTER TABLE customer_transaction_stats
    ADD COLUMN IF NOT EXISTS customer_distinct_merchants_30d BIGINT NOT NULL DEFAULT 0;

ALTER TABLE customer_transaction_stats
    ADD COLUMN IF NOT EXISTS is_new_merchant_for_customer BIGINT NOT NULL DEFAULT 1;

ALTER TABLE customer_transaction_stats
    ADD COLUMN IF NOT EXISTS top_visited_merchant_id_30d TEXT NOT NULL DEFAULT '';
