import os, sys
from dotenv import load_dotenv
from server import Server

# os.chdir('./MobileGPT_server')
sys.path.append('.')

load_dotenv()
os.environ["TASK_AGENT_GPT_VERSION"] = "qwen3-32b"#修改这里
os.environ["SELECT_AGENT_HISTORY_GPT_VERSION"] = "qwen3-32b"
os.environ["EXPLORE_AGENT_GPT_VERSION"] = "qwen3-32b"
os.environ["SELECT_AGENT_GPT_VERSION"] = "qwen3-32b"
os.environ["DERIVE_AGENT_GPT_VERSION"] = "qwen3-32b"
os.environ["PARAMETER_FILLER_AGENT_GPT_VERSION"] = "qwen3-32b"
os.environ["ACTION_SUMMARIZE_AGENT_GPT_VERSION"] = "qwen3-32b"
os.environ["SUBTASK_MERGE_AGENT_GPT_VERSION"] = "qwen3-32b"

os.environ["gpt_4"] = "qwen3-32b"
os.environ["gpt_4_turbo"] = "qwen3-32b"
os.environ["gpt_3_5_turbo"] = "qwen3-32b"

os.environ["vision_model"] = "gpt-4o"
# 应用无关模式：不再需要固定应用配置
for key in ["TARGET_APP_NAME", "TARGET_APP_PACKAGE", "APP_AGENT_GPT_VERSION"]:
    if key in os.environ:
        os.environ.pop(key)
os.environ["MOBILEGPT_USER_NAME"] = "user"


def main():
    server_ip = "0.0.0.0" #监听所有网络接口，允许外部设备连接。
    server_port = 12345 #服务端监听的端口号。
    server_vision = False

    mobilGPT_server = Server(host=server_ip, port=int(server_port), buffer_size=4096) #4096：每次通信最多接收4096字节数据。
    mobilGPT_server.open()

    # mobilGPT_explorer = Explorer(host=server_ip, port=int(server_port), buffer_size=4096) #用于 探索模式（Explorer）
    # mobilGPT_explorer.open()


if __name__ == '__main__':
    main()
