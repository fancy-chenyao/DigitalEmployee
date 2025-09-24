import os
import threading
import time
from typing import List, Optional, Dict, Any

import pandas as pd
from pymongo import MongoClient
from pymongo.errors import ConnectionFailure, ServerSelectionTimeoutError

# 导入配置管理
import sys
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from env_config import Config


# 全局连接池实例
_mongo_client: Optional[MongoClient] = None
_lock = threading.Lock()


def get_db():
    """
    获取MongoDB数据库连接，使用连接池管理
    """
    global _mongo_client
    if _mongo_client is None:
        with _lock:
            if _mongo_client is None:
                # 使用统一的配置管理
                uri = Config.MONGODB_URI
                db_name = Config.MONGODB_DB
                
                # 连接池配置
                max_pool_size = Config.MONGODB_MAX_POOL_SIZE
                min_pool_size = Config.MONGODB_MIN_POOL_SIZE
                
                try:
                    _mongo_client = MongoClient(
                        uri,
                        maxPoolSize=max_pool_size,  # 最大连接池大小
                        minPoolSize=min_pool_size,   # 最小连接池大小
                        maxIdleTimeMS=30000,  # 连接最大空闲时间(30秒)
                        connectTimeoutMS=10000,  # 连接超时(10秒)
                        socketTimeoutMS=30000,   # 套接字超时(30秒)
                        serverSelectionTimeoutMS=5000,  # 服务器选择超时(5秒)
                        retryWrites=True,  # 启用重试写入
                        retryReads=True   # 启用重试读取
                    )
                    
                    # 测试连接
                    _mongo_client.admin.command('ping')
                    print(f"MongoDB连接池初始化成功: {uri}")
                    
                except (ConnectionFailure, ServerSelectionTimeoutError) as e:
                    print(f"MongoDB连接失败: {e}")
                    raise e
                except Exception as e:
                    print(f"MongoDB初始化异常: {e}")
                    raise e
    
    return _mongo_client[Config.MONGODB_DB]


def load_dataframe(collection_name: str, columns: List[str], use_cache: bool = True) -> pd.DataFrame:
    """加载DataFrame，使用智能缓存优化；当 ENABLE_DB=False 时改用本地CSV。"""
    # 本地模式：直接读CSV
    if not Config.ENABLE_DB:
        from utils.local_store import read_dataframe_csv
        return read_dataframe_csv(collection_name, columns)
    # 使用缓存键
    cache_key = f"dataframe_{collection_name}_{hash(tuple(columns))}"
    
    # 检查缓存
    if use_cache and hasattr(load_dataframe, '_cache'):
        if cache_key in load_dataframe._cache:
            cached_data, timestamp, version = load_dataframe._cache[cache_key]
            # 智能缓存：根据数据大小调整缓存时间
            cache_duration = min(300, max(60, len(cached_data) * 0.1))  # 60秒到5分钟
            if time.time() - timestamp < cache_duration:
                return cached_data.copy()
    
    db = get_db()
    collection = db[collection_name]
    
    # 使用投影优化，只查询需要的字段
    projection = {col: 1 for col in columns}
    projection['_id'] = 0  # 排除_id字段
    
    docs = list(collection.find({}, projection))
    if len(docs) == 0:
        result_df = pd.DataFrame([], columns=columns)
    else:
        df = pd.DataFrame(docs)
        # Ensure all columns exist
        for col in columns:
            if col not in df.columns:
                df[col] = None
        # Keep column order as provided
        result_df = df[columns]
    
    # 智能缓存结果
    if use_cache:
        if not hasattr(load_dataframe, '_cache'):
            load_dataframe._cache = {}
        # 添加版本号用于缓存失效
        version = time.time()
        load_dataframe._cache[cache_key] = (result_df.copy(), time.time(), version)
    
    return result_df


def save_dataframe(collection_name: str, df: pd.DataFrame, batch_size: int = 1000) -> None:
    """保存DataFrame；当 ENABLE_DB=False 时改用本地CSV。
    注意：为避免生成平铺文件，以下集合在本地模式下不在此处写入：
      - 以 page_ 开头的集合（由 PageManager 负责写入 pages/<index>/ 下的 CSV）
      - tasks / pages / hierarchy（由 Memory 负责按任务目录写入）
    其他集合（如 global_tasks）仍按全局写入 memory/log/<collection>.csv。
    """
    if not Config.ENABLE_DB:
        if collection_name.startswith("page_") or collection_name in ("tasks", "pages", "hierarchy"):
            return
        from utils.local_store import write_dataframe_csv
        write_dataframe_csv(collection_name, df)
        return
    db = get_db()
    collection = db[collection_name]
    
    # 使用批量操作优化
    if df.empty:
        collection.delete_many({})
        return
    
    records = df.to_dict(orient="records")
    
    # 分批插入，避免内存问题
    for i in range(0, len(records), batch_size):
        batch = records[i:i + batch_size]
        if i == 0:
            # 第一批：清空并插入
            collection.delete_many({})
            collection.insert_many(batch)
        else:
            # 后续批次：直接插入
            collection.insert_many(batch)
    
    # 清除相关缓存
    clear_cache_for_collection(collection_name)


def append_one(collection_name: str, doc: dict) -> None:
    """插入单条记录；当 ENABLE_DB=False 时改用本地CSV。
    为避免生成平铺文件：以 page_ 开头的集合在此处不写，由调用方传 task/page 定位写入。
    """
    if not Config.ENABLE_DB:
        if collection_name.startswith("page_"):
            return
        from utils.local_store import append_one_csv
        append_one_csv(collection_name, doc)
        return
    db = get_db()
    db[collection_name].insert_one(doc)
    clear_cache_for_collection(collection_name)


def append_many(collection_name: str, docs: List[dict], batch_size: int = 1000) -> None:
    """批量插入记录"""
    if not docs:
        return
    
    db = get_db()
    collection = db[collection_name]
    
    # 分批插入
    for i in range(0, len(docs), batch_size):
        batch = docs[i:i + batch_size]
        collection.insert_many(batch)
    
    clear_cache_for_collection(collection_name)


def upsert_one(collection_name: str, filter_doc: dict, doc: dict) -> None:
    """更新或插入单条记录；当 ENABLE_DB=False 时改用本地CSV。"""
    if not Config.ENABLE_DB:
        if collection_name.startswith("page_") or collection_name in ("tasks", "pages", "hierarchy"):
            return
        from utils.local_store import read_dataframe_csv, write_dataframe_csv
        headers = list(set(list(doc.keys()) + list(filter_doc.keys())))
        df = read_dataframe_csv(collection_name, headers)
        if not df.empty:
            mask = pd.Series([True] * len(df))
            for k, v in filter_doc.items():
                if k in df.columns:
                    mask &= (df[k] != v)
            df = df[mask]
        df = pd.concat([df, pd.DataFrame([doc])], ignore_index=True)
        write_dataframe_csv(collection_name, df)
        return
    db = get_db()
    db[collection_name].replace_one(filter_doc, doc, upsert=True)
    clear_cache_for_collection(collection_name)


def upsert_many(collection_name: str, operations: List[dict], batch_size: int = 1000) -> None:
    """批量更新或插入记录；当 ENABLE_DB=False 时改用本地CSV。"""
    if not Config.ENABLE_DB:
        if collection_name.startswith("page_") or collection_name in ("tasks", "pages", "hierarchy"):
            return
        from utils.local_store import read_dataframe_csv, write_dataframe_csv
        if not operations:
            return
        headers: List[str] = []
        for op in operations:
            headers += list(op.get('filter', {}).keys()) + list(op.get('document', {}).keys())
        headers = list(dict.fromkeys(headers))
        df = read_dataframe_csv(collection_name, headers)
        for op in operations:
            f = op.get('filter', {})
            d = op.get('document', {})
            if not df.empty and f:
                mask = pd.Series([True] * len(df))
                for k, v in f.items():
                    if k in df.columns:
                        mask &= (df[k] != v)
                df = df[mask]
            df = pd.concat([df, pd.DataFrame([d])], ignore_index=True)
        write_dataframe_csv(collection_name, df)
        return
    if not operations:
        return
    
    db = get_db()
    collection = db[collection_name]
    
    # 分批执行upsert操作
    for i in range(0, len(operations), batch_size):
        batch = operations[i:i + batch_size]
        for op in batch:
            collection.replace_one(op['filter'], op['document'], upsert=True)
    
    clear_cache_for_collection(collection_name)


def clear_cache_for_collection(collection_name: str) -> None:
    """清除指定集合的缓存"""
    if hasattr(load_dataframe, '_cache'):
        keys_to_remove = [key for key in load_dataframe._cache.keys() if collection_name in key]
        for key in keys_to_remove:
            del load_dataframe._cache[key]


def ensure_indexes(collection_name: str, indexes: List[dict]) -> None:
    """确保集合有必要的索引"""
    db = get_db()
    collection = db[collection_name]
    
    for index_spec in indexes:
        try:
            collection.create_index(
                index_spec['keys'],
                background=True,  # 后台创建索引
                unique=index_spec.get('unique', False),
                sparse=index_spec.get('sparse', False)
            )
        except Exception as e:
            print(f"创建索引失败 {collection_name}: {e}")


def get_collection_stats(collection_name: str) -> Optional[Dict[str, Any]]:
    """获取集合统计信息"""
    try:
        db = get_db()
        collection = db[collection_name]
        
        stats = collection.aggregate([
            {"$group": {
                "_id": None,
                "count": {"$sum": 1},
                "avg_size": {"$avg": {"$bsonSize": "$$ROOT"}}
            }}
        ])
        
        result = list(stats)
        if result:
            return result[0]
        return {"count": 0, "avg_size": 0}
    except Exception as e:
        print(f"获取集合统计失败 {collection_name}: {e}")
        return None


def check_connection() -> bool:
    """
    检查MongoDB连接是否健康
    """
    try:
        if _mongo_client is None:
            return False
        
        # 执行ping命令测试连接
        _mongo_client.admin.command('ping')
        return True
    except (ConnectionFailure, ServerSelectionTimeoutError) as e:
        print(f"MongoDB连接健康检查失败: {e}")
        return False
    except Exception as e:
        print(f"MongoDB健康检查异常: {e}")
        return False


def get_connection_info() -> Optional[Dict[str, Any]]:
    """
    获取MongoDB连接池信息
    """
    if _mongo_client is None:
        return None
    
    try:
        # 获取服务器状态
        server_status = _mongo_client.admin.command("serverStatus")
        
        # 获取连接池信息
        pool_info = {
            'max_pool_size': _mongo_client.max_pool_size,
            'min_pool_size': _mongo_client.min_pool_size,
            'server_info': {
                'host': _mongo_client.address[0],
                'port': _mongo_client.address[1],
                'version': server_status.get('version', 'unknown')
            },
            'connections': {
                'current': server_status.get('connections', {}).get('current', 0),
                'available': server_status.get('connections', {}).get('available', 0),
                'total_created': server_status.get('connections', {}).get('totalCreated', 0)
            },
            'uptime_seconds': server_status.get('uptime', 0),
            'memory_usage_mb': server_status.get('mem', {}).get('resident', 0)
        }
        
        return pool_info
    except Exception as e:
        print(f"获取MongoDB连接信息失败: {e}")
        return None


def close_connection() -> None:
    """
    关闭MongoDB连接池
    """
    global _mongo_client
    if _mongo_client is not None:
        try:
            _mongo_client.close()
            print("MongoDB连接池已关闭")
        except Exception as e:
            print(f"关闭MongoDB连接池时出错: {e}")
        finally:
            _mongo_client = None


def reconnect() -> bool:
    """
    重新连接MongoDB
    """
    global _mongo_client
    try:
        # 关闭现有连接
        close_connection()
        
        # 重新创建连接
        _mongo_client = None
        get_db()  # 这会触发重新连接
        
        return check_connection()
    except Exception as e:
        print(f"MongoDB重连失败: {e}")
        return False


def get_collection_stats(collection_name: str) -> Optional[Dict[str, Any]]:
    """
    获取集合统计信息
    """
    try:
        db = get_db()
        collection = db[collection_name]
        stats = db.command("collStats", collection_name)
        
        return {
            'collection_name': collection_name,
            'count': stats.get('count', 0),
            'size_bytes': stats.get('size', 0),
            'avg_obj_size': stats.get('avgObjSize', 0),
            'storage_size': stats.get('storageSize', 0),
            'indexes': stats.get('nindexes', 0),
            'total_index_size': stats.get('totalIndexSize', 0)
        }
    except Exception as e:
        print(f"获取集合 {collection_name} 统计信息失败: {e}")
        return None


def batch_operations(collection_name: str, operations: List[Dict[str, Any]]) -> Dict[str, Any]:
    """
    批量执行数据库操作
    """
    try:
        db = get_db()
        collection = db[collection_name]
        
        # 执行批量操作
        result = collection.bulk_write(operations)
        
        return {
            'inserted_count': result.inserted_count,
            'matched_count': result.matched_count,
            'modified_count': result.modified_count,
            'deleted_count': result.deleted_count,
            'upserted_count': result.upserted_count,
            'upserted_ids': result.upserted_ids
        }
    except Exception as e:
        print(f"批量操作失败: {e}")
        return {'error': str(e)}


def get_cached_connection() -> MongoClient:
    """
    获取缓存的连接，避免重复创建
    """
    return _mongo_client


def clear_cache():
    """
    清理缓存
    """
    if hasattr(load_dataframe, '_cache'):
        load_dataframe._cache.clear()
    print("数据库缓存已清理")


def get_cache_stats() -> Dict[str, Any]:
    """
    获取缓存统计信息
    """
    if not hasattr(load_dataframe, '_cache'):
        return {'cache_size': 0, 'cached_keys': []}
    
    cache = load_dataframe._cache
    return {
        'cache_size': len(cache),
        'cached_keys': list(cache.keys()),
        'cache_hit_rate': getattr(load_dataframe, '_cache_hits', 0) / max(getattr(load_dataframe, '_cache_requests', 1), 1)
    }



