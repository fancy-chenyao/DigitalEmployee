import os, sys
from dotenv import load_dotenv
from server import Server

# os.chdir('./MobileGPT_server')
sys.path.append('.')

load_dotenv()
os.environ["TASK_AGENT_GPT_VERSION"] = "gpt-4o"
os.environ["SELECT_AGENT_HISTORY_GPT_VERSION"] = "gpt-4o"
os.environ["EXPLORE_AGENT_GPT_VERSION"] = "gpt-4o"
os.environ["SELECT_AGENT_GPT_VERSION"] = "gpt-4-turbo"
os.environ["DERIVE_AGENT_GPT_VERSION"] = "gpt-4-turbo"
os.environ["PARAMETER_FILLER_AGENT_GPT_VERSION"] = "gpt-4o"
os.environ["ACTION_SUMMARIZE_AGENT_GPT_VERSION"] = "gpt-4o"
os.environ["SUBTASK_MERGE_AGENT_GPT_VERSION"] = "gpt-4o"

os.environ["gpt_4"] = "gpt-4"
os.environ["gpt_4_turbo"] = "gpt-4-turbo"
os.environ["gpt_3_5_turbo"] = "gpt-3.5-turbo"

os.environ["vision_model"] = "gpt-4o"
# 应用无关模式：不再需要固定应用配置
for key in ["TARGET_APP_NAME", "TARGET_APP_PACKAGE", "APP_AGENT_GPT_VERSION"]:
    if key in os.environ:
        os.environ.pop(key)
os.environ["MOBILEGPT_USER_NAME"] = "user"


def main():
    server_ip = "0.0.0.0"
    server_port = 12345
    server_vision = False

    mobilGPT_server = Server(host=server_ip, port=int(server_port), buffer_size=4096)
    mobilGPT_server.open()

    # mobilGPT_explorer = Explorer(host=server_ip, port=int(server_port), buffer_size=4096)
    # mobilGPT_explorer.open()


if __name__ == '__main__':
    main()
