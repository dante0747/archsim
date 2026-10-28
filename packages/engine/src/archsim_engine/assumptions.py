"""Every number ArchSim uses without the user typing it, and where each comes from.

``render()`` writes ``docs/assumptions.md`` (``scripts/gen_assumptions.py``; a test fails when the
committed file is out of date). It reads the values from the code that uses them: technology
profiles (``technology.py``), presets and part defaults (the component models), and the
engine-wide constants of the sizing, cost and component models. Each value is marked
**documented** (with the link that states it) or **illustrative** (a typical value ArchSim chose).
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any
from urllib.parse import urlparse

from archsim_engine import cost, pricing, sizing, validation
from archsim_engine.components import cache as cache_model
from archsim_engine.components import create_default_registry
from archsim_engine.components import database as database_model
from archsim_engine.registry import ComponentModel, ComponentRegistry, describe_fields
from archsim_engine.schema import EdgeConfig, Workload
from archsim_engine.technology import TIER_NOTE, _camel
from archsim_engine.version import ENGINE_VERSION

_DYNAMO_PARTITIONS = (
    "https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/bp-partition-key-design.html"
)
_DYNAMO_ON_DEMAND = (
    "https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/"
    "on-demand-capacity-mode.html#on-demand-capacity-mode-initial"
)
_DYNAMO_BURST = (
    "https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/burst-adaptive-capacity.html"
)
_KAFKA_RETENTION = "https://kafka.apache.org/documentation/#brokerconfigs_log.retention.hours"
_LAMBDA_MEMORY = "https://docs.aws.amazon.com/lambda/latest/dg/configuration-memory.html"
_RDS_CONNECTIONS = "https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/CHAP_Limits.html#RDS_Limits.MaxConnections"
_T4G_BASELINE = (
    "https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/burstable-credits-baseline-concepts.html"
)


@dataclass(frozen=True)
class Constant:
    """An engine-wide number, with where it lives and its basis."""

    area: str
    name: str
    value: str
    code: str
    source: str | None = None


def _pct(v: float) -> str:
    return f"{v * 100:g}%"


def constants() -> list[Constant]:
    """The engine-wide constants behind sizing, prices and component models."""
    s, c, db, ch, p = sizing, cost, database_model, cache_model, pricing
    return [
        Constant("Auto-sizing", "CPU utilisation a part is sized for at its peak", _pct(s.TARGET_CPU), "sizing.TARGET_CPU"),
        Constant("Auto-sizing", "Memory headroom over what the model needs", "+" + _pct(s.MEMORY_HEADROOM - 1), "sizing.MEMORY_HEADROOM"),
        Constant("Auto-sizing", "Storage kept under this fill level", _pct(s.STORAGE_FILL), "sizing.STORAGE_FILL"),
        Constant("Auto-sizing", "Smallest auto storage", f"{s.MIN_STORAGE_GIB:g} GiB", "sizing.MIN_STORAGE_GIB"),
        Constant("Auto-sizing", "Largest auto memory of one service replica", f"{s.MAX_SERVICE_MEMORY_GIB} GiB", "sizing.MAX_SERVICE_MEMORY_GIB"),
        Constant("Auto-sizing", "Load balancer throughput per vCPU", f"{s.PROXY_RPS_PER_VCPU:,} req/s", "sizing.PROXY_RPS_PER_VCPU"),
        Constant("Auto-sizing", "Load balancer memory per vCPU", f"{s.PROXY_GIB_PER_VCPU} GiB", "sizing.PROXY_GIB_PER_VCPU"),
        Constant("Auto-sizing", "Broker throughput per vCPU", f"{s.BROKER_MSGS_PER_VCPU:,} messages/s", "sizing.BROKER_MSGS_PER_VCPU"),
        Constant("Auto-sizing", "Broker memory per vCPU", f"{s.BROKER_GIB_PER_VCPU} GiB", "sizing.BROKER_GIB_PER_VCPU"),
        Constant("Auto-sizing", "Share of a database's data read often, when no hot working set is set", _pct(s.DB_HOT_SHARE), "sizing.DB_HOT_SHARE"),
        Constant("Auto-sizing", "Smallest auto memory of a database instance", f"{s.DB_MIN_MEMORY_GIB:g} GiB", "sizing.DB_MIN_MEMORY_GIB"),
        Constant("Auto-sizing", "Database memory per connection", f"{s.DB_BYTES_PER_CONNECTION:,} bytes", "sizing.DB_BYTES_PER_CONNECTION", _RDS_CONNECTIONS),
        Constant("Auto-sizing", "A burstable type's CPU at the base load, at most", f"{_pct(p.BURST_BASE_SHARE)} of its baseline", "pricing.BURST_BASE_SHARE", _T4G_BASELINE),
        Constant("Auto-sizing", "A burstable type's CPU at the peak, at most", f"{_pct(p.BURST_PEAK_SHARE)} of its vCPUs", "pricing.BURST_PEAK_SHARE"),
        Constant("Auto-sizing", "Memory of a VM's operating system", f"{s.OS_MEMORY_GIB:g} GiB", "sizing.OS_MEMORY_GIB"),
        Constant("Auto-sizing", "Serverless function memory per vCPU", f"{s.LAMBDA_MB_PER_VCPU:,} MB", "sizing.LAMBDA_MB_PER_VCPU", _LAMBDA_MEMORY),
        Constant("Auto-sizing", "Backlog a delete-on-ack broker keeps room for", f"{s.BACKLOG_S // 60:g} minutes of peak publishes", "sizing.BACKLOG_S"),
        Constant("Auto-sizing", "Log retention of Kafka-style brokers", f"{s.LOG_RETENTION_S // 3600:g} hours", "sizing.LOG_RETENTION_S", _KAFKA_RETENTION),
        Constant("Cost estimate", "Hours in a month", f"{c.HOURS_PER_MONTH}", "cost.HOURS_PER_MONTH"),
        Constant("Cost estimate", "Price per vCPU-hour (parts without a price)", f"${c.PRICE_PER_VCPU_HOUR:g}", "cost.PRICE_PER_VCPU_HOUR"),
        Constant("Cost estimate", "Price per GiB-hour of memory", f"${c.PRICE_PER_GIB_HOUR:g}", "cost.PRICE_PER_GIB_HOUR"),
        Constant("Cost estimate", "Price per GiB-month of storage", f"${c.PRICE_PER_STORAGE_GIB_MONTH:g}", "cost.PRICE_PER_STORAGE_GIB_MONTH"),
        Constant("Database model", "Share of memory used as buffer cache", _pct(db.BUFFER_CACHE_SHARE), "database.BUFFER_CACHE_SHARE"),
        Constant("Database model", "DynamoDB read units per partition per second", f"{db.PARTITION_READ_UNITS:,g}", "database.PARTITION_READ_UNITS", _DYNAMO_PARTITIONS),
        Constant("Database model", "DynamoDB write units per partition per second", f"{db.PARTITION_WRITE_UNITS:,g}", "database.PARTITION_WRITE_UNITS", _DYNAMO_PARTITIONS),
        Constant("Database model", "DynamoDB data per partition", f"{db.PARTITION_GIB:g} GiB", "database.PARTITION_GIB"),
        Constant("Database model", "DynamoDB on-demand table: reads per second at first", f"{db.ON_DEMAND_READ_UNITS:,g}", "database.ON_DEMAND_READ_UNITS", _DYNAMO_ON_DEMAND),
        Constant("Database model", "DynamoDB on-demand table: writes per second at first", f"{db.ON_DEMAND_WRITE_UNITS:,g}", "database.ON_DEMAND_WRITE_UNITS", _DYNAMO_ON_DEMAND),
        Constant("Database model", "DynamoDB burst capacity banked", f"{db.BURST_SECONDS:g} s of unused capacity", "database.BURST_SECONDS", _DYNAMO_BURST),
        Constant("Cache model", "Share of a node's memory that holds keys", _pct(ch.KEY_MEMORY_SHARE), "cache.KEY_MEMORY_SHARE"),
        Constant("Limits", "Most component calls one client request may cause", f"{validation.DEFAULT_MAX_AMPLIFICATION:,} (a warning above a tenth)", "validation.DEFAULT_MAX_AMPLIFICATION"),
    ]  # fmt: skip


# ---------------------------------------------------------------------------------------------
# formatting
# ---------------------------------------------------------------------------------------------


def _num(v: float) -> str:
    return f"{v:,}" if isinstance(v, int) else f"{v:,g}"


def _value(v: Any, unit: str | None = None) -> str:
    """A setting's value as text, with its unit."""
    u = f" {unit}" if unit else ""
    if v is None:
        return "none"
    if isinstance(v, bool):
        return "yes" if v else "no"
    if isinstance(v, dict) and "kind" in v:
        kind = v["kind"]
        if kind == "constant":
            return f"{_num(v['value'])}{u}"
        if kind == "exponential":
            return f"exponential, mean {_num(v['mean'])}{u}"
        if kind == "lognormal":
            return f"lognormal, median {_num(v['median'])}{u}, p95 {_num(v['p95'])}{u}"
        if kind == "uniform":
            return f"uniform {_num(v['min'])}–{_num(v['max'])}{u}"
    if isinstance(v, int | float):
        return f"{_num(v)}{u}"
    return f"`{v}`"


def _basis(source: str | None) -> str:
    if not source:
        return "illustrative"
    host = urlparse(source).netloc.removeprefix("www.").removeprefix("docs.")
    return f"documented: [{host}]({source})"


def _cell(text: str) -> str:
    return text.replace("|", "\\|")


def _table(head: list[str], rows: list[list[str]]) -> list[str]:
    out = ["| " + " | ".join(head) + " |", "|" + "|".join(" --- " for _ in head) + "|"]
    out += ["| " + " | ".join(_cell(c) for c in r) + " |" for r in rows]
    return out


def _fields(model: ComponentModel) -> dict[str, dict[str, Any]]:
    return {f["key"]: f for f in describe_fields(model.config_model)}


def _label(fields: dict[str, dict[str, Any]], key: str) -> str:
    f = fields.get(key)
    return f"{f['label']} (`{key}`)" if f else f"`{key}`"


def _unit(fields: dict[str, dict[str, Any]], key: str) -> str | None:
    f = fields.get(key)
    return f.get("unit") if f else None


# ---------------------------------------------------------------------------------------------
# sections
# ---------------------------------------------------------------------------------------------


def _with_profiles(registry: ComponentRegistry) -> list[ComponentModel]:
    return [m for m in registry.models() if m.technology_profiles]


def _tiers(registry: ComponentRegistry) -> list[str]:
    rows = []
    for m in _with_profiles(registry):
        by: dict[str, list[str]] = {"core": [], "experimental": []}
        for tech, p in m.technology_profiles.items():
            by[p.tier].append(f"`{tech}`")
        rows.append(
            [m.display_name, ", ".join(by["core"]) or "-", ", ".join(by["experimental"]) or "-"]
        )
    return [
        "## Technology tiers",
        "",
        "**Core** technologies are the ones ArchSim focuses on and will validate against "
        "measurements first. **Experimental** ones stay available, with a badge in the parts "
        f'library, the inspector, reports and exports that says: "{TIER_NOTE}" The generic part '
        "of each kind is core: it adds no technology behaviour, only the shared mechanisms "
        "(workers, connections, queues, service times) that the core technologies use too. No "
        "technology has been validated against measurements yet.",
        "",
        *_table(["Part", "Core", "Experimental"], rows),
        "",
    ]


def _profiles(registry: ComponentRegistry) -> list[str]:
    out = [
        "## Technology behaviour defaults",
        "",
        'What each technology sets for its "auto" settings (Technology behaviour in the '
        "inspector). Setting one on a part overrides it.",
        "",
    ]
    for m in _with_profiles(registry):
        fields = _fields(m)
        rows = []
        for tech, p in m.technology_profiles.items():
            first = True
            for key, v in p.values.items():
                camel = _camel(key)
                name = f"`{tech}`, {p.tier}" if first else ""
                rows.append(
                    [
                        name,
                        _label(fields, camel),
                        _value(v, _unit(fields, camel)),
                        _basis(p.source(key)),
                    ]
                )
                first = False
        if rows:
            out += [
                f"### {m.display_name}",
                "",
                *_table(["Technology", "Setting", "Value", "Basis"], rows),
                "",
            ]
    return out


def _presets(registry: ComponentRegistry) -> list[str]:
    out = [
        "## Preset numbers",
        "",
        "The numbers a technology preset fills in when you add it from the parts library. Prices "
        "are illustrative until real cloud prices are added.",
        "",
    ]
    for m in registry.models():
        if not m.presets:
            continue
        fields = _fields(m)
        rows = []
        for preset in m.presets:
            tier = m.technology_profiles.get(preset.technology)
            title = preset.name + (f", {tier.tier}" if tier else "")
            first = True
            for key, v in preset.config.items():
                if key == "technology":
                    continue
                rows.append(
                    [
                        title if first else "",
                        _label(fields, key),
                        _value(v, _unit(fields, key)),
                        _basis(preset.sources.get(key)),
                    ]
                )
                first = False
        out += [
            f"### {m.display_name}",
            "",
            *_table(["Preset", "Setting", "Value", "Basis"], rows),
            "",
        ]
    links = [
        [
            m.display_name,
            "Link " + _label(_fields_of(EdgeConfig), k),
            _value(v, "ms"),
            "illustrative",
        ]
        for m in registry.models()
        for k, v in m.link_defaults.items()
    ]
    if links:
        out += [
            "### Links drawn from client parts",
            "",
            *_table(["Client", "Setting", "Value", "Basis"], links),
            "",
        ]
    return out


def _fields_of(model: Any) -> dict[str, dict[str, Any]]:
    return {f["key"]: f for f in describe_fields(model)}


def _part_defaults(registry: ComponentRegistry) -> list[str]:
    out = [
        "## Defaults of a new part",
        "",
        "Settings marked as assumptions (≈ in the inspector) are numbers you assert about your "
        "system. A new part starts with these illustrative defaults; replace them with your own "
        "measurements. Every run lists the values it used under Assumptions.",
        "",
    ]
    rows = []
    for m in registry.models():
        fields = [f for f in describe_fields(m.config_model) if f["assumption"]]
        # an empty setting that a technology profile fills in takes the profile's value
        by_tech = {_camel(k) for p in m.technology_profiles.values() for k in p.values}
        for i, f in enumerate(fields):
            from_tech = f["default"] is None and f["key"] in by_tech
            rows.append(
                [
                    m.display_name if i == 0 else "",
                    f"{f['label']} (`{f['key']}`)",
                    "the technology's default (above)"
                    if from_tech
                    else _value(f["default"], f.get("unit")),
                ]
            )
    for title, model in (("Link", EdgeConfig), ("Workload", Workload)):
        fields = [f for f in describe_fields(model) if f["assumption"]]
        for i, f in enumerate(fields):
            rows.append(
                [
                    title if i == 0 else "",
                    f"{f['label']} (`{f['key']}`)",
                    _value(f["default"], f.get("unit")),
                ]
            )
    out += [*_table(["Part", "Setting", "Default"], rows), ""]
    return out


def _constants() -> list[str]:
    rows = [[c.area, c.name, c.value, _basis(c.source), f"`{c.code}`"] for c in constants()]
    return [
        "## Engine constants",
        "",
        "Numbers built into the sizing, cost and component models. Design review thresholds "
        "(for example 80% CPU at the peak) are rules of thumb stated with each finding, not "
        "inputs to the simulation.",
        "",
        *_table(["Area", "Constant", "Value", "Basis", "Code"], rows),
        "",
    ]


def render(registry: ComponentRegistry | None = None) -> str:
    """``docs/assumptions.md``."""
    registry = registry or create_default_registry(include_plugins=False)
    lines = [
        "# Assumptions",
        "",
        "<!-- Generated by scripts/gen_assumptions.py from the engine; do not edit by hand. -->",
        "",
        f"Every number ArchSim {ENGINE_VERSION} uses without you typing it, and where it comes "
        "from:",
        "",
        "- **documented**: the vendor's documentation states it (linked);",
        "- **illustrative**: a typical value ArchSim chose, not a vendor guarantee and not a "
        "measurement. Replace it with your own numbers before trusting absolute results.",
        "",
        "Generated from the code that uses these values (`uv run python "
        "scripts/gen_assumptions.py`); a test fails when this file is out of date.",
        "",
        *_tiers(registry),
        *_profiles(registry),
        *_presets(registry),
        *_part_defaults(registry),
        *_constants(),
    ]
    return "\n".join(lines).rstrip("\n") + "\n"
