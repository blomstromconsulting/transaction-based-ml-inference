import json
from pathlib import Path


def load_model_catalog(path: str = "training/model_catalog.json") -> dict:
    catalog_path = Path(path)
    with catalog_path.open("r", encoding="utf-8") as file:
        return json.load(file)
