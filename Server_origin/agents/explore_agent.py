import json
import os

from agents.prompts import explore_agent_prompt
from memory.memory_manager import Memory
from utils.parsing_utils import get_trigger_ui_attributes, get_extra_ui_attributes
from utils.utils import query, log

import xml.etree.ElementTree as ET

# 分析移动应用的屏幕界面（通过 XML 数据），生成可执行的子任务（subtask），并将这些信息存储到系统记忆中
class ExploreAgent:
    def __init__(self, memory: Memory):
        self.memory = memory

    def explore(self, parsed_xml, hierarchy_xml, html_xml, screen_num=None) -> int:
        """
        Desc: Generate a new node based on the given screen xmls
        return: index of the new node.
        """
        
        log(f":::EXPLORE:::", "blue")
        # 通过explore_agent_prompt.get_prompts生成提示词，引导大语言模型分析html_xml描述的界面结构。
        prompts = explore_agent_prompt.get_prompts(html_xml)
        # 使用指定的大语言模型（版本由环境变量EXPLORE_AGENT_GPT_VERSION设定），生成当前屏幕可执行的子任务列表（subtasks_raw）
        subtasks_raw = query(prompts, model=os.getenv("EXPLORE_AGENT_GPT_VERSION"), is_list=True)
        # 遍历原始子任务列表，确保每个子任务都包含必要字段
        for subtask in subtasks_raw:
            if "parameters" not in subtask:
                subtask['parameters'] = {}
            if "trigger_UIs" not in subtask:
                subtask['trigger_UIs'] = []

        subtasks_raw = list(filter(lambda x: len(x["trigger_UIs"]) > 0, subtasks_raw))

        subtasks_trigger_uis = {subtask['name']: subtask['trigger_UIs'] for subtask in subtasks_raw}
        subtasks_trigger_ui_attributes = get_trigger_ui_attributes(subtasks_trigger_uis, parsed_xml)

        # flatten the list of trigger ui indexes.
        trigger_ui_indexes = [index for ui_indexes in subtasks_trigger_uis.values() for index in ui_indexes]
        extra_ui_attributes = get_extra_ui_attributes(trigger_ui_indexes, parsed_xml)

        available_subtasks = [{key: value for key, value in subtask.items() if key != 'trigger_UIs'} for subtask in
                              subtasks_raw]
        # 调用内存管理器的add_node方法，将新页面的信息存储到内存中，传入可用子任务、触发 UI 属性、额外 UI 属性、解析后的 XML 和屏幕编号。pages.csv
        new_node_index = self.memory.add_node(available_subtasks, subtasks_trigger_ui_attributes, extra_ui_attributes, parsed_xml, screen_num)
        # 调用内存管理器的add_hierarchy_xml方法，将完整的界面层级XML与新节点关联存储。保存hierachy.csv
        self.memory.add_hierarchy_xml(hierarchy_xml, new_node_index)

        return new_node_index
