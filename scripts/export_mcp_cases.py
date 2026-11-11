"""Write what the frozen engine's MCP server answered, for the Java server, or check the committed file.

The Python MCP server (``packages/mcp``, ``archsim_mcp.server`` on engine 0.16.0) is started over
stdio, as an agent starts it, and one session of JSON-RPC requests is sent to it: the
handshake, the lists of tools, resources, resource templates and prompts, every resource, and
calls of every tool, some refused (an unknown template or part type, a document that is not
JSON, an invalid design, a run over its limits, a calibration without its files or of files
that are not there, an unknown tool, arguments the input schema refuses). Each request and the
server's answer are recorded in order, as they were on the wire, the answer's ``id`` left out, in
``archsim-mcp/src/test/resources/io/archsim/mcp/session.json.gz``. ``McpSessionTests`` sends the
same requests to the Java server and compares the answers.

Files the requests name (the calibration's mapping, recorded responses and traces) are named by
placeholders, ``{fixtures}/...``, and recorded with the session (``files``), which the Java test
writes to a folder of its own and names instead; an answer that names one as ``pathlib`` does
(``[Errno 2] No such file or directory: '...'``) names it by its placeholder too, whatever the
system's separator. The files the server serves, the templates
(``examples/*.json``) and ``docs/simulation-model.md``, are recorded with them, by their paths in
the repository, so the Java test serves what the Python server served, whatever they hold since.

The server's calibrations stamp a fixed time (``NOW``). A full report's wall clock and Python
versions are normalized, and its text written again as the
SDK wrote it (``pydantic_core.to_json(..., indent=2)``).

Usage:
    uv run python scripts/export_mcp_cases.py           # write the session
    uv run python scripts/export_mcp_cases.py --check   # exit 1 if a fresh session differs
"""

from __future__ import annotations

import argparse
import copy
import gzip
import json
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import Any

import pydantic_core
from export_simulation_cases import CASES, busy_hour, clients, design, external, link

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "archsim-mcp/src/test/resources/io/archsim/mcp/session.json.gz"
GENERATOR = "scripts/export_mcp_cases.py (archsim_mcp 0.1.0, archsim_engine 0.16.0)"
FIXTURES = ROOT / "parity/fixtures"
PLACEHOLDER = "{fixtures}"
"""Stands for the folder of the test fixtures in the recorded requests."""
PROTOCOL = "2025-06-18"
NOW = "2026-11-10T12:00:00Z"
"""The time the calibrations stamp, as the Java test fixes it too."""
SERVER = (
    "import archsim_engine.calibration.apply as apply\n"
    "import archsim_engine.calibration.otlp as otlp\n"
    f"apply.now_iso = otlp.now_iso = lambda: {NOW!r}\n"
    "from archsim_mcp.server import main\n"
    "main()\n"
)
"""The Python server, its calibrations' clock stopped at NOW."""
Json = dict[str, Any]


def template(name: str) -> Json:
    data: Json = json.loads((ROOT / "examples" / f"{name}.json").read_text(encoding="utf-8"))
    return data


def fixture(name: str) -> str:
    return (FIXTURES / name).read_text(encoding="utf-8")


SMALL = CASES["constantRateToAnApi"]
SEEDED = busy_hour(copy.deepcopy(SMALL))
INVALID = design(
    [clients(), external(latencyMs={"kind": "constant", "value": 20})],
    [link("clients", "nowhere")],
    {"rps": 100},
)
TOO_BIG = design(
    [clients(), external(latencyMs={"kind": "constant", "value": 20})],
    [link("clients", "api")],
    {"rps": 50000, "durationS": 60},
)
# steady enough, and long enough for its sample, to be answered from a sample
STEADY = design(
    [clients(), external(latencyMs={"kind": "constant", "value": 25})],
    [link("clients", "api")],
    {"rps": 5000, "arrivalProcess": "deterministic", "warmupS": 2, "durationS": 30},
)
STARTER = template("cache-aside-starter")
# a run with more findings than a summary keeps
FINDINGS = template("notification-fanout-quotas")


def tiny_latencies(document: Json) -> Json:
    """The design with links whose latencies are below 1e-4 ms, which a calibration echoes:
    pydantic_core writes such floats otherwise than json.dumps (0.000025, 1.5e-6)."""
    tiny = copy.deepcopy(document)
    latencies = {"lb-api": 2.5e-05, "api-cache": 1.5e-06}
    for edge in tiny["architecture"]["edges"]:
        if edge["id"] in latencies:
            edge["config"]["latencyMs"] = {"kind": "constant", "value": latencies[edge["id"]]}
    return tiny


TINY = tiny_latencies(STARTER)


def call(tool: str, /, **arguments: Any) -> Json:
    return {"method": "tools/call", "params": {"name": tool, "arguments": arguments}}


def read(uri: str) -> Json:
    return {"method": "resources/read", "params": {"uri": uri}}


def requests() -> list[Json]:
    """The session's requests, in order: each run's id is its digest, so later calls name it."""
    mapping = f"{PLACEHOLDER}/calibration/starter-mapping.yaml"
    return [
        {"method": "tools/list"},
        {"method": "resources/list"},
        {"method": "resources/templates/list"},
        {"method": "prompts/list"},
        read("archsim://schema/architecture"),
        read("archsim://templates/queueing-lab"),
        read("archsim://templates/nowhere"),
        read("archsim://docs/simulation-model"),
        call("list_part_types"),
        call("list_part_types", type="database"),
        call("list_part_types", type="nowhere"),
        call("list_templates"),
        call("get_template", id="queueing-lab"),
        call("get_template", id="nowhere"),
        # an argument the input schema requires, left out
        call("get_template"),
        call("validate_design", document=STARTER),
        call("validate_design", document=json.dumps(INVALID)),
        call("validate_design", document="{not json"),
        call("validate_design", document="[1]"),
        call("validate_design", document={"schemaVersion": "2.0"}),
        call("analyze_design", document=STARTER),
        call("analyze_design", document=STARTER, scenario_id="three-api-replicas"),
        call("analyze_design", document=STARTER, scenario_id="nowhere"),
        call("simulate", document=SMALL),
        call("simulate", document=SEEDED, scenario_id="busy"),
        call("simulate", document=STEADY, mode="sampled"),
        call("simulate", document=INVALID),
        call("simulate", document=TOO_BIG),
        call("simulate", document=FINDINGS),
        call("get_results", run_id="{run}"),
        call("get_results", run_id="{run}", detail="full"),
        call("get_results", run_id="nowhere"),
        call("compare", document=SEEDED, b="busy"),
        call("compare", document=SEEDED, a="busy"),
        call("compare", document=SEEDED, a="", b="busy"),
        call("breaking_point", document=SMALL, steps=3),
        call("breaking_point", document=SMALL, from_rps=50, to_rps=400, steps=2),
        # a number as text, which pydantic took and the input schema does not
        call("breaking_point", document=SMALL, steps="3"),
        call("export", document=STARTER, format="compose"),
        call("export", document=STARTER, format="k8s"),
        call("export", document=STARTER, format="mermaid"),
        call("export", document=INVALID, format="mermaid"),
        call(
            "calibrate",
            document=STARTER,
            source="prometheus",
            mapping_path=mapping,
            recorded_path=f"{PLACEHOLDER}/calibration/starter-prometheus.json",
        ),
        call(
            "calibrate",
            document=TINY,
            source="prometheus",
            mapping_path=mapping,
            recorded_path=f"{PLACEHOLDER}/calibration/starter-prometheus.json",
        ),
        call("calibrate", document=STARTER, source="prometheus", mapping_path=mapping),
        call(
            "calibrate",
            document=STARTER,
            source="otlp",
            traces_path=f"{PLACEHOLDER}/calibration/checkout-traces.otlp.json",
            mapping_path=f"{PLACEHOLDER}/calibration/otlp-mapping.yaml",
        ),
        call("calibrate", document=STARTER, source="otlp"),
        call(
            "calibrate",
            document=STARTER,
            source="otlp",
            traces_path=f"{PLACEHOLDER}/calibration/missing-traces.json",
        ),
        call(
            "calibrate",
            document=STARTER,
            source="prometheus",
            mapping_path=f"{PLACEHOLDER}/calibration/missing-mapping.yaml",
            recorded_path=f"{PLACEHOLDER}/calibration/starter-prometheus.json",
        ),
        call("import_design", format="compose", text=fixture("import/voting-compose.yml")),
        call("import_design", format="k8s", text=fixture("import/shop-k8s.yaml"), name="Shop"),
        call("import_design", format="terraform", text=fixture("import/orders-aws-plan.json")),
        call("import_design", format="compose", text="[1, 2]"),
        call("nowhere"),
    ]


class Session:
    """The Python MCP server over stdio, one JSON-RPC message per line."""

    def __init__(self) -> None:
        env = {**os.environ, "ARCHSIM_EXAMPLES_DIR": str(ROOT / "examples")}
        self.process = subprocess.Popen(
            [sys.executable, "-c", SERVER],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            encoding="utf-8",
            cwd=ROOT,
            env=env,
        )
        self.next_id = 1

    def send(self, message: Json) -> None:
        assert self.process.stdin is not None
        self.process.stdin.write(json.dumps(message) + "\n")
        self.process.stdin.flush()

    def request(self, method: str, params: Json | None) -> Json:
        message: Json = {"jsonrpc": "2.0", "id": self.next_id, "method": method}
        if params is not None:
            message["params"] = params
        self.send(message)
        self.next_id += 1
        assert self.process.stdout is not None
        answer: Json = json.loads(self.process.stdout.readline())
        answer.pop("id", None)
        return answer

    def close(self) -> None:
        assert self.process.stdin is not None
        self.process.stdin.close()
        self.process.wait(timeout=60)


def resolved(value: Any, run: str) -> Any:
    """``value`` with the fixtures' placeholder and the run's id as the server must see them."""
    text = json.dumps(value).replace(PLACEHOLDER, FIXTURES.as_posix()).replace("{run}", run)
    return json.loads(text)


def placeheld(value: Any) -> Any:
    """``value`` with the fixtures' folder named by its placeholder, as the requests name it,
    and a file the server named as ``pathlib`` names it (with backslashes on Windows) so too."""
    text = json.dumps(value)
    for name in named_files():
        native = str(FIXTURES / name.removeprefix(PLACEHOLDER + "/"))
        text = text.replace(json.dumps(repr(native))[1:-1], json.dumps(repr(name))[1:-1])
    return json.loads(text.replace(FIXTURES.as_posix(), PLACEHOLDER))


def named_files() -> list[str]:
    """The files the requests name, by their placeheld names."""
    return sorted(set(re.findall(re.escape(PLACEHOLDER) + r"/[\w./-]+", json.dumps(requests()))))


def record() -> Json:
    session = Session()
    try:
        handshake = {
            "protocolVersion": PROTOCOL,
            "capabilities": {},
            "clientInfo": {"name": "export_mcp_cases", "version": "1"},
        }
        exchanges = [
            {
                "request": {"method": "initialize", "params": handshake},
                "answer": session.request("initialize", handshake),
            }
        ]
        session.send({"jsonrpc": "2.0", "method": "notifications/initialized"})
        run = ""
        for request in requests():
            answer = session.request(request["method"], resolved(request.get("params"), run))
            if request.get("params", {}).get("name") == "simulate" and not run:
                run = answer["result"]["structuredContent"]["runId"]
            exchanges.append({"request": request, "answer": placeheld(answer)})
    finally:
        session.close()
    for exchange in exchanges:
        normalize(exchange["answer"])
    return {"generator": GENERATOR, "run": run, "files": files(), "exchanges": exchanges}


def files() -> Json:
    """The text of each file the requests name that is there, by its placeheld name, and of each
    the server serves, by its path in the repository: as the server read them, ``read_text``'s."""
    named = named_files()
    served = [*sorted((ROOT / "examples").glob("*.json")), ROOT / "docs/simulation-model.md"]
    return {
        **{
            name: (FIXTURES / name.removeprefix(PLACEHOLDER + "/")).read_text(encoding="utf-8")
            for name in named
            if (FIXTURES / name.removeprefix(PLACEHOLDER + "/")).exists()
        },
        **{path.relative_to(ROOT).as_posix(): path.read_text(encoding="utf-8") for path in served},
    }


def normalize(answer: Json) -> None:
    """A full report's wall clock and Python versions, which no two runs share."""
    content = answer.get("result", {}).get("structuredContent")
    if isinstance(content, dict) and isinstance(content.get("provenance"), dict):
        provenance = content["provenance"]
        provenance["wallSeconds"] = 0.0
        provenance["python"] = "normalized"
        provenance["simpy"] = "normalized"
        text = pydantic_core.to_json(content, fallback=str, indent=2).decode()
        answer["result"]["content"] = [{"type": "text", "text": text}]


def export() -> bytes:
    text = json.dumps(record(), separators=(",", ":"))
    return gzip.compress(text.encode(), mtime=0)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    parser.add_argument("--check", action="store_true", help="exit 1 if a fresh session differs")
    args = parser.parse_args(argv)
    data = export()
    if args.check:
        if not OUT.exists() or gzip.decompress(OUT.read_bytes()) != gzip.decompress(data):
            print(f"{OUT.name} differs from a fresh session: run scripts/export_mcp_cases.py")
            return 1
        print(f"{OUT.name} matches the frozen engine's MCP server")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_bytes(data)
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
