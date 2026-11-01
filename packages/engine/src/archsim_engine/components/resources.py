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

from archsim_engine.components.host import HostRuntime, private_machine
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


def instance_type_field(per: str) -> Any:
    return Field(
        default=None,
        pattern=r"^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$",
        description=(
            f"Instance type of each {per} in the design's price snapshot (e.g. c6g.xlarge). It is "
            "used while it covers the CPU and memory; empty, or too small: the cheapest type that "
            "covers them. Auto-sizing records the type it chose in a run's input, so running that "
            "input again gives the same types and cost."
        ),
        json_schema_extra=ui(label="Instance type", group=RESOURCES),
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


SIBLING_SPEED_HELP = (
    "With 2 or more vCPU: the vCPUs are hyperthreads, two per physical core (as on most cloud "
    "instances), and a vCPU whose core's other vCPU is busy too runs at this speed, so a busy "
    "pair does twice this much work (about 0.6 on Intel and AMD cores: 1.2 cores' work). Empty: "
    "every vCPU is a full core (as before; right for Graviton and other cores without "
    "hyperthreads). Ignored when the design sets a host machine (architecture.host), whose "
    "sibling speed applies instead."
)


def sibling_speed_field() -> Any:
    return Field(
        default=None,
        gt=0,
        le=1,
        # left out of the JSON when empty, so documents and reports read as before
        exclude_if=lambda v: v is None,
        description=SIBLING_SPEED_HELP,
        json_schema_extra=ui(label="Hyperthread sibling speed", group=RESOURCES, step=0.05),
    )


class Cores:
    """The CPU of one instance.

    With ``cores`` unset, or when there are at least as many whole cores as handlers that could
    run at once, :meth:`run` simply takes the work time (no extra events, same results as without
    specs). Otherwise CPU work queues for one of the executors. With a ``machine`` (the design's
    host, or the instance's own hyperthreads; ``components/host.py``) each piece of work runs at
    the machine's thread speed when it starts.
    """

    __slots__ = ("busy", "env", "machine", "n", "pool", "speed")

    def __init__(
        self,
        env: simpy.Environment,
        cores: float | None,
        concurrency: int,
        busy: Gauge | None,
        machine: HostRuntime | None = None,
    ) -> None:
        self.env = env
        self.busy = busy
        self.machine = machine
        if cores is None:
            self.n, self.speed = concurrency, 1.0
            self.pool: simpy.Resource | None = None
            return
        self.n, self.speed = executors(cores)
        contended = self.n < concurrency or abs(self.speed - 1.0) > 1e-9
        self.pool = simpy.Resource(env, capacity=self.n) if contended else None

    def run(self, work_ms: float) -> Generator[simpy.Event, Any, None]:
        env = self.env
        machine = self.machine
        if self.pool is None:
            if self.busy is not None:
                self.busy.add(1, env.now)
            d = machine.start(work_ms) if machine is not None else work_ms
            if d > 0:
                yield env.timeout(d)
            if machine is not None:
                machine.stop()
            if self.busy is not None:
                self.busy.add(-1, env.now)
            return
        req = self.pool.request()
        yield req
        if self.busy is not None:
            self.busy.add(1, env.now)
        d = work_ms / self.speed
        if machine is not None:
            d = machine.start(d)
        if d > 0:
            yield env.timeout(d)
        if machine is not None:
            machine.stop()
        if self.busy is not None:
            self.busy.add(-1, env.now)
        self.pool.release(req)


def machine_for(ctx: Any, cores: float | None, sibling_speed: float | None) -> HostRuntime | None:
    """The machine one instance's CPU work runs on: the design's host (shared by every part
    with a CPU size), else the instance's own hyperthreads (``siblingSpeed``), else None (every
    vCPU a full core, as before schema 1.7)."""
    if cores is None:
        return None
    shared = ctx.shared.get("host")
    if shared is not None:
        return shared
    own = private_machine(cores, sibling_speed)
    return HostRuntime(ctx.env, own) if own is not None else None


def busy_cores_series(busy: Gauge, cores: Cores) -> list[float | None]:
    """CPU cores busy in each time bucket, over all the part's instances: busy executors times their
    speed (a fraction of a core below one vCPU)."""
    return [v * cores.speed for v in busy.mean_series()]


def core_utilization(busy: Gauge | None, executors_total: float) -> float | None:
    """Mean share of CPU executors busy inside the measurement window."""
    if busy is None or executors_total <= 0:
        return None
    return round(busy.window_mean() / executors_total, 4)


# Illustrative on-demand prices used by the web UI's "price from size" hint live in the web app
# (lib/cost.ts); the engine never prices anything by itself.
