"""Golden files for the Java port: the inputs and outputs of every deterministic engine function,
recorded from the frozen Python engine (0.16.0) so a Java test can feed each function the
recorded input and compare its output, without Python. See parity/README.md for the format.

What is recorded:

* per template (``examples/*.json``) and each of its scenarios: loading, migration, validation,
  scenario resolution and auto-sizing, the simulation input, design review, input provenance,
  technology tiers, the load and instant estimates, sizing, cost, confidence, quick check and
  the full ``analyze_document``; the exports and their import round trips;
* per template, two short fixed-seed simulations (the base design and one scenario, on a
  compressed time axis): their reports are recorded as *inputs* to the functions that read a
  report (findings, report confidence, post-run cost, explain formulas, SLOs, compare, seeds,
  check, sweep rows, digests);
* template-independent catalogs: component registry, document JSON Schema, pricing, assumptions
  and constants, formatting helpers on value grids;
* fixtures: older schema versions, invalid documents, importer inputs and calibration fits.

Every output is computed by calling the engine on the recorded JSON input (``golden.adapters``),
so an entry's output is a function of its input alone.

The modules, from the bottom up:

* ``paths``: where the exporter reads and writes;
* ``codec``: the JSON conventions (camelCase, canonical JSON, JSON Pointers, recorded errors);
* ``adapters``: the registry of engine functions an entry may name, one module per engine area;
* ``file``: one golden file under construction (its tree, entries and references);
* ``files``: what each golden file records, one module per file or group of files;
* ``build``, ``store`` and ``compare``: building the files, writing them with their manifest,
  and checking them against the committed ones;
* ``cli``: the command line (``scripts/export_golden.py``).
"""

from __future__ import annotations

FORMAT = "archsim.golden/1"
"""The format every golden file and the manifest name."""

GENERATOR = "uv run python scripts/export_golden.py"
"""The command that writes the files (recorded in the manifest)."""
