import asyncio
from concurrent.futures import ThreadPoolExecutor
from typing import Any, Callable, List, Tuple

from .utils import query


class ParallelAIExecutor:
    """Utility for optional parallel AI calls without changing agent contracts."""

    def __init__(self, max_workers: int = 5):
        self.executor = ThreadPoolExecutor(max_workers=max_workers)

    async def run_parallel(self, call_specs: List[Tuple[Callable[..., Any], tuple, dict]]):
        """
        Run multiple callables in parallel.
        call_specs: list of (callable, args, kwargs)
        Returns results preserving order.
        """

        loop = asyncio.get_event_loop()

        def submit(spec):
            func, args, kwargs = spec
            return loop.run_in_executor(self.executor, func, *args, **kwargs)

        tasks = [submit(spec) for spec in call_specs]
        results = await asyncio.gather(*tasks, return_exceptions=True)
        return results


async def parallel_query(message_model_pairs: List[Tuple[list, str]], is_list_flags: List[bool] = None):
    """Run multiple utils.query calls in parallel.

    message_model_pairs: list of (messages, model)
    is_list_flags: optional list of is_list flags per call
    """
    executor = ParallelAIExecutor()

    specs = []
    for idx, pair in enumerate(message_model_pairs):
        messages, model = pair
        is_list = is_list_flags[idx] if is_list_flags and idx < len(is_list_flags) else False
        specs.append((query, (messages,), {"model": model, "is_list": is_list}))

    results = await executor.run_parallel(specs)
    return results


