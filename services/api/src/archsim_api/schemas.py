"""Request/response models (camelCase JSON) for the v1 API."""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Annotated, Any, Literal

from pydantic import AfterValidator, BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


def _as_utc(v: datetime) -> datetime:
    # SQLite does not store time zones; values are written in UTC, so label them as such.
    return v.replace(tzinfo=UTC) if v.tzinfo is None else v.astimezone(UTC)


UtcDateTime = Annotated[datetime, AfterValidator(_as_utc)]


class ApiModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, from_attributes=True)


class IssueOut(ApiModel):
    severity: Literal["error", "warning"]
    code: str
    message: str
    node_id: str | None = None
    edge_id: str | None = None
    path: str | None = None
    hint: str | None = None


class ErrorDetail(ApiModel):
    message: str
    issues: list[IssueOut] = Field(default_factory=list)


class LimitsOut(ApiModel):
    max_requests: int
    max_wall_seconds: float
    max_concurrent_runs: int


class MetaOut(ApiModel):
    engine_version: str
    schema_version: str
    report_version: str
    database: str
    limits: LimitsOut


class ValidateRequest(ApiModel):
    document: dict[str, Any]
    scenario_id: str | None = None


class ValidateResponse(ApiModel):
    valid: bool
    issues: list[IssueOut]


class ExampleOut(ApiModel):
    id: str
    name: str
    description: str | None = None
    tags: list[str] = Field(default_factory=list)
    scenario_count: int = 0


class ProjectCreate(ApiModel):
    name: str | None = Field(default=None, min_length=1, max_length=120)
    description: str | None = Field(default=None, max_length=4000)
    document: dict[str, Any] | None = None
    example_id: str | None = Field(default=None, max_length=100)
    reuse_unedited: bool = Field(
        default=False,
        description=(
            "With exampleId and no name: return the most recent copy of that example that was "
            "never saved (revision 1, status 200) instead of creating another one."
        ),
    )


class ProjectUpdate(ApiModel):
    name: str | None = Field(default=None, min_length=1, max_length=120)
    description: str | None = Field(default=None, max_length=4000)
    document: dict[str, Any] | None = None
    expected_revision: int | None = Field(
        default=None,
        description="If set, the update fails with 409 when the stored revision differs.",
    )


class ProjectSummaryOut(ApiModel):
    id: str
    name: str
    description: str | None
    revision: int
    created_at: UtcDateTime
    updated_at: UtcDateTime
    node_count: int
    scenario_count: int


class ProjectOut(ProjectSummaryOut):
    document: dict[str, Any]


class RunCreate(ApiModel):
    scenario_id: str | None = Field(default=None, max_length=64)
    label: str | None = Field(default=None, max_length=200)
    document: dict[str, Any] | None = Field(
        default=None,
        description="Simulate this document (e.g. unsaved edits) instead of the saved one. "
        "It is stored with the run, so results stay reproducible.",
    )
    seeds: int = Field(
        default=1,
        ge=1,
        le=10,
        description="Above 1: a seed group, one run per seed from the document's seed (base) to "
        "base + seeds - 1, read together with GET /run-groups/{groupId}. Up to 5 in hosted "
        "workspaces, 10 locally. The answer is the group's first run.",
    )
    mode: Literal["full", "sampled"] = Field(
        default="full",
        description="'sampled': a quick check, answered from a steady sample when the design "
        "qualifies (constant traffic, no faults, a long enough run; see /analyze quickCheck). "
        "Stored with the run, so a rerun uses it too.",
    )


class BatchRunCreate(ApiModel):
    scenario_ids: list[str | None] = Field(min_length=1, max_length=10)
    document: dict[str, Any] | None = None


class RunOut(ApiModel):
    id: str
    project_id: str
    label: str | None
    scenario_id: str | None
    scenario_name: str | None
    status: Literal["queued", "running", "succeeded", "failed", "cancelled"]
    progress: float
    error: dict[str, Any] | None
    seed: int
    engine_version: str
    input_digest: str | None
    result_digest: str | None
    summary: dict[str, Any] | None
    wall_seconds: float | None
    created_at: UtcDateTime
    started_at: UtcDateTime | None
    finished_at: UtcDateTime | None
    reproduces_run_id: str | None = None
    group_id: str | None = None
    seed_index: int | None = None
    mode: Literal["full", "sampled"] = "full"
