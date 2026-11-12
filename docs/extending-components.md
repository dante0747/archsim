# Adding a component type

A component type lives entirely in the engine. You write a config model, declare ports,
optionally add validation, implement runtime behaviour, and register it. The API serves it
through `GET /api/v1/components` and the web editor builds its palette entry and inspector
form from that catalog, so **no API or frontend changes are needed**.

Read [`simulation-model.md`](simulation-model.md) first; your component must keep request
accounting exact.

## Anatomy

```
ComponentModel (registry.py)          RuntimeComponent (runtime.py)
├─ type, display_name, category       ├─ handle(req) -> SimPy generator returning Outcome
├─ description, icon                  ├─ handle_on(req, i) / instance_count()  (optional)
├─ config_model  (Pydantic + ui())    ├─ self.m: NodeMetrics  (arrive/depart/reject/count)
├─ ports: tuple[PortSpec, ...]        ├─ self.ctx: SimContext (env, rng(), call(), edges)
├─ emitted_types(), validate()        └─ occupancy (gauge used for utilization)
├─ capacity_slots(), uses_keys()
├─ modeled / not_modeled  (shown in the Assumptions panel)
└─ build(ctx, node, config) -> RuntimeComponent
```

## Worked example: a fixed-delay component

This is adapted from the component in `test_custom_component_can_be_registered_and_simulated`
(`packages/engine/tests/test_schema_registry.py`). It adds a constant delay with unlimited
concurrency (think of a network hop or an external API with a fixed response time).

### 1. Config model

Subclass `CamelModel` (camelCase JSON, unknown keys rejected). Use `Field` constraints for
ranges and `ui(...)` for UI metadata:

```python
from pydantic import Field
from archsim_engine import ui
from archsim_engine.schema import CamelModel

class DelayConfig(CamelModel):
    delay_ms: float = Field(
        default=5.0,
        ge=0,
        le=60_000,
        description="Constant time added to every request (ms).",
        json_schema_extra=ui(label="Delay", unit="ms", group="Performance", assumption=True),
    )
```

`ui()` options:

| Option | Effect in the UI |
| --- | --- |
| `label` | Field label (default: humanised key). |
| `unit` | Shown next to the input; also used in the report's assumptions list. |
| `group` | Inspector section heading. |
| `assumption=True` | Marked as a user assumption in the inspector and listed under "assumptions" in every report. Use it for anything the user asserts rather than configures (times, ratios, latencies). |
| `headline=True` | Shown on the node card on the canvas. |
| `visible_when={"otherKey": value}` | Show the field only when another field has that value. |
| `step` | Input step for numbers. |
| `kind` | Override the detected widget kind (`number`, `integer`, `enum`, `boolean`, `distribution`, `requestTypes`). |

Field kinds are inferred from annotations: `int`, `float`, `bool`, `Literal[...]` (enum),
`float | None` (nullable number), and `Distribution` (a distribution editor). Defaults come
from the model, so every field needs a default.

### 2. Runtime behaviour

```python
from collections.abc import Generator
from typing import Any
import simpy
from archsim_engine.runtime import OK, Outcome, Request, RuntimeComponent

class DelayRuntime(RuntimeComponent):
    def handle(self, req: Request) -> Generator[simpy.Event, Any, Outcome]:
        self.m.arrive(self.env.now)                 # every arrival must be counted...
        yield self.env.timeout(self.config.delay_ms)
        self.m.depart(self.env.now, OK, req)        # ...and end in exactly one depart/reject
        return OK
```

Rules for `handle`:

- It is a SimPy generator: `yield` events (`env.timeout(...)`, pool requests, calls) and
  `return` an `Outcome`.
- Call `self.m.arrive(now)` once on entry, then exactly one of
  `self.m.depart(now, outcome, req)` or `self.m.reject(reason, now)`. This keeps the
  invariant `arrivals == completed + failedDownstream + rejected + inProgress`.
- Return `OK` on success, `rejected(reason, self.id)` when this component refuses work, or
  pass through a downstream failure unchanged so its origin is preserved.
- Use seeded randomness only: `rng = self.ctx.rng(f"node:{self.id}:something")`. Never use
  the global `random` module or wall-clock time.

### 3. The model and its ports

```python
from archsim_engine import ComponentModel, PortSpec
from archsim_engine.runtime import SimContext
from archsim_engine.schema import NodeSpec

class FixedDelayModel(ComponentModel):
    type = "fixed_delay"
    display_name = "Fixed delay"
    category = "network"            # traffic | network | compute | storage
    description = "Adds a constant delay."
    icon = "timer"                  # lucide icon name hint for the UI
    config_model = DelayConfig
    ports = (PortSpec("in", "in", "In"),)
    modeled = ("Constant delay per request, unlimited concurrency",)
    not_modeled = ("Queueing", "Failures")

    def build(self, ctx: SimContext, node: NodeSpec, config: Any) -> RuntimeComponent:
        return DelayRuntime(ctx, node, config, None)   # None = no slots, no utilization
```

`PortSpec(name, direction, label, description="", request_types=("read", "write"),
min_edges=0, max_edges=None)`:

- `direction="in"` ports accept links; `"out"` ports start them.
- `request_types` restricts what a port accepts or emits; the validator rejects links that
  carry other types (the cache's `miss` port uses `("read",)`).
- `min_edges` / `max_edges` are enforced with actionable errors.

### 4. Register it

Built-in components are listed in `BUILTIN_MODELS` in
`packages/engine/src/archsim_engine/components/__init__.py`. Add your class there and the
default registry (used by the API and CLI) includes it.

For an external package, expose the model through an entry point instead:

```toml
# your package's pyproject.toml
[project.entry-points."archsim.components"]
fixed_delay = "my_package.delay:FixedDelayModel"
```

Install the package into the API's environment and start the API with
`ARCHSIM_ENABLE_PLUGINS=1`. Entry points are only read from locally installed packages;
nothing can be uploaded through the API (see [SECURITY.md](../SECURITY.md)).

In tests, use a private registry so you do not depend on global state:

```python
registry = create_default_registry(include_plugins=False)
registry.register(FixedDelayModel())
report = run_simulation(document, registry=registry)
```

## Optional hooks

| Hook | Override when |
| --- | --- |
| `emitted_types(port, incoming, config, view)` | The types leaving an output port differ from the types arriving (default: same). The cache returns `{"read"}` for `miss`, or nothing if it can never miss. Used for routing validation. |
| `validate(node, config, view)` | You have rules beyond ports and field ranges. `view` gives nodes, parsed configs, in/out edges, the workload and `incoming_types` per node. Return `Issue`s via `error(...)`/`warning(...)` from `archsim_engine.issues`, with a hint. |
| `capacity_slots(config)` | The component has a fixed number of concurrent slots; used for utilization and `capacityRps`. |
| `uses_keys(config)` | Behaviour depends on request keys; turns on Zipf key sampling. |
| `RuntimeComponent.instance_count()`, `instance_load(i)`, `handle_on(req, i)` | The component has replicas a load balancer should spread across. |
| `RuntimeComponent.occupancy` | Utilization should come from a gauge other than busy slots (the load balancer uses in-flight requests). |

## Building blocks you can reuse

- **`WorkerPool(env, slots, queue_capacity, queue_timeout_ms, metrics)`** - slots with a
  bounded FIFO queue and wait timeout. `tok, reason = yield from pool.acquire()`; if `tok` is
  `None`, reject with `reason`; otherwise do the work and `pool.release(tok)`. It records queue
  depth, waits and busy slots for you.
- **`self.ctx.call(edge, req, caller_id)`** - call a downstream link honouring its latency,
  timeout and bounded retries. Get links with `self.out_edges("port_name")`.
- **`self.m.count(name, now)`** - custom counters (they appear in the report's `counters`).
- **`self.m.service.record(now, ms)` / `self.m.hold.record(now, ms)`** - feed
  `meanServiceMs` / `meanHoldMs` (and so `capacityRps`).
- `self.m.cpu.add(+1/-1, now)` with `self.m.has_cpu_split = True` - report own-work
  utilization separately (as services do).

## Technology presets

A component type can ship named technology profiles. They appear in the web app's technology
library, the inspector's technology picker and the command palette:

```python
from archsim_engine.registry import Preset

class FixedDelayModel(ComponentModel):
    ...
    presets = (
        Preset(
            id="slow-mainframe",
            name="Mainframe gateway",
            technology="generic",
            family="Legacy",
            description="Illustrative defaults: 120 ms per call. Calibrate before trusting.",
            config={"delayMs": 120},
            tags=("legacy",),
        ),
    )
```

`config` holds the values that differ from the defaults (camelCase, as in documents) and is
merged over `defaultConfig` when a preset is applied. Keep behaviour in config fields, never
in the preset name: a preset is a convenient, transparent starting point. The test suite
validates every preset of every registered model against its config model.

Preset numbers are illustrative unless `sources` links the documentation that states them
(`sources={"maxConnections": "https://..."}`, camelCase keys of `config`). Technology profiles
(`technology_profiles`, see `archsim_engine/technology.py`) work the same way: a profile value
is documented when one of its traits lists it in `covers` and has a `source`. Each profile has a
`tier`, `experimental` unless you set `core`; the web app badges experimental technologies.
After changing presets or profiles, regenerate the table of assumptions with
`uv run python scripts/gen_assumptions.py` (a test fails when `docs/assumptions.md` is out of
date).

Built-in examples: `database` (PostgreSQL, MongoDB sharded, Cassandra...), `service` (VM,
Docker, Kubernetes HPA, serverless), `message_queue` (Kafka, RabbitMQ...), `external_api`
(payments, LLM inference...). Their implementations are good references for asynchronous work
(`components/message_queue.py`: background consumers with their own counters and a
`background_busy()` hook so the run drains the backlog) and time-varying capacity
(`components/service.py`: autoscaling with `instances_version` so load balancers follow
replica changes).

Faults apply to your component for free if you sample processing times with
`self.sample_time(dist, rng)` (slowdown faults); outages are enforced before your `handle` is
called, and link latency faults are applied by `ctx.call`.

## Testing checklist

Put tests next to the engine tests (`packages/engine/tests/`):

- the catalog entry: fields, kinds, units, ports (`registry.catalog()`);
- validation: each new rule produces the expected `code`, node/edge id and a hint;
- behaviour: a small document exercising the component, asserting direction (more capacity ⇒
  more throughput, etc.), not exact numbers;
- accounting: reuse `assert_accounting(report)` from `test_engine_behaviour.py` so every node
  invariant holds and links match arrivals when the run drained;
- determinism: two runs with the same seed produce the same `resultDigest`.

The web unit tests use a copy of the component catalog. After adding or changing a component,
regenerate it with `./gradlew :archsim-engine:generate`; the engine test `GeneratedFileTests`
fails until you do.

If the component changes results for existing documents, bump the engine's version,
`EngineVersions.ENGINE` (see [CONTRIBUTING.md](../CONTRIBUTING.md#changing-simulation-semantics)).
