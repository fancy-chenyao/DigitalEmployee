import json
import os

import pandas as pd

from agents import param_fill_agent
from utils.action_utils import adapt_action
from log_config import log
from utils.mongo_utils import load_dataframe, save_dataframe


def init_database(collection: str, headers: list):
    return load_dataframe(collection, headers)


class PageManager:
    def __init__(self,  page_index):
        self.page_index = page_index


        subtask_header = ['name', 'description', 'parameters', 'example']
        action_header = ['subtask_name', 'step', 'action', 'example']
        available_subtask_header = ['name', 'description', 'parameters']

        # MongoDB 集合名（简化结构，不再按应用拆分）
        self.subtask_db_path = f"page_{page_index}_subtasks"
        self.available_subtask_db_path = f"page_{page_index}_available_subtasks"
        self.action_db_path = f"page_{page_index}_actions"

        self.subtask_db = init_database(self.subtask_db_path, subtask_header)
        self.available_subtask_db = init_database(self.available_subtask_db_path, available_subtask_header)
        self.action_db = init_database(self.action_db_path, action_header)

        self.action_data = self.action_db.to_dict(orient='records')

        for action in self.action_data:
            action['traversed'] = False

    def get_available_subtasks(self):
        return self.available_subtask_db.to_dict(orient='records')

    def add_new_action(self, new_action):
        self.available_subtask_db = pd.concat([self.available_subtask_db, pd.DataFrame([new_action])], ignore_index=True)
        save_dataframe(self.available_subtask_db_path, self.available_subtask_db)

    def save_subtask(self, subtask_raw: dict, example: dict):
        # 检查是否已存在
        if not self.subtask_db.empty and subtask_raw['name'] in self.subtask_db['name'].values:
            return
        
        subtask_data = {
            "name": subtask_raw['name'],
            "description": subtask_raw['description'],
            "parameters": json.dumps(subtask_raw['parameters']),
            "example": json.dumps(example)
        }

        # 使用批量操作优化
        from utils.mongo_utils import append_one
        append_one(self.subtask_db_path, subtask_data)
        
        # 更新内存中的DataFrame
        new_row = pd.DataFrame([subtask_data])
        self.subtask_db = pd.concat([self.subtask_db, new_row], ignore_index=True)
        log("added new subtask to the database")

    def get_next_subtask_data(self, subtask_name: str) -> dict:
        # Filter the subtask_db for rows matching the specific 'name'
        filtered_subtask = self.subtask_db[(self.subtask_db['name'] == subtask_name)]
        next_subtask_data = filtered_subtask.iloc[0].to_dict()

        return next_subtask_data

    def save_action(self, subtask_name, step: int, action: dict, example=None) -> None:
        if example is None:
            example = {}
        new_action_db = {
            "subtask_name": subtask_name,
            'step': step,
            "action": json.dumps(action),
            "example": json.dumps(example)
        }

        # 使用批量操作优化
        from utils.mongo_utils import append_one
        append_one(self.action_db_path, new_action_db)

        # 更新内存中的DataFrame
        new_row = pd.DataFrame([new_action_db])
        self.action_db = pd.concat([self.action_db, new_row], ignore_index=True)

        # Append to action data 同步更新内存中的动作列表（添加"traversed"标记，标记为已执行）
        new_action_data = {
            "subtask_name": subtask_name,
            'step': step,
            "action": json.dumps(action),
            "example": json.dumps(example),
            "traversed": True
        }
        self.action_data.append(new_action_data)

    def get_next_action(self, subtask: dict, screen: str, step: int):
        # 步骤1：获取当前子任务名（如“click_send_button”）
        curr_subtask_name = subtask['name']
        examples = []
        # 步骤2：遍历内存中的动作列表，查找匹配的动作
        for action_data in self.action_data:
            # 匹配条件：1. 关联的子任务名一致；2. 动作步骤一致；3. 未被执行过（traversed=False）
            if action_data.get("subtask_name", "") == curr_subtask_name and action_data.get("step") == step:
                if not action_data.get("traversed", False):
                    action_data['traversed'] = True
                    next_base_action = json.loads(action_data.get("action")) #action："{""name"": ""click"", ""parameters"": {""index"": 40, ""description"": ""Create contact""}}"
                    examples.append(json.loads(action_data.get("example")))

                    subtask_arguments = subtask['parameters']
                    adapted_action = adapt_action(next_base_action, screen, subtask_arguments)
                    if adapted_action:
                        return adapted_action
        # 若未找到可执行动作，但有示例，返回示例列表（供DeriveAgent泛化）
        if len(examples) > 0:
            return {"examples": examples}
        # 若既无动作也无示例，返回None（需DeriveAgent新生成动作）
        return None

    def update_subtask_info(self, subtask) -> None:
        condition = (self.subtask_db['name'] == subtask['name'])
        if condition.any():
            self.subtask_db.loc[condition, 'name'] = subtask['name']
            self.subtask_db.loc[condition, 'description'] = subtask['description']
            self.subtask_db.loc[condition, 'parameters'] = json.dumps(subtask['parameters'])

            save_dataframe(self.subtask_db_path, self.subtask_db)

    def merge_subtask_into(self, base_subtask_name, prev_subtask_name, target_subtask_name):
        actions = self.action_db.to_dict(orient="records")
        starting_step = 0

        for action in actions[:]:  # Iterating over a copy of the list
            subtask_name = action['subtask_name']
            action_data = json.loads(action['action'])
            if subtask_name == prev_subtask_name and action_data['name'] == 'finish':
                starting_Step = action['step']
                actions.remove(action)

        for action in actions[:]:
            subtask_name = action['subtask_name']
            if subtask_name == target_subtask_name:
                action['subtask_name'] = base_subtask_name
                action['step'] = starting_step + action['step']

        self.action_db = pd.DataFrame(actions)
        save_dataframe(self.action_db_path, self.action_db)
    def delete_subtask(self, subtask_name):
        """
        仅根据子任务名称删除数据
        """
        # 1. 删除subtask_db中名称匹配的记录
        # 筛选条件：仅匹配子任务名称
        subtask_condition = (self.subtask_db['name'] == subtask_name)

        if subtask_condition.any():
            # 保留不满足条件的记录（即删除名称匹配的记录）
            self.subtask_db = self.subtask_db[~subtask_condition]
            # 持久化到CSV
            save_dataframe(self.subtask_db_path, self.subtask_db)
            log(f"已删除子任务: {subtask_name} (共 {subtask_condition.sum()} 条记录)", "blue")
        else:
            log(f"未找到名称为 {subtask_name} 的子任务", "yellow")
            return

