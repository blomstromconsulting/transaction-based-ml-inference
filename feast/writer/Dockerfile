FROM python:3.11-slim

WORKDIR /app
COPY feast/writer/requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt
COPY feast/ /feast/
COPY feast/writer/app.py /app/app.py

ENV FEAST_REPO_PATH=/feast
ENV FEAST_REDIS_CONNECTION_STRING=redis:6379
EXPOSE 8080
CMD ["sh", "-c", "python /feast/scripts/render_feature_store.py && cd /feast && feast apply && cd /app && uvicorn app:app --host 0.0.0.0 --port 8080"]
