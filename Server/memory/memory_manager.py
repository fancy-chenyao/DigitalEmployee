import json
import os
from collections import defaultdict
from typing import Dict

import numpy as np
import pandas as pd

from agents import param_fill_agent, subtask_merge_agent
from memory.page_manager import PageManager
from memory.node_manager import NodeManager
from utils import parsing_utils
from env_config import Config
from utils.mongo_utils import check_connection
from utils.action_utils import generalize_action
from utils.utils import get_openai_embedding, log, safe_literal_eval, cosine_similarity
from utils.mongo_utils import load_dataframe, save_dataframe
from utils.local_store import write_dataframe_csv, read_dataframe_csv
from utils.local_store import write_dataframe_csv


def init_database(path: str, headers: list, use_cache: bool = True):
    # 当 DB 关闭时，优先从本地 CSV 读取；否则走 Mongo 集合
    if not Config.ENABLE_DB:
        return read_dataframe_csv(path, headers)
    return load_dataframe(path, headers, use_cache=use_cache)


class Memory:
    def __init__(self, instruction: str, task_name: str):

        self.instruction = instruction
        self.task_name = task_name
        self.curr_action_step = 0

        # 使用 MongoDB 集合作为持久化目标，不再使用本地文件系统
        self.task_db_path = "tasks"
        self.page_path = "pages"
        self.screen_hierarchy_path = "hierarchy"
        self.screens_path = "screens"  # 用于存储屏幕截图和XML文件

        task_header = ['name', 'path']
        page_header = ['index', 'available_subtasks', 'trigger_uis', 'extra_uis', "screen"]
        hierarchy_header = ['index', 'screen', 'embedding']

        log(f"📊 内存初始化: 任务='{task_name}', 指令='{instruction[:50]}...'", "blue")

        # 使用缓存优化数据库查询（本地模式按任务维度读取CSV）
        if not Config.ENABLE_DB:
            self.task_db = read_dataframe_csv(self.task_db_path, task_header, task_name=self.task_name)
        else:
            self.task_db = init_database(self.task_db_path, task_header, use_cache=True)
        log(f"📊 任务数据库加载: 任务数量={len(self.task_db)}", "cyan")
        
        if not Config.ENABLE_DB:
            self.page_db = read_dataframe_csv(self.page_path, page_header, task_name=self.task_name)
        else:
            self.page_db = init_database(self.page_path, page_header, use_cache=True)
        self.page_db.set_index('index', drop=False, inplace=True)
        log(f"📊 页面数据库加载: 页面数量={len(self.page_db)}", "cyan")
        
        if not Config.ENABLE_DB:
            self.hierarchy_db = read_dataframe_csv(self.screen_hierarchy_path, hierarchy_header, task_name=self.task_name)
        else:
            self.hierarchy_db = init_database(self.screen_hierarchy_path, hierarchy_header, use_cache=True)
        self.hierarchy_db['embedding'] = self.hierarchy_db.embedding.apply(safe_literal_eval)
        log(f"📊 层级数据库加载: 层级数量={len(self.hierarchy_db)}", "cyan")
        
        self.task_path = self.__get_task_data(self.task_name)
        if self.task_path:
            log(f"🔥 热启动: 找到任务历史路径，页面数量={len(self.task_path)}", "green")
        else:
            log(f"❄️ 冷启动: 无任务历史路径，将学习新流程", "yellow")
            
        self.page_managers: Dict[int, PageManager] = {}
        self.page_manager = None
        self._cache_dirty = False  # 缓存脏标记

    def init_page_manager(self, page_index: int):
        if page_index not in self.page_managers:
            self.page_managers[page_index] = PageManager(self.task_name, page_index)

        self.page_manager = self.page_managers[page_index]

    def search_node(self, parsed_xml, hierarchy_xml, encoded_xml) -> (int, list):
        # candidate_nodes_indexes = self.__search_similar_hierarchy_nodes(hierarchy_xml)
        #
        # node_manager = NodeManager(self.page_db, self, parsed_xml, encoded_xml)
        # node_index, new_subtasks = node_manager.search(candidate_nodes_indexes)
        log(f"🔍 页面匹配检查: 历史页面数量={len(self.hierarchy_db)}", "blue")
        most_similar_node_index = self.__search_most_similar_hierarchy_node(hierarchy_xml)
        if most_similar_node_index >= 0:
            log(f"🔥 热启动: 页面匹配成功，页面索引={most_similar_node_index}", "green")
            return most_similar_node_index, []
        else:
            log(f"❄️ 冷启动: 未找到匹配的历史页面，将探索新界面", "yellow")
            return -1, []

    def get_available_subtasks(self, page_index):
        return self.page_managers[page_index].get_available_subtasks()

    def add_new_action(self, new_action, page_index):
        page_manager = self.page_managers[page_index]
        # 1) 写入 available_subtasks.csv（已有逻辑）
        page_manager.add_new_action(new_action)
        # 2) 同步写入最小示例到 subtasks.csv（若不存在）
        try:
            subtask_raw = {
                "name": new_action.get("name", "unknown"),
                "description": new_action.get("description", ""),
                "parameters": new_action.get("parameters", {})
            }
            page_manager.save_subtask(subtask_raw, example={})
        except Exception:
            pass
        # 3) 移除基础动作模板保存，只保留具体执行动作

    def search_node_by_hierarchy(self, parsed_xml, hierarchy_xml, encoded_xml) -> (int, list):
        # 1. First search for at most 5 candidate nodes based only on the hierarchy of the screen
        most_similar_node_index = self.__search_most_similar_hierarchy_node(hierarchy_xml)

        if most_similar_node_index >= 0:
            page_data = json.loads(self.page_db.loc[most_similar_node_index].to_json())
            available_subtasks = json.loads(page_data['available_subtasks'])
            return most_similar_node_index, available_subtasks
        else:
            return -1, []

    def add_node(self, available_subtasks: list, trigger_uis: dict, extra_uis: list, screen: str, screen_num=None) -> int:
        new_index = len(self.page_db)
        new_row = {'index': new_index, 'available_subtasks': json.dumps(available_subtasks),
                   'trigger_uis': json.dumps(trigger_uis),
                   'extra_uis': json.dumps(extra_uis), "screen": screen}
        # 将更新后的页面信息保存到 MongoDB 集合
        self.page_db = pd.concat([self.page_db, pd.DataFrame([new_row])], ignore_index=True)
        save_dataframe(self.page_path, self.page_db)
        write_dataframe_csv(self.page_path, self.page_db, task_name=self.task_name)

        # 根据配置与连通性：优先写入数据库；不可用时不写DB，保留本地文件
        try:
            if Config.ENABLE_DB and check_connection():
                parsing_utils.save_screen_info_to_mongo(self.task_name, new_index, screen_num)
            else:
                # 本地保存（与 Server_origin 对齐）：memory/log/<task>/pages/<index>/screen/
                try:
                    parsing_utils.save_screen_info_local_aligned(self.task_name, new_index, screen_num)
                except Exception:
                    pass
        except Exception:
            # 任何异常都不阻断主流程
            pass

        return new_index

    def update_node(self, page_index, new_available_subtasks: list, new_trigger_uis: dict, new_extra_uis: list,
                    new_screen: str):
        page_data = json.loads(self.page_db.loc[page_index].to_json())
        page_data = {key: json.loads(value) if key in ['available_subtasks', 'trigger_uis', 'extra_uis'] else value for
                     key, value in page_data.items()}

        # merge old and new infos
        merged_available_subtasks = page_data['available_subtasks'] + new_available_subtasks
        merged_trigger_uis = {}
        merged_trigger_uis.update(page_data['trigger_uis'])
        merged_trigger_uis.update(new_trigger_uis)
        merged_extra_uis = page_data['extra_uis'] + new_extra_uis

        updated_row = {'index': page_index, 'available_subtasks': json.dumps(merged_available_subtasks),
                       'trigger_uis': json.dumps(merged_trigger_uis),
                       'extra_uis': json.dumps(merged_extra_uis), "screen": new_screen}

        self.page_db.loc[page_index] = updated_row
        save_dataframe(self.page_path, self.page_db)
        write_dataframe_csv(self.page_path, self.page_db, task_name=self.task_name)

        # available_subtasks 的持久化由 PageManager 负责到 MongoDB，不再写 CSV

    def add_hierarchy_xml(self, screen, page_index):
        #  生成界面XML的嵌入向量
        embedding = get_openai_embedding(screen)
        # 构造层级数据（页面索引、XML、嵌入向量）
        new_screen_hierarchy = {'index': page_index, 'screen': screen, 'embedding': str(embedding)}
        # 写入界面层级库并重新加载（确保后续匹配可用）
        if not Config.ENABLE_DB:
            hierarchy_db = read_dataframe_csv(self.screen_hierarchy_path, ['index', 'screen', 'embedding'], task_name=self.task_name)
        else:
            hierarchy_db = init_database(self.screen_hierarchy_path, ['index', 'screen', 'embedding'])
        
        # 若该 page_index 已存在，则更新；否则追加，保证“每个新页面一行”
        if not hierarchy_db.empty and 'index' in hierarchy_db.columns and page_index in set(hierarchy_db['index'].tolist()):
            try:
                mask = (hierarchy_db['index'] == page_index)
                hierarchy_db.loc[mask, 'screen'] = new_screen_hierarchy['screen']
                hierarchy_db.loc[mask, 'embedding'] = new_screen_hierarchy['embedding']
            except Exception:
                hierarchy_db = pd.concat([hierarchy_db, pd.DataFrame([new_screen_hierarchy])], ignore_index=True)
        else:
            hierarchy_db = pd.concat([hierarchy_db, pd.DataFrame([new_screen_hierarchy])], ignore_index=True)
        
        save_dataframe(self.screen_hierarchy_path, hierarchy_db)
        write_dataframe_csv(self.screen_hierarchy_path, hierarchy_db, task_name=self.task_name)

        if not Config.ENABLE_DB:
            self.hierarchy_db = read_dataframe_csv(self.screen_hierarchy_path, ['index', 'screen', 'embedding'], task_name=self.task_name)
        else:
            self.hierarchy_db = init_database(self.screen_hierarchy_path, ['index', 'screen', 'embedding'])
        self.hierarchy_db['embedding'] = self.hierarchy_db.embedding.apply(safe_literal_eval)

    def get_next_subtask(self, page_index, qa_history, screen):
        # Initialize action step
        self.curr_action_step = 0
        # 调用应用级task.csv
        candidate_subtasks = self.task_path.get(page_index, [])
        next_subtask_name = None
        # 遍历候选子任务，找到第一个“未执行”（traversed = False）的子任务
        for subtask in candidate_subtasks:
            if not subtask.get("traversed", False):
                next_subtask_name = subtask.get("name")
                subtask['traversed'] = True # 标记为“已执行”，避免重复选择
                break
        # 处理特殊子任务（结束、滑动）
        if next_subtask_name == 'finish':
            finish_subtask = {"name": "finish",
                              "description": "Use this to signal that the task has been completed",
                              "parameters": {}
                              }
            return finish_subtask
        # elif next_subtask_name == "scroll_screen":
        #     scroll_subtask = {"name": "scroll_screen", "parameters": {"scroll_ui_index": 1, "direction": 'down'}}
        #     return scroll_subtask
        # 若找到子任务，填充参数（调用param_fill_agent，结合问答历史）,调用subtasks.csv
        if next_subtask_name:
            next_subtask_data = self.page_manager.get_next_subtask_data(next_subtask_name)

            raw_params = next_subtask_data.get('parameters', {})
            params: dict = {}
            if isinstance(raw_params, dict):
                params = raw_params
            elif isinstance(raw_params, str):
                try:
                    # 兼容 '"{}"'、'' 等情况
                    if raw_params.strip() == '' or raw_params.strip().strip('"') == '{}':
                        params = {}
                    else:
                        params = json.loads(raw_params)
                except Exception:
                    params = {}
            else:
                params = {}

            next_subtask = {'name': next_subtask_data.get('name', next_subtask_name), 'description': next_subtask_data.get('description', ''),
                            'parameters': params}
            # 若子任务有参数，调用param_fill_agent填充参数（结合用户指令、问答历史、界面）
            if len(next_subtask['parameters']) > 0:
                params = param_fill_agent.parm_fill_subtask(instruction=self.instruction,
                                                            subtask=next_subtask,
                                                            qa_history=qa_history,
                                                            screen=screen,
                                                            example=json.loads(
                                                                next_subtask_data.get('example', {})))

                next_subtask['parameters'] = params

            return next_subtask

        return None

    def save_subtask(self, subtask_raw: dict, example: dict) -> None:
        self.page_manager.save_subtask(subtask_raw, example)

    def get_next_action(self, subtask: dict, screen: str) -> dict:
        next_action = self.page_manager.get_next_action(subtask, screen, self.curr_action_step)
        self.curr_action_step += 1
        log(f":::DERIVE:::", "blue")
        return next_action

    def save_action(self, subtask: dict, action: dict, example=None) -> None:
        if action['name'] == 'finish':
            self.curr_action_step += 1
        self.page_manager.save_action(subtask, self.curr_action_step, action, example)

    def merge_subtasks(self, task_path: list) -> list:
        # Remove finish subtask at the end
        finish_subtask = task_path.pop()

        # Initialize list of subtasks performed.
        raw_subtask_list = []
        for subtask_data in task_path:
            page_index = subtask_data['page_index']
            subtask_name = subtask_data['subtask_name']
            page_data = json.loads(self.page_db.loc[page_index].to_json())
            available_subtasks = json.loads(page_data['available_subtasks'])
            for subtask_available in available_subtasks:
                if subtask_available['name'] == subtask_name:
                    raw_subtask_list.append(subtask_available)

        merged_subtask_list = subtask_merge_agent.merge_subtasks(raw_subtask_list)

        merged_task_path = self.__merge_subtasks_data(task_path, merged_subtask_list)
        # Add Finish subtask at the end back in
        merged_task_path.append(finish_subtask)

        return merged_task_path

    def save_task(self, task_path: list) -> None:
        # 遍历 task_path 中的每个子任务（每个子任务包含多个动作）
        for subtask in task_path:
            subtask_name = subtask['subtask_name'] # 子任务所属页面索引
            subtask_dict = subtask['subtask'] # 子任务名
            actions = subtask['actions'] # 子任务包含的所有动作
            step = 0 # 动作步骤计数器（标记是子任务的第几步动作）
            # 遍历当前子任务的每个动作
            for action_data in actions:
                page_index = action_data['page_index']  # 动作执行时的页面索引
                action = action_data['action']  # 具体动作
                screen = action_data['screen']  # 动作执行时的界面XML
                example = action_data['example']  # 动作示例（可选，用于后续复用参考）

                # 关键判断：仅保存"结束动作"或"带示例的动作"（这些动作更具复用价值）
                if action['name'] == 'finish' or example:
                    #  泛化动作：去除界面依赖的具体值（如将固定坐标转为相对位置）
                    # 例如：将{"coordinates":[800,900]}转为{"coordinates":"send_button_position"}
                    generalized_action = generalize_action(action, subtask_dict, screen)
                    page_manager = self.page_managers[page_index]
                    # 调用页面管理器保存泛化后的动作（写入页面专属的actions.csv）
                    page_manager.save_action(subtask_name, step, generalized_action, example)
                step += 1

        known_task_path = {
            key: [item["name"] for item in value]
            for key, value in self.task_path.items()
        }

        for subtask in task_path:
            page_index = subtask['page_index']
            subtask_name = subtask['subtask_name']
            if page_index in known_task_path:
                if subtask_name not in known_task_path[page_index]:
                    known_task_path[page_index].append(subtask_name)
            else:
                known_task_path[page_index] = [subtask_name]

        # 合并后的任务路径持久化到tasks.csv，完成整个任务的 “记忆存储”
        # 构造新的任务数据（含任务名和JSON格式的路径）
        new_task_path = {
            'name': self.task_name,
            'path': json.dumps(known_task_path)
        }
        # 判断任务是否已存在于全局任务库（tasks.csv）
        condition = (self.task_db['name'] == new_task_path['name'])
        if condition.any():
            self.task_db.loc[condition] = pd.DataFrame([new_task_path])
        else:
            self.task_db = pd.concat([self.task_db, pd.DataFrame([new_task_path])], ignore_index=True)
        # 将更新后的任务库写入 MongoDB
        save_dataframe(self.task_db_path, self.task_db)
        write_dataframe_csv(self.task_db_path, self.task_db, task_name=self.task_name)
        log(f":::TASK SAVE::: Path saved: {new_task_path}")

    def save_task_path(self, new_task_path: dict):
        for page_index, subtasks in new_task_path.items():
            if page_index in self.task_path:
                self.task_path[page_index].extend(subtasks)
            else:
                self.task_path[page_index] = subtasks[:]

        new_task_data = {
            'name': self.task_name,
            'path': json.dumps(self.task_path)
        }

        condition = (self.task_db['name'] == new_task_data['name'])
        if condition.any():
            for column in new_task_path.keys():
                self.task_db.loc[condition, column] = new_task_path[column]
        else:
            self.task_db = pd.concat([self.task_db, pd.DataFrame([new_task_data])], ignore_index=True)

        save_dataframe(self.task_db_path, self.task_db)

    def __get_task_data(self, task_name):
        # Search for the task
        matched_tasks = self.task_db[(self.task_db['name'] == task_name)]
        if matched_tasks.empty:
            log(f"❄️ 冷启动: 任务 '{task_name}' 在数据库中不存在", "yellow")
            return {}
        else:
            task_data = matched_tasks.iloc[0].to_dict()
            path = json.loads(task_data['path'])

            task_path = {}
            for page_index, subtasks in path.items():
                subtasks_data = []
                for subtask in subtasks:
                    subtasks_data.append({"name": subtask, "traversed": False})
                task_path[int(page_index)] = subtasks_data

            log(f"🔥 热启动: 找到任务 '{task_name}' 的历史路径", "green")
            log(f"📊 任务路径详情: {task_path}", "cyan")

            return task_path

    def __search_similar_hierarchy_nodes(self, hierarchy) -> list:
        new_hierarchy_vector = np.array(get_openai_embedding(hierarchy))
        self.hierarchy_db["similarity"] = self.hierarchy_db.embedding.apply(
            lambda x: cosine_similarity(x, new_hierarchy_vector))

        # get top apps with the highest similarity
        candidates = self.hierarchy_db.sort_values('similarity', ascending=False).head(5).to_dict(orient='records')
        candidate_node_indexes = []
        for node in candidates:
            candidate_node_indexes.append(node['index'])

        return candidate_node_indexes

    def __search_most_similar_hierarchy_node(self, hierarchy) -> int:
        new_hierarchy_vector = np.array(get_openai_embedding(hierarchy))
        self.hierarchy_db["similarity"] = self.hierarchy_db.embedding.apply(
            lambda x: cosine_similarity(x, new_hierarchy_vector))

        # get top apps with the highest similarity
        candidates = self.hierarchy_db.sort_values('similarity', ascending=False).head(5).to_dict(orient='records')
        if candidates:
            highest_similarity = candidates[0]['similarity']
            log(f"📊 相似度计算: 最高相似度={highest_similarity:.4f}, 阈值=0.97", "cyan")
            if highest_similarity > 0.97:
                log(f"✅ 页面匹配成功: 页面索引={candidates[0]['index']}, 相似度={highest_similarity:.4f}", "green")
                return candidates[0]['index']
            else:
                log(f"❌ 页面匹配失败: 相似度{highest_similarity:.4f}低于阈值0.97", "yellow")
        else:
            log("❌ 页面匹配失败: 无历史页面数据", "yellow")
        return -1

    def __merge_subtasks_data(self, original_subtasks_data, merged_subtasks) -> list:
        len_diff = len(original_subtasks_data) - len(merged_subtasks)
        for i in range(0, len_diff):
            merged_subtasks.append({"name": "dummy"})

        original_pointer = 0
        merged_pointer = 0
        while original_pointer < len(original_subtasks_data):
            curr_subtask_data = original_subtasks_data[original_pointer]
            curr_subtask_name = curr_subtask_data['subtask_name']
            curr_subtask_actions = curr_subtask_data['actions']

            merged_subtask_dict = merged_subtasks[merged_pointer]
            if merged_subtask_dict['name'] == curr_subtask_name:
                page_index = curr_subtask_data['page_index']
                page_data = json.loads(self.page_db.loc[page_index].to_json())
                available_subtasks = json.loads(page_data['available_subtasks'])
                # Loop through the available subtasks list and replace the subtask with the new one.
                for i in range(len(available_subtasks)):
                    if available_subtasks[i]['name'] == curr_subtask_name:
                        available_subtasks[i] = merged_subtask_dict

                page_data['available_subtasks'] = json.dumps(available_subtasks)
                self.page_db.loc[page_index] = page_data
                save_dataframe(self.page_path, self.page_db)
                write_dataframe_csv(self.page_path, self.page_db, task_name=self.task_name)

                self.page_managers[page_index].update_subtask_info(merged_subtask_dict)

                merged_subtask_params = merged_subtask_dict['parameters']
                curr_subtask_params = curr_subtask_data['subtask']['parameters']
                for param_name, _ in merged_subtask_params.items():
                    if param_name not in curr_subtask_params:
                        curr_subtask_params[param_name] = None

                original_pointer += 1
                merged_pointer += 1
            else:
                base_subtask_data = original_subtasks_data[original_pointer - 1]
                base_subtask_actions = base_subtask_data['actions']

                base_subtask_params = base_subtask_data['subtask']['parameters']
                curr_subtask_params = curr_subtask_data['subtask']['parameters']
                for param_name, param_value in base_subtask_params.items():
                    if param_value is None and param_name in curr_subtask_params:
                        base_subtask_params[param_name] = curr_subtask_params[param_name]

                base_subtask_actions.pop()

                merged_actions = base_subtask_actions + curr_subtask_actions
                base_subtask_data['actions'] = merged_actions

                original_subtasks_data.pop(original_pointer)

        return original_subtasks_data
    def delete_subtask(self, substask_name):
        self.page_manager.delete_subtask(substask_name)