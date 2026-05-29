FROM python:3.11-slim

WORKDIR /feast
COPY feast/requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt
COPY feast/ /feast/

ENV FEAST_REDIS_CONNECTION_STRING=redis:6379
EXPOSE 6566
CMD ["sh", "-c", "python scripts/render_feature_store.py && feast apply && feast serve --host 0.0.0.0 --port 6566"]
