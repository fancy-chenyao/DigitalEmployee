"""
Reflector模块 - 智能体反思器
执行出错时调用
并提供改进建议
"""

import json
import logging
import sys
import os

# 添加Server目录到路径，以便导入utils
sys.path.append(os.path.join(os.path.dirname(os.path.dirname(__file__)), 'Server'))

from Reflector_Agent.base import AgentMemory, Reflection
from Reflector_Agent.reflector_prompt import DEFAULT_REFLECTOR_SYSTEM_PROMPT, DEFAULT_PERSONA_FORMAT_TEMPLATE
from utils.utils import query, log

# 设置日志
logger = logging.getLogger(__name__)

class Reflector:
    def __init__(self, memory: AgentMemory):
        self.memory = memory

    
    def reflect_on_episodic_memory(self, agent_memory: AgentMemory) -> 'Reflection':
        """
        基于情景记忆进行反思分析
        Args:
            agent_memory (AgentMemory): 包含智能体执行步骤的情景记忆
            
        Returns:
            Reflection: 包含分析结果的反思对象
            
        Raises:
            json.JSONDecodeError: 当LLM返回的JSON格式无效时，会递归重试
        """

        log(f"开始反思", 'red')

        # 创建系统提示词，定义反思器的角色和分析规则
        system_prompt_content = DEFAULT_REFLECTOR_SYSTEM_PROMPT

        # 格式化智能体人格信息
        persona_content = DEFAULT_PERSONA_FORMAT_TEMPLATE

        formatted_steps = []
        formatted_step_0 = f"""0 执行子任务前界面信息:
        {agent_memory.preXML}
        """
        formatted_steps.append(formatted_step_0)
        formatted_step_1 = f"""1 执行子任务后界面信息:
        {agent_memory.curXML}
        """
        formatted_steps.append(formatted_step_1)

        formatted_steps_str = "\n".join(formatted_steps)

        error_type = agent_memory.errTYPE
        error_message = agent_memory.errMessage
        action = agent_memory.action

        instruction = agent_memory.instruction
        current_subtask = agent_memory.current_subtask
        available_subtasks = agent_memory.available_subtasks

        # 构建用户消息内容，包含人格信息、目标和执行步骤
        content_sections = [
            persona_content,
            f"用户整体的指令: {instruction}",
            f"选择子任务前可用的子任务列表: {available_subtasks}",
            f"当前正在执行的子任务: {current_subtask}",
            f"客户端返回的错误类型: {error_type}",
            f"执行返回的错误信息: {error_message}",
            f"客户端执行的子任务动作: {action}",
            f"执行子任务前后的界面信息:\n{formatted_steps_str}",
            "请根据上述信息，提供直接建议和对当前界面执行失败的简要总结。请根据指定的JSON格式返回结果。"
        ]
        user_content = "\n\n".join(content_sections)

        # 构建消息列表，符合Server中query函数的格式
        messages = [
            {"role": "system", "content": system_prompt_content},
            {"role": "user", "content": user_content}
        ]
            
        # 调用大语言模型进行分析
        response = query(messages=messages)

        # 记录反思结果到日志
        log(f"反思结果: {response}", 'red')

        try:
            # 如果response已经是字典类型，直接使用
            if isinstance(response, dict):
                return Reflection.from_dict(response)
            
            # 如果response是字符串，进行JSON解析
            content = str(response).strip()
            
            # 移除markdown代码块标记（如果存在）
            if content.startswith('```json'):
                content = content[7:]  # 移除 ```json
            elif content.startswith('```'):
                content = content[3:]   # 移除 ```
            
            if content.endswith('```'):
                content = content[:-3]  # 移除结尾的 ```
            
            content = content.strip()
            
            # 解析JSON响应并创建Reflection对象
            parsed_response = json.loads(content)
            return Reflection.from_dict(parsed_response)
        except json.JSONDecodeError as e:
            # 如果JSON解析失败，记录错误并递归重试
            logger.error(f"Failed to parse reflection response: {e}")
            logger.error(f"Raw response: {response}")
            return self.reflect_on_episodic_memory(agent_memory=agent_memory)