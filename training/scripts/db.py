import os

import psycopg


def database_url() -> str:
    return os.getenv(
        "TRAINING_DATABASE_URL",
        "postgresql://feast:feast@localhost:5432/fraud_features",
    )


def connect():
    return psycopg.connect(database_url())
