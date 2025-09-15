from utils.utils import generate_numbered_list
# 是 TaskAgent 的提示词模板文件，用于生成结构化提示词，指导大语言模型将用户指令解析为规范的任务定义（API 格式），并匹配历史任务库

# 系统提示生成，指导大语言模型判断用户指令是否匹配已知的 API（任务定义），若不匹配则生成新的 API，并严格约束输出格式。
def get_sys_prompt():
    sys_msg = (
        "Given the user instruction, check if it matches any of the known APIs. "
        "If there's no match, suggest a new API.\n\n"

        "**Guidelines on how to find a matching API:**\n"
        "1. An API is a match if it covers all the steps "
        "required for the given user instruction.\n"
        "2. An API does NOT match if the user instruction requires additional steps "
        "beyond what the API description provides.\n\n"

        "**Guidelines on how to generate a new API:**\n"
        "Break down the user instruction into a api name and parameters "
        "combination. The combination should CLEARLY REPRESENT all phrases in the instruction.\n\n"

        "Respond using the JSON format below. Ensure the response can be parsed by Python json.loads:\n"
        '{"reasoning":<reasoning>, "found_match": <True or False>,  "api": {"name":<matched_api_name. Suggest new api if there is no match>, "description": <description of what the api intends to do>, "parameters":{"<parameter_name>":<parameter description>,...} }}'
    )
    return sys_msg


# 用户提示生成，通过具体示例引导大语言模型理解如何根据用户指令匹配已知 API 或生成新 API
def get_usr_prompt(instruction: str, known_tasks: list):
    numbered_known_tasks = generate_numbered_list(known_tasks)
    usr_msg = (
        "[Example #1]:\n"
        "User instruction: 'find me an asian restaurant in Las Vegas'\n\n"

        "List of known APIs:\n"
        '1. {"name":"findRestaurantsByLocation", "description": "find restaurants in a specific location.", "parameters":{"location":"The location to search in"}}\n'
        "...(truncated for brevity)...\n\n"

        "Response:\n"
        '{"reasoning":...(truncated for brevity)..., "found_match": "False",  "api": {"name":"findRestaurantsByCuisineAndLocation", "description": "find restaurants in a specific location based on the type of cuisine", "parameters":{"cuisine_type":"The type of cuisine to search for", "location":"The location to search in"}}}\n'
        "[END Example #1]\n\n"

        # "[Example #2]:\n"
        # "User instruction: 'find me an Mexican restaurant in Washington'\n\n"
        #     
        # "List of known APIs:\n"
        # '1. {"name":"findRestaurantsByLocation", "description": "find restaurants in a specific location.", "parameters":{"location":"The location to search in"}, "app": "unknown"}\n'
        # '2. {"name":"findRestaurantsByCuisineAndLocation", "description": "find restaurants in a specific location based on the type of cuisine", "parameters":{"cuisine_type":"The type of cuisine to search for", "location":"The location to search in"}, "app": "unknown"}\n'
        # "...(truncated for brevity)...\n\n"
        # 
        # "Response:\n"
        # '{"reasoning":...(truncated for brevity)..., "found_match": "True",  "name":"findRestaurantsByCuisineAndLocation", "description": "find restaurants in a specific location based on the type of cuisine", "parameters":{"cuisine_type":"The type of cuisine to search for", "location":"The location to search in"}, "app": "unknown"}\n'
        # "[END Example #2]\n\n"

        "[Example #2]:\n"
        "User instruction: 'send message to Bob saying hello'\n\n"

        "List of known APIs:\n"
        '1. {"name":"sendMessage", "description": "send message to a recipient", "parameters":{"recipient":"recipient of the message"}}\n'
        "...(truncated for brevity)...\n\n"

        "Response:\n"
        '{"reasoning":...(truncated for brevity)..., "found_match": "True",  "api": {"name":"sendMessage", "description": "send message to a recipient", "parameters":{"recipient":"recipient of the message", "message":"content of the message"}}}\n'
        "[END Example #2]\n\n"

        "[Your Turn]\n"
        f"User instruction: '{instruction}'\n\n"

        "List of known APIs:\n"
        f"{numbered_known_tasks}\n\n"

        "Response:\n"
    )
    return usr_msg

# 组合提示词
def get_prompts(instruction: str, known_tasks: list):
    sys_msg = get_sys_prompt()
    usr_msg = get_usr_prompt(instruction, known_tasks)
    messages = [{"role": "system", "content": sys_msg},
                {"role": "user", "content": usr_msg}]
    return messages
