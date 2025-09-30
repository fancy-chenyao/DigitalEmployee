from utils.utils import generate_numbered_list
# 是 TaskAgent 的提示词模板文件，用于生成结构化提示词，指导大语言模型将用户指令解析为规范的任务定义（API 格式），并匹配历史任务库

# 系统提示生成，指导大语言模型判断用户指令是否匹配已知的 API（任务定义），若不匹配则生成新的 API，并严格约束输出格式。
def get_sys_prompt():
    sys_msg = (
        "根据用户指令，检查是否与任何已知的API匹配。"
        "如果没有匹配项，建议一个新的API。\n\n"

        "**如何找到匹配API的指导原则：**\n"
        "1. 如果API涵盖了给定用户指令所需的所有步骤，则API匹配。\n"
        "2. 如果用户指令需要超出API描述提供的额外步骤，则API不匹配。\n\n"

        "**如何生成新API的指导原则：**\n"
        "将用户指令分解为API名称和参数的组合。该组合应该清楚地表示指令中的所有短语。\n\n"

        "使用下面的JSON格式回应。确保回应可以被Python json.loads解析：\n"
        '{"reasoning":<推理过程>, "found_match": <True或False>,  "api": {"name":<匹配的API名称。如果没有匹配则建议新API>, "description": <API意图的描述>, "parameters":{"<参数名称>":<参数描述>,...} }}'
    )
    return sys_msg


# 用户提示生成，通过具体示例引导大语言模型理解如何根据用户指令匹配已知 API 或生成新 API
def get_usr_prompt(instruction: str, known_tasks: list):
    numbered_known_tasks = generate_numbered_list(known_tasks)
    usr_msg = (
        "[示例 #1]:\n"
        "用户指令：'在拉斯维加斯找一家亚洲餐厅'\n\n"

        "已知API列表：\n"
        '1. {"name":"findRestaurantsByLocation", "description": "在特定位置查找餐厅", "parameters":{"location":"要搜索的位置"}}\n'
        "...(为简洁起见省略)...\n\n"

        "回应：\n"
        '{"reasoning":...(为简洁起见省略)..., "found_match": "False",  "api": {"name":"findRestaurantsByCuisineAndLocation", "description": "根据菜系类型在特定位置查找餐厅", "parameters":{"cuisine_type":"要搜索的菜系类型", "location":"要搜索的位置"}}}\n'
        "[示例 #1 结束]\n\n"

        # "[示例 #2]:\n"
        # "用户指令：'在华盛顿找一家墨西哥餐厅'\n\n"
        #     
        # "已知API列表：\n"
        # '1. {"name":"findRestaurantsByLocation", "description": "在特定位置查找餐厅", "parameters":{"location":"要搜索的位置"}, "app": "unknown"}\n'
        # '2. {"name":"findRestaurantsByCuisineAndLocation", "description": "根据菜系类型在特定位置查找餐厅", "parameters":{"cuisine_type":"要搜索的菜系类型", "location":"要搜索的位置"}, "app": "unknown"}\n'
        # "...(为简洁起见省略)...\n\n"
        # 
        # "回应：\n"
        # '{"reasoning":...(为简洁起见省略)..., "found_match": "True",  "name":"findRestaurantsByCuisineAndLocation", "description": "根据菜系类型在特定位置查找餐厅", "parameters":{"cuisine_type":"要搜索的菜系类型", "location":"要搜索的位置"}, "app": "unknown"}\n'
        # "[示例 #2 结束]\n\n"

        "[示例 #2]:\n"
        "用户指令：'给Bob发消息说你好'\n\n"

        "已知API列表：\n"
        '1. {"name":"sendMessage", "description": "向收件人发送消息", "parameters":{"recipient":"消息的收件人"}}\n'
        "...(为简洁起见省略)...\n\n"

        "回应：\n"
        '{"reasoning":...(为简洁起见省略)..., "found_match": "True",  "api": {"name":"sendMessage", "description": "向收件人发送消息", "parameters":{"recipient":"消息的收件人", "message":"消息内容"}}}\n'
        "[示例 #2 结束]\n\n"

        "[轮到你了]\n"
        f"用户指令：'{instruction}'\n\n"

        "已知API列表：\n"
        f"{numbered_known_tasks}\n\n"

        "回应：\n"
    )
    return usr_msg

# 组合提示词
def get_prompts(instruction: str, known_tasks: list):
    sys_msg = get_sys_prompt()
    usr_msg = get_usr_prompt(instruction, known_tasks)
    messages = [{"role": "system", "content": sys_msg},
                {"role": "user", "content": usr_msg}]
    return messages
