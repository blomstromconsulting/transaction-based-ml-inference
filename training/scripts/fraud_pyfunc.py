from typing import Any

import joblib
import mlflow.pyfunc
import pandas as pd


class FraudProbabilityModel(mlflow.pyfunc.PythonModel):
    def load_context(self, context: Any) -> None:
        self.bundle = joblib.load(context.artifacts["model_bundle"])
        self.pipeline = self.bundle["pipeline"]
        self.columns = self.bundle["columns"]

    def predict(self, context: Any, model_input: pd.DataFrame, params: dict | None = None) -> pd.DataFrame:
        columns = self.columns["numeric"] + self.columns["categorical"]
        scores = self.pipeline.predict_proba(model_input[columns])[:, 1]
        return pd.DataFrame({"fraud_score": scores})
