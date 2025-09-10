# from datasets import load_dataset
#
# # 加载数据集
# dataset = load_dataset("Yukang/LongAlpaca-12k")
# # dataset = load_dataset("AI-ModelScope/LongAlpaca-12k", trust_remote_code=True)
# # 若想将数据集保存到指定位置，可使用 save_to_disk 方法
# dataset.save_to_disk("D:/倩倩/建行科创工作/大模型推理/")
from datasets import load_dataset

# 下载并加载 gsm8k 数据集
dataset = load_dataset("Yukang/LongAlpaca-12k", "main")

# 打印数据集信息
print(f"数据集名称: {dataset}")
print(f"训练集样本数: {len(dataset['train'])}")
print(f"测试集样本数: {len(dataset['test'])}")

# 可选：保存数据集到本地
dataset.save_to_disk("./gsm8k_dataset")

print("数据集下载完成！")