FROM python:3.11-slim
WORKDIR /app
COPY kserve/transformer/requirements.txt .
RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential gcc python3-dev \
    && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir -r requirements.txt
COPY kserve/transformer/transformer.py .
COPY feast/ /feast/
ENV FEAST_REPO_PATH=/feast
ENV FEAST_REDIS_CONNECTION_STRING=redis:6379
ENTRYPOINT ["sh", "-c", "python /feast/scripts/render_feature_store.py && cd /feast && feast apply && cd /app && exec python transformer.py \"$@\"", "--"]
