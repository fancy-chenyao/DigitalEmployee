import os
import numpy as np
import pandas as pd

from serpapi import GoogleSearch

from agents.prompts import app_agent_prompt
from utils.utils import log, get_openai_embedding, cosine_similarity, query, safe_literal_eval
from utils.mongo_utils import load_dataframe, save_dataframe

# 获取应用信息
def get_package_info(package_name):
    # 调用 SerpAPI 的 Google Play 产品搜索接口，参数为应用包名和API密钥
    params = {
        "engine": "google_play_product",
        "store": "apps",
        "product_id": package_name,
        "api_key": os.getenv("GOOGLESEARCH_KEY")
    }

    search_result = GoogleSearch(params).get_dict()

    app_name = None
    description = None

    if "about_this_app" in search_result and "snippet" in search_result["about_this_app"]:
        app_name = search_result["product_info"]["title"]
        description = search_result["about_this_app"]["snippet"]
    else:
        log("No description: " + package_name, 'red')

    return app_name, description


class AppAgent:
    def __init__(self):
        # 使用 MongoDB 集合 'apps' 持久化
        self.collection = 'apps'
        self.database = load_dataframe(self.collection, ['app_name', 'package_name', 'description', 'embedding'])

    def update_app_list(self, new_packages):
        known_packages = [row["package_name"] for _, row in self.database.iterrows()]
        for package_name in new_packages:
            if not package_name in known_packages:
                app_name, description = get_package_info(package_name)
                if description:
                    embedding = get_openai_embedding(description)
                else:
                    embedding = ""

                new_app = {"package_name": package_name, 'app_name': app_name, 'description': description,
                           "embedding": str(embedding)}
                self.database = pd.concat([self.database, pd.DataFrame([new_app])], ignore_index=True)
                log("New app detected: " + package_name, "green")

        save_dataframe(self.collection, self.database)
        log(f"App Analyze Finished. Number of stored applications: {len(self.database)}", "blue")

    def predict_app(self, instruction) -> str:
        self.database['embedding'] = self.database.embedding.apply(safe_literal_eval)

        instruction_vector = np.array(get_openai_embedding(instruction))
        self.database["similarity"] = self.database.embedding.apply(lambda x: cosine_similarity(x, instruction_vector))

        candidates = self.database.sort_values('similarity', ascending=False).head(5)
        log("candidate apps", "blue")
        log(candidates, "blue")

        response = query(messages=app_agent_prompt.get_prompts(instruction=instruction, candidates=candidates),
                         model=os.getenv("APP_AGENT_GPT_VERSION"))

        log(f"Instruction: {instruction}")
        log(f"predicted App: {response['app']}")

        return response['app']

    def get_package_name(self, app) -> str:
        matching_rows = self.database[self.database['app_name'] == app]

        if not matching_rows.empty:
            return matching_rows['package_name'].tolist()[0]
        else:
            return ""

    def get_app_name(self, package_name: str) -> str:
        matching_rows = self.database[self.database['package_name'] == package_name]

        if not matching_rows.empty:
            return matching_rows['app_name'].tolist()[0]
        else:
            return ""
