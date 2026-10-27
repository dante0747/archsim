"""ArchSim engine: architecture schema, component registry and discrete-event simulator."""

from typing import Any

from archsim_engine.analyze import analyze_document
from archsim_engine.compare import compare_reports
from archsim_engine.components import create_default_registry
from archsim_engine.engine import (
    PreparedRun,
    SimulationCancelledError,
    SimulationLimitError,
    SimulationLimits,
    default_registry,
    prepare_run,
    quick_check,
    run_simulation,
)
from archsim_engine.issues import DocumentInvalidError, Issue
from archsim_engine.migrations import MigrationError, migrate_document
from archsim_engine.registry import ComponentModel, ComponentRegistry, PortSpec, ui
from archsim_engine.scenarios import load_document, resolve_scenario
from archsim_engine.schema import ArchitectureDocument
from archsim_engine.seeds import summarize_seeds
from archsim_engine.validation import ValidationResult, validate_document
from archsim_engine.version import ENGINE_VERSION, REPORT_VERSION, SCHEMA_VERSION

__all__ = [
    "ENGINE_VERSION",
    "REPORT_VERSION",
    "SCHEMA_VERSION",
    "ArchitectureDocument",
    "ComponentModel",
    "ComponentRegistry",
    "DocumentInvalidError",
    "Issue",
    "MigrationError",
    "PortSpec",
    "PreparedRun",
    "SimulationCancelledError",
    "SimulationLimitError",
    "SimulationLimits",
    "ValidationResult",
    "analyze_document",
    "compare_reports",
    "create_default_registry",
    "default_registry",
    "load_document",
    "migrate_document",
    "prepare_run",
    "quick_check",
    "resolve_scenario",
    "run_simulation",
    "summarize_seeds",
    "ui",
    "validate_document",
]


def document_json_schema() -> dict[str, Any]:
    """JSON Schema of the architecture document (node configs validated per component type)."""
    return ArchitectureDocument.model_json_schema(by_alias=True)
