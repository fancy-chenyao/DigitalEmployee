import os
import threading
from typing import List, Optional, Dict, Any

import pandas as pd
from pymongo import MongoClient
from pymongo.errors import ConnectionFailure, ServerSelectionTimeoutError


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
                uri = os.getenv("MONGODB_URI", "mongodb://192.168.100.56:27017")
                db_name = os.getenv("MONGODB_DB", "mobilegpt")
                
                # 连接池配置
                max_pool_size = int(os.getenv("MONGODB_MAX_POOL_SIZE", "50"))
                min_pool_size = int(os.getenv("MONGODB_MIN_POOL_SIZE", "5"))
                
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
    
    return _mongo_client[os.getenv("MONGODB_DB", "mobilegpt")]


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



