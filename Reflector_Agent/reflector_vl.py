"""
ReflectorVL模块 - 视觉模态智能体反思器
执行出错时调用
并提供改进建议
"""
import json
import logging
import sys
import os
import base64

# 添加Server目录到路径，以便导入utils
sys.path.append(os.path.join(os.path.dirname(os.path.dirname(__file__)), 'Server'))

from Reflector_Agent.base import AgentMemoryVL, Reflection
from Reflector_Agent.reflector_prompt import DEFAULT_PERSONA_FORMAT_TEMPLATE, DEFAULT_REFLECTOR_SYSTEM_PROMPT_VL
from utils.utils import query, log

from openai import OpenAI

# 设置日志
logger = logging.getLogger(__name__)

client = OpenAI(
    api_key="sk-c2cc873160714661aa76b6d5ab7239bf",
    base_url="https://dashscope.aliyuncs.com/compatible-mode/v1"
)

# 编码函数：将本地文件转换为 Base64 编码的字符串
def encode_image(image_path):
    with open(image_path, "rb") as image_file:
        return base64.b64encode(image_file.read()).decode("utf-8")

class ReflectorVL:
    def __init__(self, memory: AgentMemoryVL):
        self.memory = memory

    def reflect_on_episodic_memory(self, agent_memory: AgentMemoryVL) -> Reflection:
        """
        基于情景记忆进行反思分析
        Args:
            agent_memory (AgentMemoryVL): 包含智能体执行步骤的情景记忆

        Returns:
            Reflection: 包含分析结果的反思对象

        Raises:
            json.JSONDecodeError: 当LLM返回的JSON格式无效时，会递归重试
        """

        log(f"开始反思", 'red')

        # 创建系统提示词，定义反思器的角色和分析规则
        system_prompt_content = DEFAULT_REFLECTOR_SYSTEM_PROMPT_VL

        # 格式化智能体人格信息
        persona_content = DEFAULT_PERSONA_FORMAT_TEMPLATE

        base64_image = base64.b64encode(agent_memory.curScreenshot).decode('utf-8')

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
            "请根据上述信息，提供直接建议和对当前界面执行失败的简要总结。请根据指定的JSON格式返回结果。"
        ]
        user_content = "\n\n".join(content_sections)

        # 创建聊天完成请求
        completion = client.chat.completions.create(
            model="qwen3-vl-plus",
            messages=[
                {
                    "role": "system",
                    "content": system_prompt_content
                },
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "image_url",
                            "image_url": {"url": f"data:image/jpeg;base64,{base64_image}"},
                        },
                        {"type": "text", "text": user_content},
                    ],
                },
            ],
            stream=False,
            extra_body={
                'enable_thinking': False,
                "thinking_budget": 500,
            },
        )

        # 收集完整回复内容
        answer_content = ""

        # 一次性获取完整回复
        if hasattr(completion, 'choices') and completion.choices:
            answer_content = completion.choices[0].message.content

            try:
                # 如果response已经是字典类型，直接使用
                if isinstance(answer_content, dict):
                    return Reflection.from_dict(answer_content)

                # 如果response是字符串，进行JSON解析
                content = str(answer_content).strip()

                # 移除markdown代码块标记（如果存在）
                if content.startswith('```json'):
                    content = content[7:]  # 移除 ```json
                elif content.startswith('```'):
                    content = content[3:]  # 移除 ```

                if content.endswith('```'):
                    content = content[:-3]  # 移除结尾的 ```

                content = content.strip()

                # 解析JSON响应并创建Reflection对象
                parsed_response = json.loads(content)
                return Reflection.from_dict(parsed_response)
            except json.JSONDecodeError as e:
                # 如果JSON解析失败，记录错误并递归重试
                logger.error(f"Failed to parse reflection response: {e}")
                logger.error(f"Raw response: {answer_content}")
                return self.reflect_on_episodic_memory(agent_memory=agent_memory)
        else:
            self.reflect_on_episodic_memory(agent_memory=agent_memory)