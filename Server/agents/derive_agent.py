import json
import os
from copy import deepcopy

from agents import action_summarize_agent
from agents.prompts import derive_agent_prompt
from memory.memory_manager import Memory
from utils.utils import query, log, parse_completion_rate
from utils import action_utils, parsing_utils


class DeriveAgent:
    def __init__(self, memory: Memory, instruction: str):
        self.memory = memory
        self.instruction = instruction
        self.subtask = None
        self.subtask_history = []
        self.action_history = []
        self.response_history = []

    def init_subtask(self, subtask: dict, subtask_history: list) -> None:
        self.subtask = subtask
        self.subtask_history = subtask_history
        self.action_history = []

    def derive(self, screen: str, action_failed=False, suggestions=None, examples=None) -> (dict, dict):
        if examples is None:
            examples = []
        if action_failed:
            self.action_history.pop()
            derive_prompt = derive_agent_prompt.get_prompts(self.instruction, self.subtask,
                                                            self.subtask_history + self.action_history, screen,
                                                            examples, suggestions)
        else:
            derive_prompt = derive_agent_prompt.get_prompts(self.instruction, self.subtask,
                                                            self.subtask_history + self.action_history, screen,
                                                            examples)
        # 生成大模型的提示词（整合所有推导依据）
        # derive_agent_prompt.get_prompts：传入用户指令、当前子任务、历史记录、界面信息、示例，生成结构化提示词
        # 提示词内容示例：“用户要‘发消息’，当前子任务是‘点击发送’，历史已执行‘输入文本’，界面有‘发送按钮’，请生成点击坐标”
        response = query(derive_prompt, model=os.getenv("DERIVE_AGENT_GPT_VERSION"))
        response['completion_rate'] = parse_completion_rate(response['completion_rate'])
        self.response_history.append(response)

        history = "your past response: " + json.dumps(response) + " has been executed successfully."
        self.action_history.append(history)
        # 生成当前动作的示例（含指令、子任务、界面、响应，供后续复用）
        example = self.__exemplify(response, screen)
        # 返回推导的具体动作和示例
        return response['action'], example

        # Save in real time.
        # self.__generalize_and_save_action(response, screen)

        # generalized_action = self.__generalize_action(response, screen)
        #
        # return response['action'], generalized_action

    # 这部分是注释掉的未启用功能，核心是 “动作泛化与实时保存”：
    # self.__generalize_action(response, screen)：将本次推导的具体动作（如 “点击# x = 550, y = 850”）泛化为 “通用动作模板”（如 “点击‘发送’按钮的中心坐标”），便于跨界面复用（如不同手机分辨率下自动适配坐标）；
    # self.__generalize_and_save_action(response, screen)：将泛化后的动作模板实时保存到 “动作知识库”（如# memory / < 应用 > / actions.csv），实现长期复用；



    def add_finish_action(self) -> None:
        finish_action = {
            "name": "finish",
            "parameters": {},
        }
        self.memory.save_action(self.subtask['name'], finish_action, example=None)

    def summarize_actions(self) -> str:
        if len(self.response_history) > 0:
            action_summary = action_summarize_agent.summarize_actions(self.response_history)
            self.action_history = []
            self.response_history = []
            return action_summary

    def __exemplify(self, response: dict, screen: str) -> dict:
        action = response['action']
        example = {}
        if "index" in action['parameters']:
            shrunk_xml = parsing_utils.shrink_screen_xml(screen, int(action['parameters']['index']))
            example = {"instruction": self.instruction, "subtask": json.dumps(self.subtask), "screen": shrunk_xml,
                       "response": json.dumps(response)}
        return example

    def __generalize_and_save_action(self, response: dict, screen) -> None:
        action = response['action']
        example = {}
        if "index" in response['action']['parameters']:
            action = deepcopy(action)
            subtask_arguments = self.subtask['parameters']
            action = action_utils.generalize_action(action, screen, subtask_arguments)

            shrunk_xml = parsing_utils.shrink_screen_xml(screen, int(action['parameters']['index']))
            example = {"instruction": self.instruction, "subtask": json.dumps(self.subtask), "screen": shrunk_xml, "response": json.dumps(response)}


        self.memory.save_action(self.subtask, action, example)




