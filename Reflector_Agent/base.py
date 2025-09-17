from dataclasses import dataclass


@dataclass
class AgentMemory:
    instruction: str
    errTYPE: str
    errMessage: str
    curXML: str
    preXML: str
    action: str


