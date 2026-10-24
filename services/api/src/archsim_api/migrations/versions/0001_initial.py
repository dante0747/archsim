"""Initial schema: projects and simulation runs.

Revision ID: 0001
Revises:
Create Date: 2026-10-24
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision = "0001"
down_revision = None
branch_labels = None
depends_on = None

JSONType = sa.JSON().with_variant(postgresql.JSONB(), "postgresql")


def upgrade() -> None:
    op.create_table(
        "projects",
        sa.Column("id", sa.String(32), primary_key=True),
        sa.Column("name", sa.String(120), nullable=False),
        sa.Column("description", sa.Text(), nullable=True),
        sa.Column("document", JSONType, nullable=False),
        sa.Column("schema_version", sa.String(16), nullable=False),
        sa.Column("revision", sa.Integer(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_table(
        "simulation_runs",
        sa.Column("id", sa.String(32), primary_key=True),
        sa.Column(
            "project_id",
            sa.String(32),
            sa.ForeignKey("projects.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("label", sa.String(200), nullable=True),
        sa.Column("scenario_id", sa.String(64), nullable=True),
        sa.Column("scenario_name", sa.String(120), nullable=True),
        sa.Column("status", sa.String(16), nullable=False),
        sa.Column("progress", sa.Float(), nullable=False),
        sa.Column("error", JSONType, nullable=True),
        sa.Column("seed", sa.BigInteger(), nullable=False),
        sa.Column("engine_version", sa.String(32), nullable=False),
        sa.Column("input_digest", sa.String(64), nullable=True),
        sa.Column("result_digest", sa.String(64), nullable=True),
        sa.Column("summary", JSONType, nullable=True),
        sa.Column("wall_seconds", sa.Float(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("started_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("finished_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("input_document", JSONType, nullable=False),
        sa.Column("report", JSONType, nullable=True),
    )
    op.create_index("ix_runs_project_created", "simulation_runs", ["project_id", "created_at"])
    op.create_index("ix_simulation_runs_project_id", "simulation_runs", ["project_id"])
    op.create_index("ix_simulation_runs_status", "simulation_runs", ["status"])


def downgrade() -> None:
    op.drop_index("ix_simulation_runs_status", table_name="simulation_runs")
    op.drop_index("ix_simulation_runs_project_id", table_name="simulation_runs")
    op.drop_index("ix_runs_project_created", table_name="simulation_runs")
    op.drop_table("simulation_runs")
    op.drop_table("projects")
