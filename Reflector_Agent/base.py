from dataclasses import dataclass
from typing import Optional


@dataclass
class AgentMemory:
    instruction: str
    errTYPE: str
    errMessage: str
    curXML: str
    preXML: str
    action: str


@dataclass
class Reflection:
    need_back: bool
    problem_type: int
    advice: Optional[str]
    summary: str
    
    @classmethod
    def from_dict(cls, data: dict) -> 'Reflection':
        """从字典创建Reflection对象"""
        return cls(
            need_back=data.get('need_back', False),
            problem_type=data.get('problem_type', 'back'),
            advice=data.get('advice'),
            summary=data.get('summary', '')
        )

