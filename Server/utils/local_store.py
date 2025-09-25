import os
import pandas as pd

from env_config import Config


def _csv_root() -> str:
    # Align with Server_origin: memory/log/<task>/...
    base = os.path.join(Config.MEMORY_DIRECTORY, "log")
    os.makedirs(base, exist_ok=True)
    return base


def _task_root(task_name: str) -> str:
    root = os.path.join(_csv_root(), task_name)
    os.makedirs(root, exist_ok=True)
    return root


def read_dataframe_csv(collection_name: str, headers: list, task_name: str | None = None, page_index: int | None = None) -> pd.DataFrame:
    if task_name is None:
        # global-level（根级）。与 Server_origin 对齐：global_tasks → tasks.csv
        base = _csv_root()
        filename = "tasks.csv" if collection_name == "global_tasks" else f"{collection_name}.csv"
        file_path = os.path.join(base, filename)
    else:
        # task-level
        task_dir = _task_root(task_name)
        if collection_name in ("tasks", "pages", "hierarchy"):
            file_path = os.path.join(task_dir, f"{collection_name}.csv")
        else:
            # page-level
            if page_index is None:
                # fallback to task root collection file
                file_path = os.path.join(task_dir, f"{collection_name}.csv")
            else:
                page_dir = os.path.join(task_dir, "pages", str(page_index))
                os.makedirs(page_dir, exist_ok=True)
                file_map = {
                    f"page_{page_index}_subtasks": "subtasks.csv",
                    f"page_{page_index}_available_subtasks": "available_subtasks.csv",
                    f"page_{page_index}_actions": "actions.csv",
                }
                filename = file_map.get(collection_name, f"{collection_name}.csv")
                file_path = os.path.join(page_dir, filename)
    if not os.path.exists(file_path):
        df = pd.DataFrame([], columns=headers)
        # 列补齐与顺序
        for h in headers:
            if h not in df.columns:
                df[h] = None
        return df[headers]
    try:
        df = pd.read_csv(file_path)
        # 确保列齐全且顺序稳定
        for h in headers:
            if h not in df.columns:
                df[h] = None
        return df[headers]
    except Exception:
        df = pd.DataFrame([], columns=headers)
        for h in headers:
            if h not in df.columns:
                df[h] = None
        return df[headers]


def write_dataframe_csv(collection_name: str, df: pd.DataFrame, task_name: str | None = None, page_index: int | None = None) -> None:
    if Config.ENABLE_DB:
        return
    try:
        if task_name is None:
            base = _csv_root()
            filename = "tasks.csv" if collection_name == "global_tasks" else f"{collection_name}.csv"
            file_path = os.path.join(base, filename)
        else:
            task_dir = _task_root(task_name)
            if collection_name in ("tasks", "pages", "hierarchy"):
                file_path = os.path.join(task_dir, f"{collection_name}.csv")
            else:
                if page_index is None:
                    file_path = os.path.join(task_dir, f"{collection_name}.csv")
                else:
                    page_dir = os.path.join(task_dir, "pages", str(page_index))
                    os.makedirs(page_dir, exist_ok=True)
                    file_map = {
                        f"page_{page_index}_subtasks": "subtasks.csv",
                        f"page_{page_index}_available_subtasks": "available_subtasks.csv",
                        f"page_{page_index}_actions": "actions.csv",
                    }
                    filename = file_map.get(collection_name, f"{collection_name}.csv")
                    file_path = os.path.join(page_dir, filename)
        df.to_csv(file_path, index=False)
    except Exception:
        pass


def append_one_csv(collection_name: str, doc: dict, task_name: str | None = None, page_index: int | None = None) -> None:
    if Config.ENABLE_DB:
        return
    try:
        existing = read_dataframe_csv(collection_name, list(doc.keys()), task_name=task_name, page_index=page_index)
        df = pd.concat([existing, pd.DataFrame([doc])], ignore_index=True)
        write_dataframe_csv(collection_name, df, task_name=task_name, page_index=page_index)
    except Exception:
        pass


def get_screen_bundle_dir(task_name: str, page_index: int) -> str:
    # memory/log/<task>/pages/<index>/screen
    page_dir = os.path.join(_task_root(task_name), "pages", str(page_index))
    screen_dir = os.path.join(page_dir, "screen")
    os.makedirs(screen_dir, exist_ok=True)
    return screen_dir



