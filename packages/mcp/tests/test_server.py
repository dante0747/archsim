"""Drive the MCP server over stdio with the official SDK's client, as an agent would."""

from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path
from typing import Any

import pytest
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

ROOT = Path(__file__).resolve().parents[3]
FIXTURES = ROOT / "packages" / "engine" / "tests" / "fixtures" / "calibration"
STARTER = json.loads((ROOT / "examples" / "cache-aside-starter.json").read_text(encoding="utf-8"))
EXPECTED_TOOLS = {
    "list_part_types",
    "list_templates",
    "get_template",
    "validate_design",
    "analyze_design",
    "simulate",
    "get_results",
    "compare",
    "breaking_point",
    "export",
    "calibrate",
    "import_design",
}


def _payload(result: Any) -> Any:
    """A tool result's JSON (structured content when the SDK gives it, else the text)."""
    assert not result.is_error, result.content
    structured = result.structured_content
    if structured is not None:
        return structured.get("result", structured)
    text = "".join(c.text for c in result.content if getattr(c, "type", "") == "text")
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        return text


async def _session_run() -> dict[str, Any]:
    params = StdioServerParameters(
        command=sys.executable, args=["-m", "archsim_mcp.server"], cwd=str(ROOT)
    )
    out: dict[str, Any] = {}
    async with stdio_client(params) as (read, write), ClientSession(read, write) as session:
        await session.initialize()
        out["tools"] = {t.name for t in (await session.list_tools()).tools}
        out["resources"] = {str(r.uri) for r in (await session.list_resources()).resources}
        out["templates"] = _payload(await session.call_tool("list_templates", {}))
        doc = STARTER
        out["sim1"] = _payload(await session.call_tool("simulate", {"document": doc}))
        out["sim2"] = _payload(await session.call_tool("simulate", {"document": doc}))
        out["analysis"] = _payload(await session.call_tool("analyze_design", {"document": doc}))
        out["compose"] = _payload(
            await session.call_tool("export", {"document": doc, "format": "compose"})
        )
        broken = json.loads(json.dumps(doc))
        broken["architecture"]["edges"].append({"id": "x", "source": "api", "target": "nowhere"})
        out["invalid"] = _payload(await session.call_tool("validate_design", {"document": broken}))
        out["simulate_invalid"] = await session.call_tool("simulate", {"document": broken})
        out["results"] = _payload(
            await session.call_tool("get_results", {"run_id": out["sim1"]["runId"]})
        )
        scenario = "one-api-replica"  # a scenario that changes the design
        out["compare"] = _payload(
            await session.call_tool("compare", {"document": doc, "b": scenario})
        )
        out["knee"] = _payload(
            await session.call_tool(
                "breaking_point", {"document": doc, "from_rps": 200, "to_rps": 2000, "steps": 3}
            )
        )
        out["calibrated"] = _payload(
            await session.call_tool(
                "calibrate",
                {
                    "document": doc,
                    "source": "prometheus",
                    "mapping_path": str(FIXTURES / "starter-mapping.yaml"),
                    "recorded_path": str(FIXTURES / "starter-prometheus.json"),
                },
            )
        )
        compose = (
            ROOT / "packages" / "engine" / "tests" / "fixtures" / "import" / "voting-compose.yml"
        ).read_text(encoding="utf-8")
        out["imported"] = _payload(
            await session.call_tool("import_design", {"format": "compose", "text": compose})
        )
    return out


def test_an_agent_can_list_simulate_explain_and_export_over_stdio() -> None:
    out = asyncio.run(_session_run())
    assert out["tools"] >= EXPECTED_TOOLS
    assert "archsim://schema/architecture" in out["resources"]
    assert any(t["id"] == "cache-aside-starter" for t in out["templates"])
    sim = out["sim1"]
    assert sim["verdict"].startswith(("Meets", "Misses"))
    assert sim["throughputRps"] > 0 and sim["latencyMs"]["p95"] > 0
    # deterministic: the same document gives the same digest
    assert sim["resultDigest"] == out["sim2"]["resultDigest"]
    assert out["results"]["resultDigest"] == sim["resultDigest"]
    assert out["analysis"]["sizes"] and out["analysis"]["review"] is not None
    assert "image: postgres:16" in out["compose"]
    # mistakes come back with how to fix them
    assert out["invalid"]["valid"] is False and out["invalid"]["issues"]
    assert out["simulate_invalid"].is_error
    text = "".join(
        c.text for c in out["simulate_invalid"].content if getattr(c, "type", "") == "text"
    )
    assert "nowhere" in text or "cannot be used" in text
    # two scenarios compared, with the input changes that explain the difference
    assert out["compare"]["kpis"] and out["compare"]["b"]["runId"] != out["compare"]["a"]["runId"]
    assert len(out["knee"]["rows"]) == 3 and out["knee"]["verdict"]
    # calibration from a recording (no network): measured inputs and the replay check
    cal = out["calibrated"]
    assert cal["document"]["provenance"]["nodes/api/serviceTimeMs"]["kind"] == "measured"
    assert cal["summary"].startswith("This model reproduces the observed p95")
    # an import says what it guessed
    assert (
        out["imported"]["report"]["guessed"]
        and out["imported"]["document"]["architecture"]["nodes"]
    )


def test_calibrate_reports_a_replay_past_the_limits(monkeypatch: pytest.MonkeyPatch) -> None:
    from mcp.server.mcpserver.exceptions import ToolError

    from archsim_engine.engine import SimulationLimits
    from archsim_mcp import server

    monkeypatch.setattr(server, "LIMITS", SimulationLimits(max_requests=1000))
    with pytest.raises(ToolError, match="replay run hit a limit"):
        server.calibrate(
            STARTER,
            "prometheus",
            mapping_path=str(FIXTURES / "starter-mapping.yaml"),
            recorded_path=str(FIXTURES / "starter-prometheus.json"),
        )
