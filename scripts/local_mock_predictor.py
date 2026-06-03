#!/usr/bin/env python3
import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


MODEL_FEATURES = {
    "MODEL_A": [
        "transaction_amount",
        "transaction_country",
        "merchant_category",
        "customer_transaction_count_1h",
        "customer_transaction_count_24h",
        "customer_total_amount_24h",
        "customer_avg_amount_7d",
    ],
    "MODEL_B": [
        "transaction_amount",
        "transaction_country",
        "merchant_category",
        "customer_transaction_count_1h",
        "customer_transaction_count_24h",
        "customer_total_amount_24h",
        "customer_avg_amount_7d",
        "customer_max_amount_7d",
        "customer_distinct_merchants_24h",
        "customer_cross_border_count_7d",
        "merchant_risk_score",
    ],
}


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("content-length", "0"))
        payload = json.loads(self.rfile.read(length) or b"{}")
        model = payload.get("model") or ("MODEL_B" if "model-b" in self.path else "MODEL_A")
        transaction = payload.get("transaction") or {}
        amount = float(transaction.get("transaction_amount") or 0)
        score = 0.91 if model == "MODEL_B" and amount >= 1000 else 0.42
        decision = "DECLINE" if score >= 0.85 else "REVIEW" if score >= 0.60 else "APPROVE"
        response = {
            "fraudScore": score,
            "decision": decision,
            "featuresUsed": MODEL_FEATURES.get(model, MODEL_FEATURES["MODEL_A"]),
            "metadata": {
                "source": "local-mock-predictor",
                "model": model,
            },
        }
        body = json.dumps(response).encode("utf-8")
        self.send_response(200)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        return


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18081)
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"local mock predictor listening on http://{args.host}:{args.port}")
    server.serve_forever()


if __name__ == "__main__":
    main()
