import json

from utils.utils import generate_numbered_list


def get_usr_prompt(action_history):
    # Remove completion rate from the list of actions. This confuses GPT.
    for action in action_history:
        del action['completion_rate']
    numbered_history = generate_numbered_list(action_history)

    usr_msg = (
        "我将给你一个命令列表和每个命令背后的推理。用一个短语总结通过这个命令列表实现了什么。"
        "保持简短和人性化（以动词开始句子，避免使用代码特定的细节）。"
        "我只需要一个短语来总结整个命令列表。该短语应包括命令（步骤）的总结、目的（原因）、下一步计划，以及其他重要细节，如失败（如果有的话）。\n\n"
        "建议用于开始短语的动词：{选择了，点击了，输入了，打开了，完成了，显示了}\n\n"
                
        "示例回应：\n"
        "打开了导航菜单。建议下一步计划：查看更多菜单\n"
        "点击了新群组选项。建议下一步计划：创建新群组\n"
        "点击了搜索按钮。建议下一步计划：搜索联系人。\n\n"
        
        "要总结的命令列表：\n"
        f"{numbered_history}\n\n"

        "回应：\n"
    )
    return usr_msg


def get_prompts(action_history: list):
    usr_msg = get_usr_prompt(action_history)
    messages = [
                {"role": "user", "content": usr_msg}]
    return messages
