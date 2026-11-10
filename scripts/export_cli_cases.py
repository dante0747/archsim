"""Write what the frozen engine's ``archsim`` commands printed, for the Java CLI, or check the committed files.

The Python CLI (``archsim_engine.cli``, engine 0.16.0) is recorded command by command, one file
each under ``archsim-cli/src/test/resources/io/archsim/cli/`` (``<command>-cases.json.gz``): the
arguments, the files the command reads, the exit code, what went to stdout and stderr, and the
files it wrote. The Java CLI's tests run each case and compare its lines word for word, and the
JSON it prints as values, as the golden files compare them (ADR-0055).

- ``run``: small designs (a run, one without failures, one whose every call fails, a scenario
  with a seed of its own, a seed given, a seed for a design without a workload, a quick check, a
  report written to a file, an invalid design, an invalid workload and a run over the limit); a
  report written to a file is compared as the recorded simulation cases are.
- ``validate``: every template, the queueing lab stamped with every schema version and with
  the versions refused, an invalid design and workload, and files it cannot use: one that is not
  JSON, one that is missing, a JSON array, an empty object.
- ``analyze``: every template and the first scenario of each that has one, an unknown scenario, an
  invalid design, a small design, and the files it cannot use.
- ``compare``, ``export`` and ``import``: see the Java CLI's README.
- ``calibrate``: ``prometheus`` from recorded responses and from a server, a stub started for
  the case (``server``: answering with the recorded responses, refusing every query, answering
  as an HTML page, or a port nothing listens on), and ``otlp`` from trace exports, with the
  refusals of each. The mapping, recording and traces a case reads are kept in ``inputs``, and
  the time a calibration stamps is fixed (``NOW``), as the Java tests fix it.
- ``check``: the starter checked against its objectives and against baselines, from files and
  at git refs (``repo``: a repository made for the case, one commit of the files it names),
  with its breaking point, and what it refuses.
- ``catalog`` and ``schema``.

Keys are kept in the order Python wrote them, as the Java tests read them.

A command that crashed in Python (an uncaught exception, exit code 1 and a traceback) is recorded
with ``crash``, the exception's type and message, instead of its stderr: the Java CLI answers
it with a message of its own.

Usage:
    uv run python scripts/export_cli_cases.py           # write the cases
    uv run python scripts/export_cli_cases.py --check   # exit 1 if a fresh export differs
"""

from __future__ import annotations

import argparse
import base64
import codecs
import contextlib
import copy
import gzip
import http.server
import io
import json
import socket
import subprocess
import sys
import tempfile
import threading
from collections.abc import Iterator
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlsplit

from export_simulation_cases import CASES, busy_hour, clients, design, external, faster_api, link

from archsim_engine import cli
from archsim_engine.calibration import apply as calibration_apply
from archsim_engine.calibration import otlp as calibration_otlp
from archsim_engine.version import SCHEMA_VERSION

FOLDER = Path(__file__).resolve().parent.parent / "archsim-cli/src/test/resources/io/archsim/cli"
EXAMPLES = Path(__file__).resolve().parent.parent / "examples"
GENERATOR = "scripts/export_cli_cases.py (archsim_engine 0.16.0)"
FILE = "{design}"
"""Stands for the design's file in the recorded arguments and text."""
REPORT = "{report}"
"""Stands for the report's file."""
MISSING = "{missing}"
"""Stands for a file that does not exist."""
NOT_JSON = "not JSON"
"""A design file's text that is not JSON."""
Json = dict[str, Any]

QUICK = faster_api(
    design(
        [clients(), external(latencyMs={"kind": "constant", "value": 25})],
        [link("clients", "api")],
        {"rps": 5000, "arrivalProcess": "deterministic", "warmupS": 2, "durationS": 30},
    )
)
INVALID = design(
    [clients(), external(latencyMs={"kind": "constant", "value": 20})],
    [link("clients", "nowhere")],
    {"rps": 100},
)
NO_FAILURES = design(
    [clients(), external(latencyMs={"kind": "constant", "value": 20}, errorRate=0)],
    [link("clients", "api")],
    {"rps": 100, "arrivalProcess": "deterministic"},
)
EVERY_CALL_FAILS = design(
    [clients(), external(latencyMs={"kind": "constant", "value": 20}, errorRate=1)],
    [link("clients", "api")],
    {"rps": 100, "arrivalProcess": "deterministic"},
)
INVALID_WORKLOAD = design(
    [clients(), external(latencyMs={"kind": "constant", "value": 20})],
    [link("clients", "api")],
    {"rps": -1},
)
NO_WORKLOAD = {key: value for key, value in NO_FAILURES.items() if key != "workload"}
TOO_BIG = design(
    [clients(), external(latencyMs={"kind": "constant", "value": 20})],
    [link("clients", "api")],
    {"rps": 50000, "durationS": 60},
)

RUNS: list[Json] = [
    {"name": "aDesignRun", "design": CASES["constantRateToAnApi"], "args": ["run", FILE]},
    {"name": "aRunWithoutFailures", "design": NO_FAILURES, "args": ["run", FILE]},
    {"name": "aRunWhoseEveryCallFails", "design": EVERY_CALL_FAILS, "args": ["run", FILE]},
    {
        # the CLI's seed is the design's: a scenario that sets its own seed wins
        "name": "aScenarioWithASeedOfItsOwn",
        "design": busy_hour(copy.deepcopy(CASES["constantRateToAnApi"])),
        "args": ["run", FILE, "--scenario", "busy", "--seed", "5"],
    },
    {
        "name": "aRunWithASeed",
        "design": CASES["poissonToAnApiThatFails"],
        "args": ["run", FILE, "--seed", "5"],
    },
    # the CLI's seed makes the workload of a design without one
    {
        "name": "aSeedForADesignWithoutAWorkload",
        "design": NO_WORKLOAD,
        "args": ["run", FILE, "--seed", "5"],
    },
    {
        "name": "aQuickCheck",
        "design": QUICK,
        "args": ["run", FILE, "--sampled", "--scenario", "faster"],
    },
    {
        "name": "aReportWrittenToAFile",
        "design": CASES["twoClientGroups"],
        "args": ["run", FILE, "--out", REPORT],
    },
    {"name": "anInvalidDesign", "design": INVALID, "args": ["run", FILE]},
    {"name": "anInvalidWorkload", "design": INVALID_WORKLOAD, "args": ["run", FILE]},
    {"name": "aRunOverTheRequestLimit", "design": TOO_BIG, "args": ["run", FILE]},
]


def template(name: str) -> Json:
    data: Json = json.loads((EXAMPLES / f"{name}.json").read_text(encoding="utf-8"))
    return data


TEMPLATES = sorted(path.stem for path in EXAMPLES.glob("*.json"))
QUEUEING_LAB = template("queueing-lab")


def versions() -> list[Json]:
    """The queueing lab stamped with every schema version, and with the versions refused."""
    current = int(SCHEMA_VERSION.split(".")[1])
    stamped = {f"1.{minor}": f"1.{minor}" for minor in range(current + 1)} | {
        "2.0": "2.0",
        "aNumber": 1.3,
        "notAVersion": "one",
    }
    cases = [
        {
            "name": f"schemaVersion {name}",
            "design": {**QUEUEING_LAB, "schemaVersion": version},
            "args": ["validate", FILE],
        }
        for name, version in stamped.items()
    ]
    without = {key: value for key, value in QUEUEING_LAB.items() if key != "schemaVersion"}
    return [*cases, {"name": "noSchemaVersion", "design": without, "args": ["validate", FILE]}]


def unreadable(*command: str) -> list[Json]:
    """Files a command cannot use: not JSON, missing, a JSON array, an empty object."""
    return [
        {"name": "aFileThatIsNotJson", "text": NOT_JSON, "args": [*command, FILE]},
        {"name": "aMissingFile", "args": [*command, MISSING]},
        {"name": "aJsonArray", "design": [QUEUEING_LAB], "args": [*command, FILE]},
        {"name": "anEmptyObject", "design": {}, "args": [*command, FILE]},
    ]


VALIDATES: list[Json] = [
    *({"name": name, "design": template(name), "args": ["validate", FILE]} for name in TEMPLATES),
    *versions(),
    {"name": "anInvalidDesign", "design": INVALID, "args": ["validate", FILE]},
    {"name": "anInvalidWorkload", "design": INVALID_WORKLOAD, "args": ["validate", FILE]},
    *unreadable("validate"),
]


def scenarios() -> list[Json]:
    """The first scenario of every template that has one."""
    return [
        {
            "name": f"{name} {doc['scenarios'][0]['id']}",
            "design": doc,
            "args": ["analyze", FILE, "--scenario", doc["scenarios"][0]["id"]],
        }
        for name in TEMPLATES
        if (doc := template(name)).get("scenarios")
    ]


ANALYZES: list[Json] = [
    *({"name": name, "design": template(name), "args": ["analyze", FILE]} for name in TEMPLATES),
    *scenarios(),
    {
        "name": "anUnknownScenario",
        "design": QUEUEING_LAB,
        "args": ["analyze", FILE, "--scenario", "nowhere"],
    },
    {"name": "anInvalidDesign", "design": INVALID, "args": ["analyze", FILE]},
    {"name": "aDesignRun", "design": CASES["constantRateToAnApi"], "args": ["analyze", FILE]},
    *unreadable("analyze"),
]

FIXTURES = Path(__file__).resolve().parent.parent / "packages/engine/tests/fixtures"
OUT_FILE = "{out}"
"""Stands for a file a command writes its result to (-o)."""


def fixture(name: str) -> str:
    return (FIXTURES / name).read_text(encoding="utf-8")


COMPOSE = fixture("import/voting-compose.yml")
K8S = fixture("import/shop-k8s.yaml")
TERRAFORM = fixture("import/orders-aws-plan.json")
SEEDED = busy_hour(copy.deepcopy(CASES["constantRateToAnApi"]))


def two_scenarios() -> Json:
    """The small design with a busy hour and a faster API, each a scenario."""
    doc = busy_hour(copy.deepcopy(CASES["constantRateToAnApi"]))
    doc["scenarios"] += faster_api(copy.deepcopy(CASES["constantRateToAnApi"]))["scenarios"]
    return doc


COMPARES: list[Json] = [
    {"name": "theDesignWithItself", "design": NO_FAILURES, "args": ["compare", FILE]},
    {
        "name": "aScenarioAgainstTheDesign",
        "design": SEEDED,
        "args": ["compare", FILE, "--b", "busy"],
    },
    {
        "name": "theDesignAgainstAScenario",
        "design": SEEDED,
        "args": ["compare", FILE, "--a", "busy"],
    },
    {
        "name": "twoScenarios",
        "design": two_scenarios(),
        "args": ["compare", FILE, "--a", "busy", "--b", "faster"],
    },
    {"name": "aDesignThatFails", "design": EVERY_CALL_FAILS, "args": ["compare", FILE]},
    {"name": "anUnknownScenario", "design": SEEDED, "args": ["compare", FILE, "--b", "nowhere"]},
    {"name": "anInvalidDesign", "design": INVALID, "args": ["compare", FILE]},
    {"name": "aRunOverTheRequestLimit", "design": TOO_BIG, "args": ["compare", FILE]},
    *unreadable("compare"),
]

EXPORTED = (
    "cache-aside-starter",
    "black-friday-kubernetes",
    "microservices-call-chain",
    "kafka-order-pipeline",
    "serverless-image-pipeline",
    "queueing-lab",
)
EXPORTS: list[Json] = [
    *(
        {"name": f"{name} {fmt}", "design": template(name), "args": ["export", fmt, FILE]}
        for name in EXPORTED
        for fmt in ("compose", "k8s", "mermaid")
    ),
    {
        "name": "toAFile",
        "design": template("cache-aside-starter"),
        "args": ["export", "k8s", FILE, "-o", OUT_FILE],
    },
    {"name": "anInvalidDesign", "design": INVALID, "args": ["export", "compose", FILE]},
    {"name": "anUnknownFormat", "design": QUEUEING_LAB, "args": ["export", "terraform", FILE]},
    *unreadable("export", "mermaid"),
]

IMPORTS: list[Json] = [
    {"name": "compose", "text": COMPOSE, "args": ["import", "compose", FILE]},
    {"name": "k8s", "text": K8S, "args": ["import", "k8s", FILE]},
    {"name": "terraform", "text": TERRAFORM, "args": ["import", "terraform", FILE]},
    {"name": "aName", "text": COMPOSE, "args": ["import", "compose", FILE, "--name", "Votes"]},
    {
        "name": "toFiles",
        "text": K8S,
        "args": ["import", "k8s", FILE, "-o", OUT_FILE, "--report", REPORT],
    },
    {"name": "fromStandardInput", "stdin": COMPOSE, "args": ["import", "compose", "-"]},
    {
        "name": "utf16WithAByteOrderMark",
        "bytes": base64.b64encode(COMPOSE.encode("utf-16")).decode(),
        "args": ["import", "compose", FILE],
    },
    {
        "name": "utf8WithAByteOrderMark",
        "bytes": base64.b64encode(COMPOSE.encode("utf-8-sig")).decode(),
        "args": ["import", "compose", FILE],
    },
    {
        "name": "notUtf8",
        "bytes": base64.b64encode(b"a: \xff\n").decode(),
        "args": ["import", "compose", FILE],
    },
    {
        "name": "aTruncatedUtf8Character",
        "bytes": base64.b64encode(b"a: \xc3").decode(),
        "args": ["import", "compose", FILE],
    },
    {
        "name": "anOddUtf16",
        "bytes": base64.b64encode(b"\xff\xfea\x00b").decode(),
        "args": ["import", "compose", FILE],
    },
    *(
        {"name": name, "bytes": base64.b64encode(raw).decode(), "args": ["import", "compose", FILE]}
        for name, raw in {
            "anInvalidContinuation": b"a\xe2\x28\xa1",
            "aContinuationByteAlone": b"a\x80",
            "anOverlongTwoByteLead": b"a\xc0\x80",
            "anUnfinishedThreeByteCharacter": b"a\xe2\x82",
            "anOverlongForm": b"a: \xe0\x80\x80",
            "aSurrogateInUtf8": b"\xed\xa0\x80",
            "pastU10ffff": b"\xf4\x90\x80\x80",
            "aTruncatedFourByteCharacter": b"a: \xf0\x9f\x98",
            "aBadThirdByteOfFour": b"\xf0\x9f\x28\x80",
            "anInvalidMarkedUtf8": b"\xef\xbb\xbfa: \xff",
            "aLoneLowSurrogate": b"\xff\xfea\x00\x00\xdc",
            "aHighSurrogateWithoutItsPair": b"\xff\xfe\x00\xd8a\x00",
            "aHighSurrogateAtTheEnd": b"\xfe\xff\x00a\xd8\x00",
            "aBigEndianOddLength": b"\xfe\xff\x00a\x00",
        }.items()
    ),
    {"name": "notTheFormat", "text": "[1, 2]", "args": ["import", "compose", FILE]},
    {"name": "terraformThatIsNotJson", "text": "{", "args": ["import", "terraform", FILE]},
    {"name": "notYaml", "text": "a: [1, 2", "args": ["import", "compose", FILE]},
    {"name": "anUnknownFormat", "text": COMPOSE, "args": ["import", "helm", FILE]},
    {"name": "aMissingFile", "args": ["import", "compose", MISSING]},
]

NOW = "2026-11-10T12:00:00Z"
"""The time a calibration stamps, in place of the clock's: the Java tests' clock says the same."""
URL = "{url}"
"""Stands for the stub Prometheus server's base URL."""
MAP = "{map}"
RECORDED = "{recorded}"
TRACES = "{traces}"
RECORD = "{record}"
"""The files a calibration reads (its mapping, recorded responses, traces) and the recording it
writes."""
INPUT_FILES = {MAP: "map.yaml", RECORDED: "recorded.json", TRACES: "traces.json"}
OUTPUT_FILES = (REPORT, OUT_FILE, RECORD)

CALIBRATION = FIXTURES / "calibration"
STARTER = template("cache-aside-starter")
STARTER_MAP = (CALIBRATION / "starter-mapping.yaml").read_text(encoding="utf-8")
STARTER_RECORDED = (CALIBRATION / "starter-prometheus.json").read_text(encoding="utf-8")
# the starter's recording, with a range whose step the range refuses
STEP_ZERO_RECORDED = json.dumps(
    {**json.loads(STARTER_RECORDED), "range": {**json.loads(STARTER_RECORDED)["range"], "step": 0}}
)
# the starter's recording without its source: named after its file
UNNAMED_RECORDED = json.dumps(
    {k: v for k, v in json.loads(STARTER_RECORDED).items() if k != "source"}
)
CHECKOUT_TRACES = (CALIBRATION / "checkout-traces.otlp.json").read_text(encoding="utf-8")
OTLP_MAP = (CALIBRATION / "otlp-mapping.yaml").read_text(encoding="utf-8")
RANGE = "2026-10-01T00:00:00Z/2026-10-01T00:02:00Z"
# not JSON where a design that is not is refused ("line 1 column 1"), in json's words in
# Python and Java alike
NOT_JSON_ON_LINE_2 = "\n x"
# only a replay: the design's own workload runs, too many requests for TOO_BIG
REPLAY_ONLY_MAP = (
    "replay:\n"
    "  endToEnd: {histogram: 'sum by (le) (rate(client_request_duration_seconds_bucket[1m]))', "
    "unit: s}\n"
)
# YAML that PyYAML refuses (a ValueError) or fails to read (KeyError, IndexError,
# AttributeError: the Python CLI caught the first, and `otlp` the second)
YAML_PYTHON_FAILS_ON = {
    "ARecursiveAlias": "a: &a [*a]\n",
    "ABooleanThatIsNotOne": "x: !!bool maybe\n",
    "AnEmptyInteger": "a: !!int ''\n",
    "ATimestampThatIsNotOne": "a: !!timestamp soon\n",
}


def compressed(traces: str, factor: int) -> str:
    """The same spans with their times ``factor`` times closer to the first: ``factor`` times
    the rate, which the OTLP replay runs at."""
    data = json.loads(traces)
    spans = [
        span
        for resource in data["resourceSpans"]
        for scope in resource["scopeSpans"]
        for span in scope["spans"]
    ]
    first = min(int(span["startTimeUnixNano"]) for span in spans)
    for span in spans:
        for key in ("startTimeUnixNano", "endTimeUnixNano"):
            span[key] = str(first + (int(span[key]) - first) // factor)
    return json.dumps(data, indent=1)


def prometheus(name: str, *args: str, **case: Any) -> Json:
    """A Prometheus calibration of the starter, with its mapping and recorded responses."""
    return {
        "name": name,
        "design": STARTER,
        "inputs": {MAP: STARTER_MAP, RECORDED: STARTER_RECORDED},
        "args": ["calibrate", "prometheus", FILE, "--map", MAP, *args],
        **case,
    }


def otlp(name: str, *args: str, **case: Any) -> Json:
    """An OpenTelemetry calibration of the starter, with its traces and mapping."""
    return {
        "name": name,
        "design": STARTER,
        "inputs": {TRACES: CHECKOUT_TRACES, MAP: OTLP_MAP},
        "args": ["calibrate", "otlp", FILE, *args],
        **case,
    }


CALIBRATES: list[Json] = [
    prometheus("recorded", "--recorded", RECORDED),
    prometheus("recordedToAFile", "--recorded", RECORDED, "-o", OUT_FILE),
    prometheus("withoutTheReplay", "--recorded", RECORDED, "--no-replay"),
    prometheus(
        "aRangeTheRecordingDoesNotCover",
        "--recorded",
        RECORDED,
        "--range",
        "2026-10-02T00:00:00Z/2026-10-02T00:02:00Z",
    ),
    prometheus("recordedAgain", "--recorded", RECORDED, "--record", RECORD),
    prometheus("fromAServer", "--url", URL, "--range", RANGE, "--step", "5", server="recorded"),
    prometheus(
        "fromAServerRecorded",
        "--url",
        URL,
        "--range",
        RANGE,
        "--step",
        "5",
        "--record",
        RECORD,
        server="recorded",
    ),
    prometheus("fromAServerOverTheMappingsRange", "--url", URL, server="recorded"),
    prometheus("aServerThatRefusesTheQuery", "--url", URL, "--range", RANGE, server="badData"),
    prometheus("aServerThatIsNotPrometheus", "--url", URL, "--range", RANGE, server="html"),
    prometheus("aServerThatIsNotThere", "--url", URL, "--range", RANGE, server="closed"),
    prometheus("aUrlThatIsNotHttp", "--url", "ftp://prometheus", "--range", RANGE),
    prometheus("aPortThatIsNotOne", "--url", "http://prometheus:port", "--range", RANGE),
    prometheus(
        "aRangeThatEndsFirst",
        "--url",
        URL,
        "--range",
        "2026-10-01T00:02:00Z/2026-10-01T00:00:00Z",
        server="recorded",
    ),
    prometheus("aRangeThatIsNotOne", "--url", URL, "--range", "yesterday", server="recorded"),
    {
        **prometheus("aMissingMapping"),
        "args": ["calibrate", "prometheus", FILE, "--map", MISSING, "--recorded", RECORDED],
    },
    prometheus(
        "aMappingThatIsNotYaml",
        "--recorded",
        RECORDED,
        inputs={MAP: "range: [1, 2", RECORDED: STARTER_RECORDED},
    ),
    prometheus(
        "aMappingOfAPartThatIsNotThere",
        "--recorded",
        RECORDED,
        inputs={
            MAP: "nodes: {nowhere: {serviceTimeMs: {histogram: x}}}",
            RECORDED: STARTER_RECORDED,
        },
    ),
    prometheus(
        "aRecordingThatIsNotJson",
        "--recorded",
        RECORDED,
        inputs={MAP: STARTER_MAP, RECORDED: "{"},
    ),
    {
        **prometheus("aMissingRecording"),
        "args": ["calibrate", "prometheus", FILE, "--map", MAP, "--recorded", MISSING],
    },
    *(
        prometheus(
            f"aMappingWith{kind}",
            "--recorded",
            RECORDED,
            inputs={MAP: text, RECORDED: STARTER_RECORDED},
        )
        for kind, text in YAML_PYTHON_FAILS_ON.items()
    ),
    # argparse's float(), then the range's bounds
    *(
        prometheus(name, "--recorded", RECORDED, "--range", RANGE, "--step", step)
        for name, step in [
            ("aStepOfZero", "0"),
            ("aNegativeStep", "-1"),
            ("aStepAboveADay", "100000"),
            ("aStepThatIsNan", "nan"),
            ("aStepThatIsInfinite", "1e400"),
            ("aStepInPythonsWords", " 1_0 "),
            ("aStepThatIsNotANumber", "0x10"),
        ]
    ),
    prometheus(
        "aReplayOverTheRequestLimit",
        "--recorded",
        RECORDED,
        design=TOO_BIG,
        inputs={MAP: REPLAY_ONLY_MAP, RECORDED: STARTER_RECORDED},
    ),
    # of two problems, the one Python met first: the mapping, the recording, the range, the design
    {
        **prometheus("aMissingMappingAndARecordingThatIsNotJson", inputs={RECORDED: "{"}),
        "args": ["calibrate", "prometheus", FILE, "--map", MISSING, "--recorded", RECORDED],
    },
    {
        **prometheus("aMissingMappingAndAUrlThatIsNotHttp"),
        "args": ["calibrate", "prometheus", FILE, "--map", MISSING, "--url", "ftp://prometheus"],
    },
    prometheus(
        "aRecordingThatIsNotJsonAndARangeThatIsNotOne",
        "--recorded",
        RECORDED,
        "--range",
        "yesterday",
        inputs={MAP: STARTER_MAP, RECORDED: "{"},
    ),
    prometheus(
        "aRangeThatIsNotOneForAnInvalidDesign",
        "--recorded",
        RECORDED,
        "--range",
        "yesterday",
        design=INVALID,
    ),
    # the design is read last, after the range
    {
        **{
            k: v
            for k, v in prometheus("aMissingMappingForADesignThatIsNotJson").items()
            if k != "design"
        },
        "text": NOT_JSON,
        "args": ["calibrate", "prometheus", FILE, "--map", MISSING, "--recorded", RECORDED],
    },
    {
        **{
            k: v
            for k, v in prometheus(
                "aRangeThatIsNotOneForADesignThatIsNotJson",
                "--recorded",
                RECORDED,
                "--range",
                "yesterday",
            ).items()
            if k != "design"
        },
        "text": NOT_JSON,
    },
    prometheus("anInvalidDesign", "--recorded", RECORDED, design=INVALID),
    {
        **{
            k: v
            for k, v in prometheus(
                "aDesignWithWindowsLineEndsThatIsNotJson", "--recorded", RECORDED
            ).items()
            if k != "design"
        },
        "bytes": base64.b64encode(b"\r\n\r\n x").decode("ascii"),
    },
    # an option given empty is not given: Python tested each by its truth
    prometheus("anEmptyRange", "--recorded", RECORDED, "--range", ""),
    prometheus("anEmptyOut", "--recorded", RECORDED, "-o", ""),
    prometheus("anEmptyRecord", "--recorded", RECORDED, "--record", ""),
    prometheus("anEmptyRecording", "--recorded", ""),
    prometheus("anEmptyUrl", "--url", ""),
    # a file named as pathlib names it
    {
        **prometheus("aMappingNamedFromHere"),
        "args": [
            "calibrate",
            "prometheus",
            FILE,
            "--map",
            "./no-such-mapping.yaml",
            "--recorded",
            RECORDED,
        ],
    },
    # a recording Python failed to read (AttributeError), and one whose range is refused
    *(
        prometheus(name, "--recorded", RECORDED, inputs={MAP: STARTER_MAP, RECORDED: text})
        for name, text in [("aRecordingThatIsAList", "[1, 2]"), ("aRecordingThatIsNull", "null")]
    ),
    prometheus(
        "aRecordingWhoseStepIsRefused",
        "--recorded",
        RECORDED,
        inputs={MAP: STARTER_MAP, RECORDED: STEP_ZERO_RECORDED},
    ),
    prometheus(
        "aRecordingWithoutASource",
        "--recorded",
        RECORDED,
        inputs={MAP: STARTER_MAP, RECORDED: UNNAMED_RECORDED},
    ),
    {
        **{
            k: v
            for k, v in prometheus("aDesignThatIsNotJson", "--recorded", RECORDED).items()
            if k != "design"
        },
        "text": NOT_JSON,
    },
    {
        **prometheus("aMissingDesign"),
        "args": ["calibrate", "prometheus", MISSING, "--map", MAP, "--recorded", RECORDED],
    },
    {
        **prometheus("withoutAMapping"),
        "args": ["calibrate", "prometheus", FILE, "--recorded", RECORDED],
    },
    prometheus("bothSources", "--recorded", RECORDED, "--url", "http://prometheus:9090"),
    prometheus("noSource"),
    {"name": "noSourceKind", "design": STARTER, "args": ["calibrate"]},
    otlp("traces", "--traces", TRACES, "--map", MAP),
    otlp("tracesWithoutAMapping", "--traces", TRACES),
    otlp("tracesToAFile", "--traces", TRACES, "--map", MAP, "-o", OUT_FILE),
    otlp("anEmptyOtlpMapping", "--traces", TRACES, "--map", ""),
    otlp("tracesToAnEmptyOut", "--traces", TRACES, "--map", MAP, "-o", ""),
    {**otlp("missingTraces"), "args": ["calibrate", "otlp", FILE, "--traces", MISSING]},
    otlp("tracesThatAreNotJson", "--traces", TRACES, inputs={TRACES: "not json"}),
    otlp("tracesThatAreNotAnExport", "--traces", TRACES, inputs={TRACES: "[1, 2]"}),
    otlp(
        "anOtlpMappingThatIsNotYaml",
        "--traces",
        TRACES,
        "--map",
        MAP,
        inputs={TRACES: CHECKOUT_TRACES, MAP: "a: [1"},
    ),
    *(
        otlp(
            f"anOtlpMappingWith{kind}",
            "--traces",
            TRACES,
            "--map",
            MAP,
            inputs={TRACES: CHECKOUT_TRACES, MAP: text},
        )
        for kind, text in YAML_PYTHON_FAILS_ON.items()
    ),
    otlp(
        "tracesReplayedOverTheRequestLimit",
        "--traces",
        TRACES,
        "--map",
        MAP,
        inputs={TRACES: compressed(CHECKOUT_TRACES, 100_000), MAP: OTLP_MAP},
    ),
    otlp("tracesForAnInvalidDesign", "--traces", TRACES, design=INVALID),
    # of two problems, the one Python met first: the mapping, the design, the traces
    {
        **otlp("aMissingOtlpMappingForAnInvalidDesign", design=INVALID),
        "args": ["calibrate", "otlp", FILE, "--traces", TRACES, "--map", MISSING],
    },
    {
        **{
            k: v
            for k, v in otlp(
                "tracesThatAreNotJsonForADesignThatIsNot",
                "--traces",
                TRACES,
                inputs={TRACES: NOT_JSON_ON_LINE_2},
            ).items()
            if k != "design"
        },
        "text": NOT_JSON,
    },
    otlp(
        "tracesThatAreNotJsonForAnInvalidDesign",
        "--traces",
        TRACES,
        design=INVALID,
        inputs={TRACES: NOT_JSON_ON_LINE_2},
    ),
    otlp("withoutTraces"),
]


BASELINE = "{baseline}"
"""Stands for the baseline design's file (check --baseline)."""
JSON_OUT = "{json}"
"""Stands for the file check writes its JSON result to (--json-out)."""
NOWHERE = "{nowhere}"
"""Stands for a file in a folder that does not exist."""


def starter(**objectives: float) -> Json:
    """The starter as the Python engine's check tests check it: a short run, no scenarios, and
    two objectives."""
    d = copy.deepcopy(STARTER)
    d["workload"].update(durationS=12, warmupS=2)
    d["scenarios"] = []
    d["objectives"] = {"latencyP95Ms": 400.0, "availability": 0.99, **objectives}
    return d


def fewer_api_replicas(document: Json) -> Json:
    """The design with one API replica: slower than the starter, a regression against it."""
    d = copy.deepcopy(document)
    next(n for n in d["architecture"]["nodes"] if n["id"] == "api")["config"]["replicas"] = 1
    return d


CHECKED = starter()
STRICT = starter(latencyP95Ms=1.0)
SLOWER = fewer_api_replicas(CHECKED)
WITH_SCENARIOS = {
    **copy.deepcopy(STARTER),
    "workload": {**STARTER["workload"], "durationS": 12, "warmupS": 2},
}
NAMED = {
    **copy.deepcopy(CHECKED),
    "metadata": {**CHECKED["metadata"], "name": "Café → 東京 checkout"},
}
DESIGN_IN_GIT = "archsim/design.json"


def committed(content: Json | list[Any] | bytes, file: str = DESIGN_IN_GIT) -> Json:
    """A repository whose one commit holds the design's file (archsim/design.json unless
    named), the case's design beside it."""
    data = (
        content if isinstance(content, bytes) else json.dumps(content, ensure_ascii=False).encode()
    )
    return {"files": {file: base64.b64encode(data).decode("ascii")}, "file": file}


def check(name: str, *args: str, **case: Any) -> Json:
    """A check of the starter, as the GitHub Action runs it."""
    return {"name": name, "design": CHECKED, "args": ["check", FILE, *args], **case}


CHECKS: list[Json] = [
    check("aDesignMeetingItsObjectives"),
    check("aDesignMissingItsObjectives", design=STRICT),
    check("objectivesMissedFailingOnRegressionsOnly", "--fail-on", "regression", design=STRICT),
    check("failingOnBoth", "--fail-on", "objectives", "--fail-on", "regression", design=STRICT),
    check("asJson", "--format", "json"),
    check("toAFile", "--out", OUT_FILE),
    check("asJsonToAFile", "--format", "json", "--out", OUT_FILE),
    check("withTheJsonToo", "--json-out", JSON_OUT),
    check("withAReportUrl", "--report-url", "https://example.test/runs/1"),
    check("aScenarioOfTheDesign", "--scenario", "one-api-replica", design=WITH_SCENARIOS),
    check("aScenarioTheDesignLacks", "--scenario", "nope"),
    check(
        "againstASlowerBaseline", "--baseline", BASELINE, "--fail-on", "regression", baseline=SLOWER
    ),
    check(
        "againstAFasterBaseline",
        "--baseline",
        BASELINE,
        "--fail-on",
        "regression",
        design=SLOWER,
        baseline=CHECKED,
    ),
    check(
        "againstAFasterBaselineAsJson",
        "--baseline",
        BASELINE,
        "--format",
        "json",
        design=SLOWER,
        baseline=CHECKED,
    ),
    check("againstItself", "--baseline", BASELINE, "--fail-on", "regression", baseline=CHECKED),
    # thresholds, read as Python's float() reads them
    check(
        "againstAFasterBaselineWithLooseThresholds",
        "--baseline",
        BASELINE,
        "--fail-on",
        "regression",
        "--max-p95-increase",
        "1e3",
        "--max-success-drop",
        " 0.5 ",
        "--max-cost-increase",
        "inf",
        "--max-knee-drop",
        "1_0",
        design=SLOWER,
        baseline=CHECKED,
    ),
    check(
        "aThresholdThatIsNan",
        "--baseline",
        BASELINE,
        "--fail-on",
        "regression",
        "--max-p95-increase",
        "nan",
        "--format",
        "json",
        design=SLOWER,
        baseline=CHECKED,
    ),
    # a baseline that cannot be simulated is a note, not a failure
    check(
        "againstABaselineThatIsNotADesign",
        "--baseline",
        BASELINE,
        "--format",
        "json",
        baseline=[1, 2],
    ),
    check("againstAnInvalidBaseline", "--baseline", BASELINE, "--format", "json", baseline=INVALID),
    check("againstABaselineThatIsNotJson", "--baseline", BASELINE, baseline=NOT_JSON),
    check("againstAMissingBaseline", "--baseline", MISSING),
    # what cannot be checked: exit code 2
    check("anInvalidDesign", design=INVALID),
    check("aDesignThatIsNotADesign", design=[1, 2]),
    {**{k: v for k, v in check("aDesignThatIsNotJson").items() if k != "design"}, "text": NOT_JSON},
    {**check("aMissingDesign"), "args": ["check", MISSING]},
    check("aRunOverTheRequestLimit", design=TOO_BIG),
    check("anOutputThatCannotBeWritten", "--out", NOWHERE),
    check("aJsonOutputThatCannotBeWritten", "--json-out", NOWHERE),
    # an option given empty is not given
    check("optionsGivenEmpty", "--baseline", "", "--out", "", "--json-out", "", "--report-url", ""),
    check("anEmptyScenario", "--scenario", ""),
    check("anEmptyScenarioAsJson", "--scenario", "", "--format", "json"),
    check("anEmptyRef", "--baseline-ref", ""),
    # the baseline at a git ref
    check(
        "againstTheDesignAtARef",
        "--baseline-ref",
        "HEAD",
        "--fail-on",
        "regression",
        design=SLOWER,
        repo=committed(CHECKED),
    ),
    check(
        "aDesignNewAtTheRef",
        "--baseline-ref",
        "HEAD",
        "--fail-on",
        "regression",
        repo={**committed(CHECKED), "file": "archsim/new.json"},
    ),
    check("aRefThatIsNotThere", "--baseline-ref", "origin/mian", repo=committed(CHECKED)),
    check("aRefBeforeTheFirstCommit", "--baseline-ref", "HEAD~1", repo=committed(CHECKED)),
    check("aDesignOutsideARepository", "--baseline-ref", "HEAD"),
    check(
        "aBaselineAtARefInAFolderNamedInUnicode",
        "--baseline-ref",
        "HEAD",
        "--fail-on",
        "regression",
        design=SLOWER,
        repo=committed(CHECKED, "archsim/東京 café/design.json"),
    ),
    check(
        "aBaselineAtARefNamedInUnicode",
        "--baseline-ref",
        "HEAD",
        "--format",
        "json",
        design=NAMED,
        repo=committed(NAMED),
    ),
    check(
        "aBaselineAtARefWithAByteOrderMark",
        "--baseline-ref",
        "HEAD",
        "--format",
        "json",
        repo=committed(codecs.BOM_UTF8 + json.dumps(CHECKED).encode()),
    ),
    check(
        "aBaselineAtARefInUtf16",
        "--baseline-ref",
        "HEAD",
        "--format",
        "json",
        repo=committed(json.dumps(CHECKED).encode("utf-16")),
    ),
    check(
        "aBaselineAtARefInUtf16WithoutAMark",
        "--baseline-ref",
        "HEAD",
        "--format",
        "json",
        repo=committed(json.dumps(CHECKED).encode("utf-16-le")),
    ),
    check(
        "aBaselineAtARefThatIsNotText",
        "--baseline-ref",
        "HEAD",
        repo=committed(bytes([0x7B, 0xFF])),
    ),
    check(
        "aBaselineAtARefThatIsNotJson", "--baseline-ref", "HEAD", repo=committed(NOT_JSON.encode())
    ),
    # the breaking point, swept on one load grid for the design and its baseline
    check("withItsBreakingPoint", "--knee", "--format", "json"),
    check(
        "againstAFasterBaselineWithBreakingPoints",
        "--knee",
        "--baseline",
        BASELINE,
        "--fail-on",
        "regression",
        design=SLOWER,
        baseline=CHECKED,
    ),
    # usage errors: exit code 2
    check("bothBaselines", "--baseline", BASELINE, "--baseline-ref", "HEAD", baseline=CHECKED),
    check("aFailOnThatIsNotOne", "--fail-on", "bogus"),
    check("aFormatThatIsNotOne", "--format", "xml"),
    check("aRefStartingWithADash", "--baseline-ref=-x"),
    check("aThresholdThatIsNotANumber", "--max-p95-increase", "0x10"),
    {"name": "noDesign", "args": ["check"]},
]


class StubPrometheus(http.server.BaseHTTPRequestHandler):
    """A Prometheus server for a calibration's queries: ``recorded`` answers each query with
    its recorded response, ``badData`` refuses every query as Prometheus refuses bad PromQL,
    ``html`` answers as a gateway's error page does."""

    behaviour = "recorded"
    recorded: Json = json.loads(STARTER_RECORDED)["responses"]

    def do_GET(self) -> None:
        query = parse_qs(urlsplit(self.path).query).get("query", [""])[0]
        if self.behaviour == "html":
            self.answer(200, "text/html", "<html><body>Bad gateway</body></html>")
        elif self.behaviour == "recorded" and query in self.recorded:
            self.answer(200, "application/json", json.dumps(self.recorded[query]))
        else:
            error = {"status": "error", "errorType": "bad_data", "error": "parse error at char 7"}
            self.answer(400, "application/json", json.dumps(error))

    def answer(self, status: int, kind: str, body: str) -> None:
        data = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, format: str, *args: Any) -> None:
        pass


@contextlib.contextmanager
def stub_server(behaviour: str | None) -> Iterator[str]:
    """The base URL of a stub server that behaves so, while the block runs; for ``closed``, a
    port nothing listens on."""
    if behaviour is None:
        yield ""
        return
    if behaviour == "closed":
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        yield f"http://127.0.0.1:{port}"
        return
    handler = type("Stub", (StubPrometheus,), {"behaviour": behaviour})
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_address[1]}"
    finally:
        server.shutdown()
        server.server_close()


@contextlib.contextmanager
def fixed_clock() -> Iterator[None]:
    """The calibrations' clock stopped at NOW."""
    saved = calibration_apply.now_iso, calibration_otlp.now_iso
    calibration_apply.now_iso = calibration_otlp.now_iso = lambda: NOW
    try:
        yield
    finally:
        calibration_apply.now_iso, calibration_otlp.now_iso = saved


COMMANDS: dict[str, list[Json]] = {
    "run": RUNS,
    "validate": VALIDATES,
    "analyze": ANALYZES,
    "compare": COMPARES,
    "export": EXPORTS,
    "import": IMPORTS,
    "calibrate": CALIBRATES,
    "check": CHECKS,
    "catalog": [{"name": "theCatalog", "args": ["catalog"]}],
    "schema": [{"name": "theSchema", "args": ["schema"]}],
}


def repository(spec: Json, folder: Path) -> Path:
    """A git repository with one commit of the files ``spec`` names (in base64); the file of
    the case's design in it."""
    folder.mkdir()

    def git(*args: str) -> None:
        subprocess.run(["git", *args], cwd=folder, check=True, capture_output=True, timeout=60)

    git("init", "-q")
    git("config", "user.email", "test@example.com")
    git("config", "user.name", "Test")
    git("config", "core.autocrlf", "false")
    git("config", "commit.gpgsign", "false")  # a fixture, whatever the developer's git signs with
    for name, data in spec["files"].items():
        (folder / name).parent.mkdir(parents=True, exist_ok=True)
        (folder / name).write_bytes(base64.b64decode(data))
    git("add", ".")
    git("commit", "-q", "-m", "base")
    path = folder / spec["file"]
    path.parent.mkdir(parents=True, exist_ok=True)
    return path


def run(case: Json, folder: Path) -> Json:
    path = folder / f"{case['name']}.json"
    if "repo" in case:
        path = repository(case["repo"], folder / f"{case['name']}-repo")
    if "design" in case:
        path.write_text(json.dumps(case["design"]), encoding="utf-8")
    elif "text" in case:
        path.write_text(case["text"], encoding="utf-8", newline="")  # its line ends as they are
    elif "bytes" in case:
        path.write_bytes(base64.b64decode(case["bytes"]))
    names = {FILE: str(path), MISSING: str(folder / "missing.json")}
    names |= {REPORT: str(folder / f"{case['name']}-report.json")}
    names |= {OUT_FILE: str(folder / f"{case['name']}-out.txt")}
    names |= {RECORD: str(folder / f"{case['name']}-record.json")}
    names |= {BASELINE: str(folder / f"{case['name']}-baseline.json")}
    names |= {JSON_OUT: str(folder / f"{case['name']}-result.json")}
    names |= {NOWHERE: str(folder / "nowhere" / "check.md")}
    if "baseline" in case:
        baseline = case["baseline"]
        text = baseline if isinstance(baseline, str) else json.dumps(baseline)
        Path(names[BASELINE]).write_text(text, encoding="utf-8", newline="")
    for placeholder, text in case.get("inputs", {}).items():
        names[placeholder] = str(folder / f"{case['name']}-{INPUT_FILES[placeholder]}")
        Path(names[placeholder]).write_text(text, encoding="utf-8", newline="")
    with stub_server(case.get("server")) as url, fixed_clock():
        if url:
            names[URL] = url
        args = [names.get(a, a) for a in case["args"]]
        recorded: Json = {**case, **called(args, case.get("stdin", ""))}
    for key in ("stdout", "stderr", "crash"):
        if key in recorded:
            recorded[key] = placeheld(recorded[key], names)
    if REPORT in case["args"] and case["args"][0] == "run":
        report = json.loads(Path(names[REPORT]).read_text(encoding="utf-8"))
        report["provenance"]["wallSeconds"] = 0.0
        report["provenance"]["python"] = "normalized"
        report["provenance"]["simpy"] = "normalized"
        recorded["report"] = report
    elif written := {
        p: placeheld(Path(names[p]).read_text(encoding="utf-8"), names)
        for p in (*OUTPUT_FILES, JSON_OUT)
        if p in case["args"] and Path(names[p]).exists()
    }:
        recorded["files"] = written
    return recorded


def called(args: list[str], stdin: str) -> Json:
    """The CLI run with ``args`` and ``stdin``: its exit code and what it printed; an uncaught
    exception as ``crash`` (Python's traceback, exit code 1), argparse's refusal as ``usage``."""
    out, err = io.StringIO(), io.StringIO()
    crash, usage = None, False
    standard_input = sys.stdin
    sys.stdin = io.TextIOWrapper(io.BytesIO(stdin.encode("utf-8")), encoding="utf-8")
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        try:
            code = cli.main(args)
        except SystemExit as exc:
            code, usage = int(exc.code or 0), True
        except Exception as exc:  # what Python left uncaught: a traceback, exit code 1
            code, crash = 1, f"{type(exc).__name__}: {exc}"
        finally:
            sys.stdin = standard_input
    result: Json = {"exitCode": code, "stdout": out.getvalue()}
    if usage:
        result["usage"] = True
    if crash is None:
        result["stderr"] = err.getvalue()
    else:
        result["crash"] = crash
    return result


def placeheld(text: str, names: dict[str, str]) -> str:
    """``text`` with each file's path, as it is and as its repr writes it (an exception's
    message), replaced by its placeholder."""
    for placeholder, path in names.items():
        text = text.replace(repr(path)[1:-1], placeholder).replace(path, placeholder)
    return text


def export(command: str) -> bytes:
    with tempfile.TemporaryDirectory() as folder:
        cases = [run(case, Path(folder)) for case in COMMANDS[command]]
    text = json.dumps({"generator": GENERATOR, "cases": cases}, separators=(",", ":"))
    return gzip.compress(text.encode(), mtime=0)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    parser.add_argument("--check", action="store_true", help="exit 1 if a fresh export differs")
    parser.add_argument("command", nargs="*", help="the commands to record (default: every one)")
    args = parser.parse_args(argv)
    differs = 0
    for command in args.command or COMMANDS:
        out = FOLDER / f"{command}-cases.json.gz"
        data = export(command)
        if not args.check:
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_bytes(data)
            print(f"wrote {out}")
        elif out.exists() and gzip.decompress(out.read_bytes()) == gzip.decompress(data):
            print(f"{out.name} matches the frozen engine's CLI")
        else:
            print(f"{out.name} differs from a fresh export: run scripts/export_cli_cases.py")
            differs = 1
    return differs


if __name__ == "__main__":
    sys.exit(main())
