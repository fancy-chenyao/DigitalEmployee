import json

from utils.utils import generate_numbered_list

default_actions = [
    {
        "name": "ask",
        "description": "向用户询问更多信息以完成任务。避免询问不必要的信息或向用户确认",
        "parameters": {"info_name": {"type": "string",
                                     "description": "你需要从用户那里获取的信息名称"},
                       "question": {"type": "string",
                                    "description": "向用户询问以获取信息的问题"}},
    },
    {
        "name": "click",
        "description": "点击屏幕上的特定按钮",
        "parameters": {"index": {"type": "integer", "description": "要点击的UI元素的索引"}}
    },
    {
        "name": "long-click",
        "description": "长按UI元素。只能用于具有长按属性的UI元素",
        "parameters": {"index": {"type": "integer", "description": "要长按的UI元素的索引"}}
    },
    {
        "name": "input",
        "description": "在屏幕上输入文本",
        "parameters": {"index": {"type": "integer", "description": "接受文本输入的UI元素的索引"},
                       "input_text": {"type": "string", "description": "要输入的文本或值"}}
    },
    # {
    #     "name": "scroll",
    #     "description": "向上或向下滚动以查看更多UI元素",
    #     "parameters": {"index": {"type": "integer", "description": "要滚动的UI元素的索引"},
    #                    "direction": {"type": "string", "description": "滚动方向，默认为'down'",
    #                                  "enum": ["up", "down"]}}
    # },
    {
        "name": "repeat-click",
        "description": "重复点击操作多次",
        "parameters": {"index": {"type": "integer", "description": "要点击的UI元素的索引"},
                       "number": {"type": "integer", "description": "要点击的次数"}}
    },
    {
        "name": "finish",
        "description": "使用此动作表示你已完成给定的子任务",
        "parameters": {}
    }
]


def get_sys_prompt():
    numbered_actions = generate_numbered_list(default_actions)
    sys_msg = (
        "你是一个可以与移动应用交互的智能手机助手智能体。你的工作是通过指导用户如何执行特定子任务来帮助用户使用移动应用，以实现他们的最终目标。"
        "根据当前移动屏幕上的可用动作列表（由<screen></screen>分隔）和导致此屏幕的过去事件，确定要采取的下一步动作以完成给定的子任务。\n\n"

        "***指导原则***:\n"
        "请按以下步骤逐步执行：\n"
        "1. 首先，阅读过去事件的历史记录（由三重引号分隔）以掌握任务执行的整体流程。\n"
        "2. 阅读由<screen></screen>分隔的屏幕HTML代码以掌握当前应用屏幕的整体意图。\n"
        "3. 选择一个能够让你更接近完成给定子任务的动作。如果过去事件表明任务已完成，请选择'finish'动作。\n"
        "4. 自我评估你完成子任务的接近程度\n"
        "5. 规划你的下一步行动\n\n"

        "***理解屏幕HTML代码的提示***:\n"
        "1. 每个HTML元素代表屏幕上的一个UI元素。\n"
        "2. 多个UI元素可以共同服务于单一目的。"
        "因此，在理解UI元素的用途时，查看其父元素或子元素会很有帮助。\n\n"

        "***选择下一个动作的提示***:\n"
        "1. 始终反思过去事件以确定你的下一个动作。避免重复相同的动作。\n"
        '2. 如果你需要更多信息来完成任务，使用"ask"命令从用户那里获取更多信息。'
        "但要非常小心，不要不必要地或重复地询问。如果人类不知道答案，请尽你所能自己找出答案。\n"

        "***选择动作的约束条件***:\n"
        "1. 你一次只能执行一个动作。\n"
        "2. 仅使用下面列出的动作。\n"
        "3. 当过去事件表明子任务已完成时，确保选择'finish'动作。\n"
        "4. 只完成给你的子任务。其余部分由用户决定。不要继续执行其他步骤。\n\n"

        "可用动作列表：\n"
        f"{numbered_actions}\n"

        "当过去事件表明给定的子任务已完成时，确保选择'finish'动作。\n\n"

        "使用下面描述的JSON格式回应\n"
        "回应格式：\n"
        '{"reasoning": <基于过去事件和屏幕HTML代码的推理>, "action": {"name":<动作名称>, "parameters": {<参数名称>: <参数值>,...}},'
        '"completion_rate": <表示你完成任务的接近程度>, "plan": <你下一步行动的计划>}\n'
        "开始！"
    )
    return sys_msg


def get_usr_prompt(instruction, subtask, history, screen, examples, suggestions):
    if len(history) == 0:
        numbered_history = "0. 暂无事件。\n"
    else:
        numbered_history = generate_numbered_list(history)

    usr_msg = ""
    if len(suggestions) > 0:
        error_action = suggestions['出错的动作']
        advice = suggestions['建议']
        usr_msg += (
            "***请注意***:\n"
            f"你之前选择的动作是：{error_action}。但是，在执行过程中遇到了错误。"
            f"在选择动作时请参考以下建议：{advice}\n\n"
        )
    if len(examples) > 0:
        for i, example in enumerate(examples):
            example_instruction = example['instruction']
            example_subtask = example['subtask']
            example_screen = f"...(为简洁起见省略)...{example['screen']}...(为简洁起见省略)..."
            example_response = example['response']

            usr_msg += (
                f"[示例 #{i}]\n"
                f"用户的最终目标（指令）：{example_instruction}\n"
                "（只完成下面给你的子任务。你可以忽略未知值的参数。但不要继续执行其他步骤）\n"
                f"给你的子任务：{example_subtask}\n\n"

                "过去事件：\n"
                "'''\n"
                f"...(为简洁起见省略)...\n"
                f"'''\n\n"

                "当前应用屏幕的HTML代码，由<screen> </screen>分隔：\n"
                f"<screen>{example_screen}</screen>\n\n"

                "回应：\n"
                f"{example_response}\n"
                f"[示例 {i} 结束]\n\n"
            )

        usr_msg += "轮到你了：\n"

    usr_msg += (
        f"用户的最终目标（指令）：{instruction}\n"
        "（只完成下面给你的子任务。你可以忽略未知值的参数。但不要继续执行其他步骤）\n"
        f"给你的子任务：{json.dumps(subtask)}\n\n"

        "过去事件：\n"
        "'''\n"
        f"{numbered_history}\n"
        f"'''\n\n"

        "当前应用屏幕的HTML代码，由<screen> </screen>分隔：\n"
        f"<screen>{screen}</screen>\n\n"

        "回应：\n"
    )

    return usr_msg


def get_prompts(instruction: str, subtask: dict, history: list, screen: str, examples: list, suggestions: list):
    sys_msg = get_sys_prompt()
    usr_msg = get_usr_prompt(instruction, subtask, history, screen, examples, suggestions)
    messages = [{"role": "system", "content": sys_msg},
                {"role": "user", "content": usr_msg}]
    
    return messages
