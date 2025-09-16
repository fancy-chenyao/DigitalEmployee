import os
from typing import List

import pandas as pd
from pymongo import MongoClient


def get_db():
    uri = os.getenv("MONGODB_URI", "mongodb://192.168.96.177:27017")
    db_name = os.getenv("MONGODB_DB", "mobilegpt")
    client = MongoClient(uri)
    return client[db_name]


def load_dataframe(collection_name: str, columns: List[str]) -> pd.DataFrame:
    db = get_db()
    collection = db[collection_name]
    docs = list(collection.find({}))
    if len(docs) == 0:
        return pd.DataFrame([], columns=columns)
    for d in docs:
        if "_id" in d:
            del d["_id"]
    df = pd.DataFrame(docs)
    # Ensure all columns exist
    for col in columns:
        if col not in df.columns:
            df[col] = None
    # Keep column order as provided
    return df[columns]


def save_dataframe(collection_name: str, df: pd.DataFrame) -> None:
    db = get_db()
    collection = db[collection_name]
    collection.delete_many({})
    records = df.to_dict(orient="records") if not df.empty else []
    if records:
        collection.insert_many(records)


def append_one(collection_name: str, doc: dict) -> None:
    db = get_db()
    db[collection_name].insert_one(doc)


def upsert_one(collection_name: str, filter_doc: dict, doc: dict) -> None:
    db = get_db()
    db[collection_name].replace_one(filter_doc, doc, upsert=True)



