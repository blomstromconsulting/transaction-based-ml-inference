import os
from typing import Any

import mlflow.pyfunc
import pandas as pd
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel


class PredictRequest(BaseModel):
    instances: list[dict[str, Any]]
    parameters: dict[str, Any] | None = None


app = FastAPI()
model = mlflow.pyfunc.load_model(os.getenv("MODEL_URI", "/opt/ml/model"))


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/v1/models/{model_name}:predict")
def predict(model_name: str, request: PredictRequest) -> dict[str, Any]:
    if not request.instances:
        raise HTTPException(status_code=400, detail="instances must contain at least one row")
    frame = pd.DataFrame(request.instances)
    predictions = model.predict(frame)
    if isinstance(predictions, pd.DataFrame):
        scores = predictions["fraud_score"].tolist()
    else:
        scores = [float(value) for value in predictions]
    return {
        "predictions": [{"fraud_score": float(score)} for score in scores],
        "parameters": request.parameters or {"model": model_name},
    }
