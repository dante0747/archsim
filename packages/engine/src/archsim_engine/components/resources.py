"""Resource specs shared by components: CPU cores, memory and storage per instance.

Every spec is optional. Left empty, it is not modelled and the component behaves exactly as it
did before specs existed. Set, it shows on the design and feeds the simulation where there is a
defensible, documented effect:

* CPU cores (services, databases, caches): CPU work waits for a free core when more handlers
  than cores want the CPU at once (CPU contention). See :class:`Cores`.
* Memory: per component (requests held by a service replica, a database's buffer pool, the
  keys a cache can hold); see each component.
* Storage (databases, queues): checked against the data size you enter.

Load balancers and queues carry the specs for the design and price estimates only.
"""

from __future__ import annotations

import math
from collections.abc import Generator
from typing import Any

import simpy
from pydantic import Field

from archsim_engine.metrics import Gauge
from archsim_engine.registry import ui

RESOURCES = "Resources"


def cpu_field(per: str, effect: str) -> Any:
    return Field(
        default=None,
        gt=0,
        le=512,
        description=f"vCPU per {per}. Empty = not modelled. {effect}",
        json_schema_extra=ui(label="CPU", unit="vCPU", group=RESOURCES, step=0.5),
    )


def memory_field(per: str, effect: str) -> Any:
    return Field(
        default=None,
        gt=0,
        le=24_576,
        description=f"Memory per {per} (GiB). Empty = not modelled. {effect}",
        json_schema_extra=ui(label="Memory", unit="GiB", group=RESOURCES, step=0.5),
    )


def storage_field(per: str, effect: str) -> Any:
    return Field(
        default=None,
        gt=0,
        le=1_000_000,
        description=f"Disk per {per} (GiB). Empty = not modelled. {effect}",
        json_schema_extra=ui(label="Storage", unit="GiB", group=RESOURCES),
    )


CPU_CONTENTION = (
    "When set, CPU work waits for a free core while more handlers than cores want the CPU "
    "(CPU contention); below 1 (e.g. 0.5) CPU work runs proportionally slower."
)
INFO_ONLY = "Shown on the design and used for price estimates; it does not change the simulation."


def executors(cores: float) -> tuple[int, float]:
    """Parallel CPU executors and their speed for ``cores`` vCPU (capacity-preserving):
    at least one executor, ``floor(cores)`` above one, each at ``cores / executors`` speed."""
    n = max(1, math.floor(cores + 1e-9))
    return n, cores / n


class Cores:
    """The CPU of one instance.

    With ``cores`` unset, or when there are at least as many whole cores as handlers that could
    run at once, :meth:`run` simply takes the work time (no extra events, same results as without
    specs). Otherwise CPU work queues for one of the executors.
    """

    __slots__ = ("busy", "env", "n", "pool", "speed")

    def __init__(
        self, env: simpy.Environment, cores: float | None, concurrency: int, busy: Gauge | None
    ) -> None:
        self.env = env
        self.busy = busy
        if cores is None:
            self.n, self.speed = concurrency, 1.0
            self.pool: simpy.Resource | None = None
            return
        self.n, self.speed = executors(cores)
        contended = self.n < concurrency or abs(self.speed - 1.0) > 1e-9
        self.pool = simpy.Resource(env, capacity=self.n) if contended else None

    def run(self, work_ms: float) -> Generator[simpy.Event, Any, None]:
        env = self.env
        if self.pool is None:
            if self.busy is not None:
                self.busy.add(1, env.now)
            if work_ms > 0:
                yield env.timeout(work_ms)
            if self.busy is not None:
                self.busy.add(-1, env.now)
            return
        req = self.pool.request()
        yield req
        if self.busy is not None:
            self.busy.add(1, env.now)
        d = work_ms / self.speed
        if d > 0:
            yield env.timeout(d)
        if self.busy is not None:
            self.busy.add(-1, env.now)
        self.pool.release(req)


def core_utilization(busy: Gauge | None, executors_total: float) -> float | None:
    """Mean share of CPU executors busy inside the measurement window."""
    if busy is None or executors_total <= 0:
        return None
    return round(busy.window_mean() / executors_total, 4)


# Illustrative on-demand prices used by the web UI's "price from size" hint live in the web app
# (lib/cost.ts); the engine never prices anything by itself.
