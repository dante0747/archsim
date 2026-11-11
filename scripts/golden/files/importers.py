"""The importers fixture: ``import_design`` on the compose, Kubernetes and Terraform fixtures of
parity/fixtures, and on text it cannot read."""

from __future__ import annotations

from golden.file import GoldenFile
from golden.paths import FIXTURES

IMPORTS: list[tuple[str, str, str | None, str | None]] = [
    # (case, format, fixture file (else the text below), name)
    ("voting-compose", "compose", "voting-compose.yml", None),
    ("voting-compose-named", "compose", "voting-compose.yml", "Voting app"),
    ("shop-k8s", "k8s", "shop-k8s.yaml", None),
    ("orders-aws-plan", "terraform", "orders-aws-plan.json", None),
]
BAD_IMPORTS: list[tuple[str, str, str]] = [
    ("compose-bad-yaml", "compose", "services: [unclosed\n"),
    ("compose-a-list", "compose", "- a\n- b\n"),
    ("compose-no-services", "compose", "version: '3'\n"),
    ("compose-service-a-string", "compose", "services:\n  web: nginx\n"),
    ("k8s-empty", "k8s", ""),
    ("k8s-not-a-manifest", "k8s", "just text\n"),
    ("terraform-not-json", "terraform", "not json"),
    ("terraform-wrong-shape", "terraform", '{"values": []}'),
    ("terraform-empty-plan", "terraform", "{}"),
    ("unknown-format", "helm", "apiVersion: v2\n"),
]


def importers_file() -> GoldenFile:
    g = GoldenFile("fixtures", name="importers")
    for name, fmt, fixture, title in IMPORTS:
        assert fixture is not None
        text = (FIXTURES / "import" / fixture).read_text(encoding="utf-8")
        g.entry(("cases", name), "import_design", "archsim_engine.importers.import_design",
                {"fmt": fmt, "text": text, "name": title})  # fmt: skip
    for name, fmt, text in BAD_IMPORTS:
        g.entry(("cases", name), "import_design", "archsim_engine.importers.import_design",
                {"fmt": fmt, "text": text, "name": None}, errors=True)  # fmt: skip
    g.cases = len(IMPORTS) + len(BAD_IMPORTS)
    return g
