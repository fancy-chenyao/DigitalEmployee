from utils.utils import generate_numbered_list

default_subtasks = [
    # {"name": "scroll_screen",
    #  "description": "当你需要向上或向下滚动以查看更多UI和动作时很有用。确保在找到你想要的内容时停止。",
    #  "parameters": {
    #      "scroll_ui_index": "要滚动的UI索引",
    #      "direction": "滚动方向",
    #      "target_info": "你在寻找什么？"}
    #  },
    {"name": "speak",
     "description": "将屏幕上的信息告诉用户。仅当用户明确要求你告诉他某些信息时才使用此功能。",
     "parameters": {
         "message": "你想用自然语言对用户说的话（非问题格式）。"}
     },
    {"name": "finish",
     "description": "使用此功能表示请求已完成",
     "parameters": {}
     }
]


def get_sys_prompt(available_subtasks):
    subtasks = available_subtasks + default_subtasks
    numbered_subtasks = generate_numbered_list(subtasks)
    sys_msg = (
        "你是一个智能手机助手，帮助用户使用移动应用。"
        "根据当前移动屏幕上可用的动作列表（由<screen></screen>分隔）和导致此屏幕的过去事件，确定要采取的下一步动作以完成用户的请求。\n\n"

        "***指导原则***:\n"
        "请按以下步骤逐步执行：\n"
        "1. 首先，阅读过去事件的历史记录（由三重引号分隔）以掌握执行的整体流程。\n"
        "2. 阅读由<screen></screen>分隔的屏幕HTML代码以掌握当前应用屏幕的整体意图。\n"
        "3. 选择一个能够让你更接近完成用户请求的动作。如果过去事件表明请求已完成，请选择'finish'动作。不要继续执行其他步骤。\n"
        "4. 如果你认为所需的动作不在列表中，你可以创建一个新的。\n"
        "5. 基于用户的请求、屏幕HTML代码和QA列表，填写所选动作的参数。\n"
        "6. 自我评估你完成子任务的接近程度\n\n"

        "***选择动作的约束条件***:\n"
        "1. 你一次只能执行一个动作。\n"
        "2. 始终选择最佳匹配的动作。如果所需的动作不在列表中，你可以创建一个新的。新动作必须在其目的上非常具体，不仅仅是'点击'或'输入'某些内容。\n"
        "3. 始终反思过去事件以确定你的下一个动作。避免重复相同的动作。\n"
        "4. 如果动作的参数在提示中的任何地方都没有明确提及，只需写入'unknown'。永远不要假设或猜测参数的值。\n\n"

        "可用动作列表：\n"
        f"{numbered_subtasks}"
        "- 如果所需的动作不在列表中，你可以创建一个新的。确保新动作在其目的上非常具体，不仅仅是'点击'或'输入'某些内容。以下面结构化的json格式提供动作及其参数的详细描述。\n"
        '- {"name": <新动作名称>, "description": <新动作的详细描述>, "parameters": {<参数名称>: <参数的描述，包括可用选项列表>,...}}\n\n'

        "使用下面描述的JSON格式回应\n"
        "回应格式：\n"
        '{"reasoning": <基于过去事件和屏幕HTML代码的推理>, '
        '"new_action"(仅在你需要创建新动作时包含): {"name": <新动作名称。这不能是click或input>, "description": <新动作的详细描述>, "parameters": {<参数名称>: <参数的描述，包括可用选项>,...}},'
        '"action": {"name":<动作名称>, "parameters": {<参数名称>: <参数值，如果提示中没有明确提及参数值，只需写入"unknown">,...}},'
        '"completion_rate": <你完成任务的接近程度>, '
        '"speak": <用自然语言与用户交流的动作简要总结。保持简短。>}\n'
        "开始！"
    )
    return sys_msg


def get_usr_prompt(instruction, subtask_history, qa_history, screen, suggestions):
    if len(subtask_history) == 0:
        numbered_subtask_history = "0. 暂无事件。\n"
    else:
        numbered_subtask_history = generate_numbered_list(subtask_history)

    if len(qa_history) == 0:
        numbered_qa_history = "此时没有QA。"
    else:
        numbered_qa_history = generate_numbered_list(qa_history)

    usr_msg = ""
    if len(suggestions) > 0:
        error_action = suggestions['出错的动作']
        advice = suggestions['建议']
        usr_msg += (
            "***请注意***:\n"
            f"你之前选择的动作是：{error_action}。但是，在执行过程中遇到了错误。"
            f"在选择动作时请参考以下建议：{advice}\n\n"
        )
    usr_msg += (
        f"用户的请求：{instruction}\n\n"

        "QA列表：\n"
        "'''\n"
        f"{numbered_qa_history}"
        "'''\n\n"

        "过去事件：\n"
        "'''\n"
        f"{numbered_subtask_history}'''\n\n"

        "当前应用屏幕的HTML代码，由<screen> </screen>分隔：\n"
        f"<screen>{screen}</screen>\n\n"

        "建设性地自我评估你完成请求的接近程度。"
        "如果过去事件表明用户的请求已经完成，你必须选择'finish'动作。不要继续执行其他步骤。\n\n"
        "回应：\n"
    )

    return usr_msg


def get_prompts(instruction: str, available_subtasks: list, subtask_history: list, qa_history: list, screen: str, suggestions: list):
    sys_msg = get_sys_prompt(available_subtasks)
    usr_msg = get_usr_prompt(instruction, subtask_history, qa_history, screen, suggestions)
    messages = [{"role": "system", "content": sys_msg},
                {"role": "user", "content": usr_msg}]
    return messages
