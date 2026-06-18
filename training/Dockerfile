FROM python:3.11-slim

WORKDIR /workspace
COPY training/requirements.txt /tmp/requirements.txt
RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential gcc python3-dev \
    && rm -rf /var/lib/apt/lists/* \
    && pip install --no-cache-dir -r /tmp/requirements.txt

COPY feast/ /workspace/feast/
COPY training/ /workspace/training/

ENV PYTHONPATH=/workspace/training/scripts
CMD ["python", "training/scripts/train_with_mlflow.py"]
