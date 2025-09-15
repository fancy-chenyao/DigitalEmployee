import shutil
from datetime import datetime
import json
import os
import re
import xml.etree.ElementTree as ET

from utils.utils import log


def find_parent_node(root, child_index: int) -> (int, ET):
    """
    Finds the parent element of the child with a specific index value.

    Parameters:
    - root: The root element of the XML tree.
    - child_index: The index of the child element.

    Returns:
    - The parent element of the found child, or None if not found.
    """
    if isinstance(child_index, str):
        log("index is String!!!!!", "red")
        child_index = int(child_index)
    for parent in root.iter():
        for rank, child in enumerate(parent):
            if int(child.get("index")) == child_index:
                return rank, parent
    return 0, None


def find_children_with_attributes(element, depth=1):
    """
    Recursively finds children with 'text' or 'description' attributes up to a depth of 3.

    Parameters:
    - element: The current element to search within.
    - depth: The current depth in the tree.

    Returns:
    - A list of tuples, each containing (child, rank, depth) for valid children.
    """
    valid_children = []
    if depth > 3:  # Base case: if depth exceeds 3, stop the recursion.
        return valid_children

    for rank, child in enumerate(element, start=0):
        # Check if child has the 'text' or 'description' attribute
        if child.text is not None or 'description' in child.attrib:
            valid_children.append((child, depth, rank))
        # Recurse to find valid children of the current child, increasing the depth
        valid_children.extend(find_children_with_attributes(child, depth + 1))

    return valid_children


def match_conditions(node, condition):
    """Check if a node matches the given condition."""
    for key, value in condition.items():
        if value == 'NONE':
            continue
        if key == 'tag':
            if node.tag != value:
                return False
        elif key == 'class_name':
            if node.attrib.get('class', 'NONE') != value:
                return False
        elif key == 'text':
            text = node.text
            if text is None:
                text = node.attrib.get('text', 'NONE')
            if text != value:
                return False
        else:
            if node.attrib.get(key, 'NONE') != value:
                return False
    return True


def find_matching_node(tree: ET, requirements):
    """Find a node in the tree that matches specific requirements."""
    matched_nodes = []

    def check_node(node, depth=0, cur_parent=None):
        if not match_conditions(node, requirements['self']):
            return None

        if cur_parent and not match_conditions(cur_parent, requirements['parent']):
            return None

        children_requirements = requirements.get('children', [])

        matched_children = []
        for child_cond, child_depth, child_rank in children_requirements:
            children = find_children_by_depth_and_rank(node, child_depth, child_rank)
            for child in children:
                if match_conditions(child, child_cond):
                    if child not in matched_children:
                        matched_children.append(child)
                        break

        if len(matched_children) != len(children_requirements):
            return None
        return node

    def find_children_by_depth_and_rank(element, target_depth, target_rank, current_depth=1):
        matched_elements = []

        if current_depth == target_depth:
            try:
                matched_elements.append(element[target_rank])
            except IndexError:
                pass
        else:
            for child in element:
                matched_elements.extend(
                    find_children_by_depth_and_rank(child, target_depth, target_rank, current_depth + 1))

        return matched_elements

    for node in tree.iter():
        _, parent = find_parent_node(tree, int(node.get("index")))
        result = check_node(node, cur_parent=parent)
        if result is not None:
            matched_nodes.append(result)
    return matched_nodes


def get_trigger_ui_attributes(trigger_ui_indexes: dict, screen: str) -> dict:
    trigger_ui_data = {}
    for subtask_name, ui_indexes in trigger_ui_indexes.items():
        trigger_uis_attributes = []
        for ui_index in ui_indexes:
            ui_attributes = get_ui_key_attrib(int(ui_index), screen)

            skip = False
            new_self_attribute_str = json.dumps(ui_attributes['self'], sort_keys=True)
            for ui_attribute in trigger_uis_attributes:
                existing_self_attribute = json.dumps(ui_attribute['self'], sort_keys=True)
                if new_self_attribute_str == existing_self_attribute:
                    skip = True
            if not skip:
                trigger_uis_attributes.append(ui_attributes)

        trigger_ui_data[subtask_name] = trigger_uis_attributes

    return trigger_ui_data


def get_extra_ui_attributes(trigger_ui_indexes: list, screen: str):
    tree = ET.fromstring(screen)

    extra_ui_indexes = []
    for tag in ['input', 'button', 'checker']:
        for node in tree.findall(f".//{tag}"):
            index = int(node.attrib['index'])
            if index not in trigger_ui_indexes:
                extra_ui_indexes.append(index)

    extra_ui_attributes = []
    for index in extra_ui_indexes:
        ui_attributes = get_ui_key_attrib(index, screen)
        extra_ui_attributes.append(ui_attributes)
    return extra_ui_attributes


def get_ui_key_attrib(ui_index: int, screen: str, include_desc=True) -> dict:
    tree = ET.fromstring(screen)
    """
    [ ({"index": <ui index>}, <depth>, <rank>), ...]
    """

    node = tree.find(f".//*[@index='{ui_index}']")

    its_attributes = {'tag': node.tag, 'id': node.attrib.get('id', 'NONE'),
                      'class': node.attrib.get('class', 'NONE')}
    if include_desc:
        its_attributes['description'] = node.attrib.get('description', 'NONE')

    _, parent_node = find_parent_node(tree, ui_index)
    parent_attributes = {}
    if parent_node:
        parent_attributes = {'tag': parent_node.tag, 'id': parent_node.attrib.get('id', 'NONE'),
                             'class': parent_node.attrib.get('class', 'NONE')}
        if include_desc:
            parent_attributes['description'] = parent_node.attrib.get('description', 'NONE')

    children = find_children_with_attributes(node)

    children_attributes_str = []
    for child in children:
        child_node, depth, rank = child
        child_attribute = {'tag': child_node.tag, 'id': child_node.attrib.get('id', 'NONE'),
                           'class': child_node.attrib.get('class', 'NONE')}
        if include_desc:
            child_attribute['description'] = child_node.attrib.get('description', 'NONE')

        child_attribute_str = json.dumps((child_attribute, depth, rank))
        if child_attribute_str not in children_attributes_str:
            children_attributes_str.append(child_attribute_str)

    children_attributes = [json.loads(child_attribute_str) for child_attribute_str in children_attributes_str]
    return {"self": its_attributes, "parent": parent_attributes, "children": children_attributes}


def get_children_with_depth_and_rank(element, depth=1) -> (ET, int, int):
    children_info = []
    for rank, child in enumerate(element, start=1):
        children_info.append((child, depth, rank))
        children_info.extend(get_children_with_depth_and_rank(child, depth + 1))
    return children_info


def get_siblings_with_rank(root, element):
    parent_map = {c: p for p in root.iter() for c in p}
    parent = parent_map.get(element)
    if parent is None:
        return []

    siblings_with_rank = []
    rank = 1
    for child in parent:
        if child != element:
            siblings_with_rank.append((child, rank))
        rank += 1
    return siblings_with_rank


def shrink_screen_xml(screen: str, target_ui_index: int, range_around: int = 10):
    original_tree = ET.fromstring(screen)
    # Create the lower and upper bounds
    lower_bound = target_ui_index - range_around
    upper_bound = target_ui_index + range_around

    new_tree = ET.Element(original_tree.tag, original_tree.attrib)

    def copy_within_range(source_node, dest_node):
        dest_node.text = source_node.text
        dest_node.tail = source_node.tail

        for child in source_node:
            index = int(child.get("index", 0))

            if lower_bound <= index <= upper_bound:
                new_child = ET.SubElement(dest_node, child.tag, child.attrib)
                copy_within_range(child, new_child)
            else:
                if any(lower_bound <= int(desc.get("index", 0)) <= upper_bound for desc in child.iter()):
                    new_child = ET.SubElement(dest_node, child.tag, child.attrib)
                    copy_within_range(child, new_child)

    copy_within_range(original_tree, new_tree)

    shrunk_xml = ET.tostring(new_tree, encoding="utf-8").decode("utf-8")
    return shrunk_xml


def find_elements_with_specific_child_depth_and_rank(root, depth, rank):
    matching_elements = []

    for elem in root.iter():
        if has_descendant_at_depth_and_rank(elem, depth, rank):
            matching_elements.append(elem)

    return matching_elements


def has_descendant_at_depth_and_rank(element, depth, rank):
    if depth == 1:
        return len(element) >= rank
    else:
        for child in element:
            if has_descendant_at_depth_and_rank(child, depth - 1, rank):
                return True
    return False


def find_element_by_depth_and_rank(element, target_depth, rank, current_depth=1):
    if current_depth == target_depth:
        try:
            return element[rank - 1]  # Indexing is 0-based, hence rank-1
        except IndexError:
            return None

    for child in element:
        result = find_element_by_depth_and_rank(child, target_depth, rank, current_depth + 1)
        if result is not None:
            return result

    return None


def save_screen_info(app_name: str, task_name: str, dest_dir: str, screen_num=None) -> None:
    def parse_datetime(dirname):
        return datetime.strptime(dirname, "%Y_%m_%d_%H-%M-%S")

    def get_index(filename):
        base = os.path.basename(filename)
        index = int(base.split('.')[0])
        return index

    base_path = f'memory/log/{app_name}/{task_name}/'

    directories = next(os.walk(base_path))[1]

    # 仅选择符合时间戳格式的目录，忽略其他名称（如 autostart 等）
    def is_timestamp_dir(name: str) -> bool:
        try:
            datetime.strptime(name, "%Y_%m_%d_%H-%M-%S")
            return True
        except Exception:
            return False

    datetime_directories = [(parse_datetime(dir), dir) for dir in directories if is_timestamp_dir(dir)]

    datetime_directories.sort(reverse=True)  # Newest first

    most_recent_log_dir = datetime_directories[0][1]
    most_recent_screenshot_path = os.path.join(base_path, most_recent_log_dir, "screenshots")
    most_recent_xml_path = os.path.join(base_path, most_recent_log_dir, "xmls")

    files = [f for f in os.listdir(most_recent_screenshot_path) if f.endswith('.jpg')]
    indices = [get_index(file) for file in files]
    if screen_num is not None:
        index = screen_num
    else:
        index = max(indices) if indices else None  # Check if the list is not empty

    shutil.copy(os.path.join(most_recent_screenshot_path, f"{index}.jpg"), os.path.join(dest_dir, "screenshot.jpg"))
    shutil.copy(os.path.join(most_recent_xml_path, f"{index}.xml"), os.path.join(dest_dir, "raw.xml"))
    shutil.copy(os.path.join(most_recent_xml_path, f"{index}_encoded.xml"), os.path.join(dest_dir, "html.xml"))
    shutil.copy(os.path.join(most_recent_xml_path, f"{index}_hierarchy_parsed.xml"),
                os.path.join(dest_dir, "hierarchy.xml"))
    shutil.copy(os.path.join(most_recent_xml_path, f"{index}_parsed.xml"), os.path.join(dest_dir, "parsed.xml"))
    shutil.copy(os.path.join(most_recent_xml_path, f"{index}_pretty.xml"), os.path.join(dest_dir, "pretty.xml"))


def save_screen_info_to_mongo(task_name: str, page_index: int, screen_num=None) -> None:
    """
    将屏幕信息保存到MongoDB而不是本地文件系统
    优先从MongoDB临时存储中读取数据，如果不存在则从本地文件系统读取
    """
    import base64
    from utils.mongo_utils import get_db
    
    db = get_db()
    
    # 首先尝试从MongoDB临时存储中获取数据
    temp_screenshots = db['temp_screenshots']
    temp_xmls = db['temp_xmls']
    
    # 查找对应的屏幕截图
    screenshot_query = {'task_name': task_name}
    if screen_num is not None:
        screenshot_query['screen_count'] = screen_num
    
    screenshot_doc = temp_screenshots.find_one(screenshot_query)
    
    # 查找对应的XML数据
    xml_types = ['raw', 'parsed', 'hierarchy', 'encoded']
    xml_data = {}
    
    for xml_type in xml_types:
        xml_query = {'task_name': task_name, 'xml_type': xml_type}
        if screen_num is not None:
            xml_query['screen_count'] = screen_num
        xml_doc = temp_xmls.find_one(xml_query)
        if xml_doc:
            xml_data[xml_type] = xml_doc['xml_content']
    
    # 如果MongoDB中有数据，直接使用
    if screenshot_doc and xml_data:
        screen_data = {
            'page_index': page_index,
            'task_name': task_name,
            'screen_num': screenshot_doc.get('screen_count', screen_num),
            'screenshot': screenshot_doc['screenshot'],
            'raw_xml': xml_data.get('raw', ''),
            'html_xml': xml_data.get('encoded', ''),
            'hierarchy_xml': xml_data.get('hierarchy', ''),
            'parsed_xml': xml_data.get('parsed', ''),
            'pretty_xml': xml_data.get('parsed', ''),  # 使用parsed作为pretty的替代
            'created_at': datetime.now()
        }
    else:
        # 如果MongoDB中没有数据，回退到本地文件系统
        log(f"Data not found in MongoDB, falling back to local filesystem", "yellow")
        return save_screen_info_from_local_files(task_name, page_index, screen_num)
    
    # 保存到MongoDB
    collection = db['screens']
    collection.replace_one(
        {'page_index': page_index, 'task_name': task_name},
        screen_data,
        upsert=True
    )
    
    log(f"Screen info saved to MongoDB for page {page_index}", "green")


def save_screen_info_from_local_files(task_name: str, page_index: int, screen_num=None) -> None:
    """
    从本地文件系统读取屏幕信息并保存到MongoDB（备用方法）
    """
    import base64
    from utils.mongo_utils import get_db
    
    def parse_datetime(dirname):
        return datetime.strptime(dirname, "%Y_%m_%d_%H-%M-%S")

    def get_index(filename):
        base = os.path.basename(filename)
        index = int(base.split('.')[0])
        return index

    def is_timestamp_dir(name: str) -> bool:
        try:
            datetime.strptime(name, "%Y_%m_%d_%H-%M-%S")
            return True
        except Exception:
            return False

    # Pick a valid base path with timestamped subdirectories
    def pick_base_path() -> str:
        candidates = []
        # app 维度已移除
        if task_name:
            candidates.append(os.path.join('memory', 'log', 'session', task_name))
        candidates.append(os.path.join('memory', 'log', 'session'))

        for path in candidates:
            if os.path.isdir(path):
                try:
                    dirs = next(os.walk(path))[1]
                except StopIteration:
                    continue
                ts_dirs = [d for d in dirs if is_timestamp_dir(d)]
                if len(ts_dirs) > 0:
                    return path
        return ''

    base_path = pick_base_path()
    if not base_path:
        log(f"Local filesystem fallback failed: no valid log directory for task='{task_name}'", "red")
        return

    try:
        directories = next(os.walk(base_path))[1]
    except StopIteration:
        log(f"Local filesystem fallback failed: empty directory '{base_path}'", "red")
        return

    datetime_directories = [(parse_datetime(dir), dir) for dir in directories if is_timestamp_dir(dir)]
    if len(datetime_directories) == 0:
        log(f"Local filesystem fallback failed: no timestamped subdirectories in '{base_path}'", "red")
        return

    datetime_directories.sort(reverse=True)  # Newest first

    most_recent_log_dir = datetime_directories[0][1]
    most_recent_screenshot_path = os.path.join(base_path, most_recent_log_dir, "screenshots")
    most_recent_xml_path = os.path.join(base_path, most_recent_log_dir, "xmls")

    files = [f for f in os.listdir(most_recent_screenshot_path) if f.endswith('.jpg')]
    indices = [get_index(file) for file in files]
    if screen_num is not None:
        index = screen_num
    else:
        index = max(indices) if indices else None  # Check if the list is not empty
    if index is None:
        log(f"Local filesystem fallback failed: no screenshots found in '{most_recent_screenshot_path}'", "red")
        return

    # 读取文件并转换为base64编码
    def read_file_as_base64(file_path):
        with open(file_path, 'rb') as f:
            return base64.b64encode(f.read()).decode('utf-8')
    
    def read_file_as_text(file_path):
        with open(file_path, 'r', encoding='utf-8') as f:
            return f.read()

    # 准备要保存的数据
    screen_data = {
        'page_index': page_index,
        'task_name': task_name,
        'screen_num': index,
        'screenshot': read_file_as_base64(os.path.join(most_recent_screenshot_path, f"{index}.jpg")),
        'raw_xml': read_file_as_text(os.path.join(most_recent_xml_path, f"{index}.xml")),
        'html_xml': read_file_as_text(os.path.join(most_recent_xml_path, f"{index}_encoded.xml")),
        'hierarchy_xml': read_file_as_text(os.path.join(most_recent_xml_path, f"{index}_hierarchy_parsed.xml")),
        'parsed_xml': read_file_as_text(os.path.join(most_recent_xml_path, f"{index}_parsed.xml")),
        'pretty_xml': read_file_as_text(os.path.join(most_recent_xml_path, f"{index}_pretty.xml")),
        'created_at': datetime.now()
    }
    
    # 保存到MongoDB
    db = get_db()
    collection = db['screens']
    collection.replace_one({'page_index': page_index, 'task_name': task_name}, screen_data, upsert=True)
    
    log(f"Screen info saved to MongoDB from local files for page {page_index}", "green")


def get_screen_info_from_mongo(page_index: int, task_name: str = None):
    """
    从MongoDB获取屏幕信息
    """
    from utils.mongo_utils import get_db
    
    db = get_db()
    collection = db['screens']
    
    query = {'page_index': page_index}
    if task_name:
        query['task_name'] = task_name
    
    screen_data = collection.find_one(query)
    return screen_data


def get_screenshot_from_mongo(page_index: int, task_name: str = None):
    """
    从MongoDB获取屏幕截图（返回base64解码后的二进制数据）
    """
    import base64
    
    screen_data = get_screen_info_from_mongo(page_index, task_name)
    if screen_data and 'screenshot' in screen_data:
        return base64.b64decode(screen_data['screenshot'])
    return None


def get_xml_from_mongo(page_index: int, xml_type: str, task_name: str = None):
    """
    从MongoDB获取XML数据
    xml_type: 'raw', 'html', 'hierarchy', 'parsed', 'pretty'
    """
    screen_data = get_screen_info_from_mongo(page_index, task_name)
    if screen_data:
        xml_field = f"{xml_type}_xml"
        return screen_data.get(xml_field)
    return None
