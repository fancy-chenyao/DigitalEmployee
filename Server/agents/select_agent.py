import json
import os

from agents.prompts import select_agent_prompt
from memory.memory_manager import Memory
from utils.utils import query, log, parse_completion_rate


class SelectAgent:
    def __init__(self, memory: Memory, instruction: str):
        self.memory = memory
        self.instruction = instruction

    def select(self, available_subtasks: list, subtask_history: list, qa_history: list, screen: str, subtask_failed=False, suggestions=None) -> (dict, dict):
        log(f":::SELECT:::", "blue")
        # 用户原始指令（子任务选择的目标依据）
        # 当前可执行子任务列表（LLM只能从这里选，或新增）
        # 子任务历史（避免重复选择已完成的子任务）
        # 问答历史（补充用户提供的参数信息，如消息内容）
        # 界面XML（LLM可分析界面元素，判断子任务可行性）
        if suggestions is None:
            suggestions = []

        if subtask_failed:
            select_prompts = select_agent_prompt.get_prompts(self.instruction, available_subtasks, subtask_history,
                                                             qa_history, screen, suggestions)
        else:
            # 一些版本的 get_prompts 要求显式提供 suggestions 参数
            select_prompts = select_agent_prompt.get_prompts(self.instruction, available_subtasks, subtask_history,
                                                             qa_history, screen, [])

        response = query(select_prompts, model=os.getenv("SELECT_AGENT_GPT_VERSION"))
        
        # Check if response is valid JSON
        if not isinstance(response, dict):
            log(f"Invalid JSON response from LLM: {response}", "red")
            # Try to parse as JSON if it's a string
            if isinstance(response, str):
                try:
                    response = json.loads(response)
                except json.JSONDecodeError as e:
                    log(f"Failed to parse JSON response: {e}", "red")
                    # Create a fallback response
                    response = {
                        "action": {"name": "finish", "parameters": {}},
                        "completion_rate": "100%",
                        "speak": "I encountered an error and will finish the task."
                    }
        # 规范化响应：若缺action但有new_action，则将new_action映射为action（保留new_action用于回写）
        if 'action' not in response and 'new_action' in response:
            log("Response missing 'action', mapping from 'new_action'", "yellow")
            response['action'] = response['new_action']

        # 若仍然缺action，进行一次带错误提示的重试
        if 'action' not in response:
            log(f"Response missing 'action' key. Response: {response}", "red")
            assistant_message = {"role": "assistant", "content": json.dumps(response)}
            select_prompts.append(assistant_message)
            error_message = {"role": "user", "content": "Error: Response missing 'action' key. Always include 'action'. If you propose a new action, also include 'new_action' and duplicate it to 'action'."}
            select_prompts.append(error_message)
            response = query(select_prompts, model=os.getenv("SELECT_AGENT_GPT_VERSION"))
            # 二次兜底
            if isinstance(response, dict) and 'action' not in response and 'new_action' in response:
                response['action'] = response['new_action']

        # 循环验证响应：若LLM选择的子任务无效（不在可执行列表且未新增），则重新提问修正
        while not self.__check_response_validity(response, available_subtasks):
            # 1. 将之前的无效响应作为“助手消息”加入提示词（让LLM知道自己之前错了）
            assistant_msg = {"role": "assistant", "content": json.dumps(response)}
            select_prompts.append(assistant_msg)
            # 2. 加入“错误提示消息”，明确告知LLM错误原因（选择的子任务不在列表中）
            error_msg = {"role": "user", "content": "Error: The selected action is not in the available actions list. You may propose 'new_action', but you must also include it as 'action'."}
            select_prompts.append(error_msg)
            # 3. 重新调用LLM，获取修正后的响应
            response = query(select_prompts, model=os.getenv("SELECT_AGENT_GPT_VERSION"))
            if isinstance(response, dict) and 'action' not in response and 'new_action' in response:
                response['action'] = response['new_action']

        next_subtask_filled = response['action']
        for subtask in available_subtasks:
            if subtask['name'] == next_subtask_filled['name']:
                next_subtask_raw = subtask
                self.__save_as_example(next_subtask_raw, screen, response)
        if "new_action" in response:
            return response, response['new_action']
        else:
            return response, None

    def __check_response_validity(self, response, available_subtasks):
        # 传入参数：
        # response：LLM的响应结果（含选中的子任务action）
        # available_subtasks：当前页面的可执行子任务列表
        # 返回值：bool（True=响应有效，False=响应无效）
        
        # Check if response is valid and contains required keys
        if not isinstance(response, dict):
            log(f"Invalid response type: {type(response)}", "red")
            return False
            
        if 'action' not in response:
            log(f"Response missing 'action' key. Response: {response}", "red")
            return False
            
        action = response['action']
        
        # Check if action has required structure
        if not isinstance(action, dict) or 'name' not in action:
            log(f"Invalid action structure: {action}", "red")
            return False

        # Check if the selected action is in the available subtasks
        # 步骤1：判断是否为"系统预设的基础子任务"（无需在available_subtasks中，直接判定有效）
        # 基础子任务：finish（结束）、speak（语音播报），系统已内置执行逻辑
        # scroll_screen（滑动）已注释掉
        subtask_match = False
        if action['name'] in ['finish', 'speak']:  # 移除 'scroll_screen'
            subtask_match = True
            return True

        # 步骤2：判断选中的子任务是否在“可执行子任务列表”中
        for subtask in available_subtasks:
            if subtask['name'] == action['name']:# 按子任务名称匹配
                subtask_match = True
                return True# 匹配成功，返回有效

        # 步骤3：若子任务不匹配，判断是否包含“new_action”（新增子任务，需加入可执行列表）
        if not subtask_match:
            # if this is a new action, we need to add it to the available subtasks
            # 响应中包含new_action（LLM认为需要新增子任务，且提供了完整信息）
            if "new_action" in response:
                new_action = response['new_action']
                available_subtasks.append(new_action)
                return True

            # if selected action is not in the available subtasks and not provided with new action, we send error message to GPT
            # 不匹配且无new_action（响应无效，需重新提问LLM）
            else:
                return False

    def __save_as_example(self, subtask_raw, screen, response):
        # Optional key: some models may not return completion_rate
        if 'completion_rate' in response:
            del response['completion_rate']
        example = {"instruction": self.instruction, "screen": screen, "response": response}
        self.memory.save_subtask(subtask_raw, example)
