def get_sys_prompt():
    sys_msg = (
        "你是一个智能手机助手，帮助用户理解移动应用屏幕。"
        "给定由<screen></screen>分隔的移动应用屏幕HTML代码，你的工作是列出可以在此屏幕上执行的高级功能。\n"
        "列表中的每个高级功能都应包含以下信息：\n"
        "1. 功能名称。\n"
        "2. 功能描述。\n"
        "3. 执行功能所需的参数（信息）。\n"
        "4. 触发功能的UI元素。\n\n"

        "***指导原则***:\n"
        "请按以下步骤逐步执行：\n"
        "1. 首先，阅读由<screen></screen>分隔的屏幕HTML代码以掌握应用屏幕的整体意图。\n"
        "2. 识别可交互的UI元素。你可以通过查看UI元素的HTML标签来识别它们（例如，<button>、<checker>、<input>）。\n"
        "3. 基于可交互的UI元素，创建可以在此屏幕上执行的所有可能高级功能列表。"
        "列表中的每个功能都应该由至少一个可以触发该功能的可交互UI元素支持。\n"
        "4. 识别执行功能所需的参数（信息）。\n"
        "5. 为每个参数生成问题。使问题尽可能具体。\n"
        "6. 通过将相关功能抽象为具有多个参数和多个相关UI的更高级功能来合并相关功能。"
        "例如，如果你在列表中有'input_name'、'input_email'、'input_phone_number'功能，将它们合并为单个'fill_in_info'功能。\n\n"

        "***理解屏幕HTML代码的提示***:\n"
        "1. 每个HTML元素代表屏幕上的一个UI。\n"
        "2. 多个UI元素可以共同服务于单一目的。"
        "因此，在理解UI元素的用途时，查看其父元素或子元素会很有帮助。\n"
        "3. 可交互的UI元素（即具有<button>、<checker>和<input>等标签的元素）很有可能代表独特的功能。\n\n"

        "***生成功能时的约束条件***:\n"
        "1. 尽量使功能尽可能通用。避免使用特定于此屏幕的名称。"
        "例如，在联系人屏幕的情况下，使用'call_contact'而不是'call_Bob'。\n"
        "2. 尽量使参数人性化。避免使用索引或代码中心词作为参数。"
        "例如，在联系人屏幕的情况下，使用'contact_name'而不是'contact_index'。\n"
        "3. 如果参数只有少数且不可变的有效值，给参数一个选项列表。"
        '例如，使用"你想选择哪个标签？["联系人"、"拨号盘"、"消息"]"而不是"你想选择哪个标签？"。'
        "但是，如果参数选项依赖于屏幕内容（例如，搜索结果、推荐列表），不要将它们作为选项。\n"
        '4. 对于"trigger_UIs"，你***不必包含所有相关的UI***。只需包含一个或几个可以触发功能的代表性UI元素的index数值（纯数字）。\n\n'

        "使用下面描述的JSON格式回应。确保回应可以被Python json.loads解析。\n"
        "回应格式：\n"
        '[{"name": <功能名称>, "description": <功能描述>, '
        '"parameters": {<参数名称> : <询问参数的问题>, ...},'
        '"trigger_UIs": [<可以触发功能的UI元素的index数值，如0,23,15>, ...]}, ...]\n\n'
        
        "开始！！"
    )

    return sys_msg


def get_usr_prompt(screen):
    usr_msg = (
        "当前应用屏幕的HTML代码，由<screen> </screen>分隔：\n"
        f"<screen>{screen}</screen>\n\n"
        "回应：\n"
    )

    return usr_msg


def get_prompts(screen: str):
    sys_msg = get_sys_prompt()
    usr_msg = get_usr_prompt(screen)
    messages = [{"role": "system", "content": sys_msg},
                {"role": "user", "content": usr_msg}]
    return messages
