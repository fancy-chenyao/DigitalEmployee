import json
import os

import pandas as pd

from agents.prompts import task_agent_prompt
from utils.utils import query, log
from utils.mongo_utils import load_dataframe, save_dataframe
# task_agent.py 就是 MobileGPT 的“任务翻译机”：
# 把用户的自然语言指令（如“帮我把这张发票发到微信群里”）翻译成结构化的任务 API（任务名、描述、所需参数、目标 App），
# 并决定这是全新任务还是已有经验可复用。


class TaskAgent:
    def __init__(self):
        # 使用 MongoDB 集合 'global_tasks' 持久化
        self.collection = 'global_tasks'
        self.database = load_dataframe(self.collection, ['name', 'description', 'parameters', 'app'])

    def get_task(self, instruction) -> (dict, bool):
        known_tasks = self.database.to_dict(orient='records') # 读取已知任务列表
        # 调用提示词模板生成查询，调用大模型
        response = query(messages=task_agent_prompt.get_prompts(instruction, known_tasks),
                         model=os.getenv("TASK_AGENT_GPT_VERSION"))

        task = response["api"]
        is_new = True # 默认标记为新任务
        # 若存在匹配的已知任务，更新任务库并标记为非新任务
        if str(response["found_match"]).lower() == "true":
            self.update_task(task)
            is_new = False

        return task, is_new

    # hard-coded
    # def get_task(self, instruction) -> (dict, bool):
    #     sample_response = """{"name":"sendGenericMessageToTelegram", "description": "send a generic message to Telegram without specifying a recipient or message content", "parameters":{}, "app": "Telegram"}"""
    #
    #     return json.loads(sample_response), True

    def update_task(self, task):
        # 匹配任务名和目标应用均相同的记录
        condition = (self.database['name'] == task['name']) & (self.database['app'] == task['app'])
        index_to_update = self.database.index[condition]

        if not index_to_update.empty:
            # 更新匹配记录的描述和参数
            # Update the 'description' and 'parameters' for the row(s) that match the condition
            self.database.loc[index_to_update, 'description'] = task['description']
            self.database.loc[index_to_update, 'parameters'] = task['parameters']
        else:
            # 无匹配时日志提示
            # Handle the case where no matching row is found
            log("No matching task found to update", "red")
        save_dataframe(self.collection, self.database)
