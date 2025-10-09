import os, csv, re
import numpy as np
import json
import pandas as pd

from termcolor import colored
from openai import OpenAI
from typing import List
from ast import literal_eval
import threading
import time
import hashlib
from collections import OrderedDict
from datetime import datetime


# 统一到标准 logging：从 log_config 重导出 log
from log_config import log


def safe_literal_eval(x):
    # 已为空或 NaN
    if pd.isna(x):
        return np.array([])
    # 已是数组/列表/元组
    if isinstance(x, (list, tuple, np.ndarray)):
        return np.array(x)
    # 非字符串，直接包装
    if not isinstance(x, str):
        return np.array([x]) if x is not None else np.array([])
    s = x.strip()
    # 空字符串
    if s == "":
        return np.array([])
    # 兼容字符串化的列表/元组/数字
    try:
        parsed = literal_eval(s)
        if isinstance(parsed, (list, tuple, np.ndarray)):
            return np.array(parsed)
        # 单值也转为一维数组，保持余下代码的 np.ndarray 假设
        return np.array([parsed])
    except Exception:
        # 回退策略：无法解析时返回空数组而非抛错
        return np.array([])


_EMBED_CACHE_LOCK = threading.RLock()
_EMBED_CACHE: OrderedDict[str, List[float]] = OrderedDict()

_DIAG_LOCK = threading.RLock()
_DIAG = {
    "query_total_calls": 0,
    "query_cache_hits": 0,
    "query_cache_misses": 0,
    "query_total_duration_ms": 0.0,
    "query_total_retries": 0,
    "query_calls_with_retry": 0,
    "query_logging_overhead_ms": 0.0,
    "embed_total_calls": 0,
    "embed_cache_hits": 0,
    "embed_cache_misses": 0,
}


def _diag_update(**kwargs):
    with _DIAG_LOCK:
        for k, v in kwargs.items():
            if k in _DIAG and isinstance(_DIAG[k], (int, float)):
                _DIAG[k] += v


def get_ai_diagnostics(reset: bool = False) -> dict:
    with _DIAG_LOCK:
        snapshot = dict(_DIAG)
        q_total = max(snapshot["query_total_calls"], 1)
        total_query_cache = snapshot["query_cache_hits"] + snapshot["query_cache_misses"]
        total_embed_cache = snapshot["embed_cache_hits"] + snapshot["embed_cache_misses"]
        snapshot.update({
            "query_avg_duration_ms": snapshot["query_total_duration_ms"] / q_total,
            "query_avg_retries": snapshot["query_total_retries"] / q_total,
            "query_hit_rate": (snapshot["query_cache_hits"] / total_query_cache) if total_query_cache > 0 else 0.0,
            "query_avg_logging_overhead_ms": snapshot["query_logging_overhead_ms"] / q_total,
            "embed_hit_rate": (snapshot["embed_cache_hits"] / total_embed_cache) if total_embed_cache > 0 else 0.0,
        })
        result = dict(snapshot)
        if reset:
            for k in _DIAG.keys():
                _DIAG[k] = 0 if isinstance(_DIAG[k], int) else 0.0
        return result


def _make_cache_key(*parts) -> str:
    joined = "|".join(str(p) for p in parts)
    return hashlib.md5(joined.encode("utf-8")).hexdigest()


def _embed_cache_get(key: str):
    with _EMBED_CACHE_LOCK:
        if key in _EMBED_CACHE:
            value = _EMBED_CACHE.pop(key)
            _EMBED_CACHE[key] = value
            return value
        return None


def _embed_cache_set(key: str, value, max_size: int):
    with _EMBED_CACHE_LOCK:
        if key in _EMBED_CACHE:
            _EMBED_CACHE.pop(key)
        _EMBED_CACHE[key] = value
        while len(_EMBED_CACHE) > max_size:
            _EMBED_CACHE.popitem(last=False)


def get_openai_embedding(text: str, model="text-embedding-v1", **kwargs) -> List[float]:
    max_cache = int(os.getenv("EMBED_CACHE_MAX", "1024"))
    text_norm = (text or "").replace("\n", " ")
    cache_key = _make_cache_key("embed", model, text_norm)
    cached = _embed_cache_get(cache_key)
    if cached is not None:
        _diag_update(embed_total_calls=1, embed_cache_hits=1)
        return cached

    client = OpenAI(
        # api_key="sk-401cd3617a3b4f96a8cd820d76bacfa1",
        api_key="sk-c2cc873160714661aa76b6d5ab7239bf",
        base_url="https://dashscope.aliyuncs.com/compatible-mode/v1",
    )
    response = client.embeddings.create(input=[text_norm], model=model, **kwargs)
    embedding = response.data[0].embedding
    _embed_cache_set(cache_key, embedding, max_cache)
    _diag_update(embed_total_calls=1, embed_cache_misses=1)
    return embedding


def cosine_similarity(a, b):
    # 兼容 list/tuple 输入
    if isinstance(a, (list, tuple)):
        a = np.array(a)
    if isinstance(b, (list, tuple)):
        b = np.array(b)

    # 必须是 ndarray
    if not isinstance(a, np.ndarray) or not isinstance(b, np.ndarray):
        return 0

    # 非空、形状一致
    if a.size == 0 or b.size == 0 or a.shape != b.shape:
        return 0

    denom = (np.linalg.norm(a) * np.linalg.norm(b))
    if denom == 0:
        return 0
    return float(np.dot(a, b) / denom)


def generate_numbered_list(data: list) -> str:
    result_string = ""

    for index, item in enumerate(data, start=1):
        if isinstance(item, dict):
            result_string += f"- {json.dumps(item)}\n"
        else:
            result_string += f"- {item}\n"

    return result_string


_QUERY_CACHE_LOCK = threading.RLock()
_QUERY_CACHE: OrderedDict[str, dict] = OrderedDict()


def _query_cache_get(key: str, ttl_seconds: int):
    now = time.time()
    with _QUERY_CACHE_LOCK:
        if key in _QUERY_CACHE:
            entry = _QUERY_CACHE.pop(key)
            if now - entry["ts"] <= ttl_seconds:
                _QUERY_CACHE[key] = entry
                return entry["val"]
        return None


def _query_cache_set(key: str, value, max_entries: int):
    with _QUERY_CACHE_LOCK:
        if key in _QUERY_CACHE:
            _QUERY_CACHE.pop(key)
        _QUERY_CACHE[key] = {"val": value, "ts": time.time()}
        while len(_QUERY_CACHE) > max_entries:
            _QUERY_CACHE.popitem(last=False)


def query(messages, model="qwen3-32b", is_list=False):
    cache_enabled = os.getenv("AI_CACHE_ENABLED", "true").lower() == "true"
    cache_ttl = int(os.getenv("AI_CACHE_TTL", "900"))
    cache_max = int(os.getenv("AI_CACHE_MAX", "512"))
    max_retries = int(os.getenv("AI_MAX_RETRIES", "3"))
    base_delay = float(os.getenv("AI_RETRY_BASE_DELAY", "0.8"))
    log_enabled = os.getenv("AI_QUERY_LOG", "true").lower() == "true"

    try:
        msg_key = json.dumps(messages, sort_keys=True, ensure_ascii=False)
    except Exception:
        msg_key = json.dumps([m.get("content", "") for m in messages], ensure_ascii=False)
    cache_key = _make_cache_key("chat", model, str(is_list), msg_key)

    t_start = time.time()
    if cache_enabled:
        cached = _query_cache_get(cache_key, cache_ttl)
        if cached is not None:
            duration_ms = (time.time() - t_start) * 1000.0
            _diag_update(query_total_calls=1, query_cache_hits=1, query_total_duration_ms=duration_ms)
            return cached

    client = OpenAI(
        # api_key="sk-401cd3617a3b4f96a8cd820d76bacfa1",
        api_key="sk-c2cc873160714661aa76b6d5ab7239bf",
        base_url="https://dashscope.aliyuncs.com/compatible-mode/v1",
    )

    log_overhead = 0.0
    # if log_enabled:
    #     t_log = time.time()
    #     for message in messages:
    #         # 注释掉全量提示词打印以减少I/O开销
    #         # log("--------------------------")
    #         # log(message.get("content", ""), 'yellow')
    #         pass
    #     log_overhead += (time.time() - t_log)

    attempt = 0
    last_exception = None
    while attempt <= max_retries:
        try:
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

            result = response.choices[0].message.content
            # if log_enabled:
            #     t_log2 = time.time()
            #     # 注释掉全量结果打印以减少I/O开销
            #     # log(result, 'green')
            #     log_overhead += (time.time() - t_log2)
            json_formatted_response = __parse_json(result, is_list=is_list)
            parsed = json.loads(json_formatted_response) if json_formatted_response else result

            if cache_enabled:
                _query_cache_set(cache_key, parsed, cache_max)

            duration_ms = (time.time() - t_start) * 1000.0
            _diag_update(
                query_total_calls=1,
                query_cache_misses=1 if cache_enabled else 0,
                query_total_duration_ms=duration_ms,
                query_total_retries=max(0, attempt),
                query_calls_with_retry=1 if attempt > 0 else 0,
                query_logging_overhead_ms=log_overhead * 1000.0,
            )
            return parsed
        except Exception as e:
            last_exception = e
            if attempt >= max_retries:
                break
            delay = base_delay * (2 ** attempt)
            time.sleep(delay)
            attempt += 1

    log(f"LLM call failed after retries: {last_exception}", "red")
    duration_ms = (time.time() - t_start) * 1000.0
    _diag_update(
        query_total_calls=1,
        query_cache_misses=1 if cache_enabled else 0,
        query_total_duration_ms=duration_ms,
        query_total_retries=max(0, attempt),
        query_calls_with_retry=1 if attempt > 0 else 0,
        query_logging_overhead_ms=log_overhead * 1000.0,
    )
    return "{}" if not is_list else "[]"


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
        # Try to find the first complete JSON object by counting braces
        start_idx = s.find('{')
        if start_idx == -1:
            return None
        
        brace_count = 0
        end_idx = start_idx
        
        for i in range(start_idx, len(s)):
            if s[i] == '{':
                brace_count += 1
            elif s[i] == '}':
                brace_count -= 1
                if brace_count == 0:
                    end_idx = i
                    break
        
        if brace_count == 0:
            return s[start_idx:end_idx + 1]
        else:
            # Fallback to original regex if brace counting fails
            matches = re.search(r'\{.*\}', s, re.DOTALL)
            if matches:
                return matches.group(0)
