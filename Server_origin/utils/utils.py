import os, csv, re
import numpy as np
import json
import pandas as pd

from termcolor import colored
from openai import OpenAI
from typing import List
from ast import literal_eval


def log(msg, color='white'):
    if not color:
        print(msg)
        return

    colored_log = colored(msg, color, attrs=['bold'])
    print(colored_log)
    print()


def safe_literal_eval(x):
    if pd.isna(x):
        return np.nan  # or return np.array([]) for converting NaN to empty arrays
    else:
        return np.array(literal_eval(x))


def get_openai_embedding(text: str, model="text-embedding-v1", **kwargs) -> List[float]:
    client = OpenAI(
        api_key="sk-401cd3617a3b4f96a8cd820d76bacfa1",
        base_url="https://dashscope.aliyuncs.com/compatible-mode/v1",
    )
    # replace newlines, which can negatively affect performance.
    text = text.replace("\n", " ")

    response = client.embeddings.create(input=[text], model=model, **kwargs)

    return response.data[0].embedding


def cosine_similarity(a, b):
    if isinstance(a, np.ndarray) and isinstance(b, np.ndarray):
        return np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b))
    else:
        return 0


def generate_numbered_list(data: list) -> str:
    result_string = ""

    for index, item in enumerate(data, start=1):
        if isinstance(item, dict):
            result_string += f"- {json.dumps(item)}\n"
        else:
            result_string += f"- {item}\n"

    return result_string


def query(messages, model="qwen3-32b", is_list=False):
    client = OpenAI(
    api_key="sk-401cd3617a3b4f96a8cd820d76bacfa1",
    base_url="https://dashscope.aliyuncs.com/compatible-mode/v1",
    )

    for message in messages:
        log("--------------------------")
        log(message["content"], 'yellow')
    # log("--------------------------")
    # log(messages[-1]["content"], 'yellow')

    response = client.chat.completions.create(
        model=model,
        messages=messages,
        temperature=0.2,
        presence_penalty=0.5,
        seed=1234,
        extra_body={
            "enable_thinking": False,
            "top_k": 10,
            }
    )

    # response = client.chat.completions.create(
    #     model=model,
    #     messages=messages,
    #     temperature=0,
    #     max_tokens=900,
    #     top_p=0,
    #     frequency_penalty=0,
    #     presence_penalty=0
    # )
    result = response.choices[0].message.content
    log(result, 'green')
    json_formatted_response = __parse_json(result, is_list=is_list)
    if json_formatted_response:
        return json.loads(json_formatted_response)
    else:
        return result


# def query(messages, model="Qwen", is_list=False):
#     # 移除 OpenAI 客户端，改用 requests 调用本地模型
#     import requests  # 需导入 requests 库
#
#     # 本地模型的 API 地址（根据实际部署情况修改）
#     local_api_url = ""
#
#     # 打印输入消息（保留原日志逻辑）
#     for message in messages:
#         log("--------------------------")
#         log(message["content"], 'yellow')
#
#     # 构造请求参数（与 OpenAI API 格式对齐，便于兼容）
#     payload = {
#         "model": model,
#         "messages": messages,
#         "temperature": 0,
#         "max_tokens": 900,
#         "top_p": 0,
#         "frequency_penalty": 0,
#         "presence_penalty": 0
#     }
#
#     # 发送请求到本地模型
#     response = requests.post(
#         url=local_api_url,
#         json=payload,
#         headers={"Content-Type": "application/json"}
#     )
#     response.raise_for_status()  # 检查请求是否成功
#     result = response.json()["choices"][0]["message"]["content"]
#
#     # 保留原日志和 JSON 解析逻辑
#     log(result, 'green')
#     json_formatted_response = __parse_json(result, is_list=is_list)
#     if json_formatted_response:
#         return json.loads(json_formatted_response)
#     else:
#         return result



def parse_completion_rate(completion_rate) -> int:
    # Convert the input to a string in case it's an integer
    input_str = str(completion_rate).strip()

    # Check if the string ends with a '%'
    if input_str.endswith('%'):
        # Remove the '%' and convert to integer
        return int(float(input_str[:-1]))
    else:
        # Convert to float to handle decimal or integer strings
        value = float(input_str)

        # If the value is less than 1, it's likely a decimal representation of a percentage
        if value < 1:
            return int(value * 100)
        # Otherwise, it's already in percentage form
        else:
            return int(value)


def __parse_json(s: str, is_list=False):
    if is_list:
        matches = re.search(r'\[.*\]', s, re.DOTALL)

        if matches:
            return matches.group(0)
    else:
        matches = re.search(r'\{.*\}', s, re.DOTALL)

        if matches:
            return matches.group(0)
