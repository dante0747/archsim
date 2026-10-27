"""Share links: ``shared_reports``, a frozen copy of one run's report that anyone with its random
link can read (``GET /public/shares/{slug}``) until it expires or is revoked.

A snapshot rather than a link to the run: run digests are guessable and cannot be revoked, runs
go when their project is deleted, and the report format changes between versions.

Revision ID: 0007
Revises: 0005 (W9's 0006 and W12's 0008 are chained in order when they are merged)
Create Date: 2026-10-27
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision = "0007"
down_revision = "0006"
branch_labels = None
depends_on = None

JSONType = sa.JSON().with_variant(postgresql.JSONB(), "postgresql")


def upgrade() -> None:
    op.create_table(
        "shared_reports",
        sa.Column("id", sa.String(32), primary_key=True),
        sa.Column("slug", sa.String(32), nullable=False),
        sa.Column(
            "workspace_id",
            sa.String(32),
            sa.ForeignKey(
                "workspaces.id", ondelete="CASCADE", name="fk_shared_reports_workspace_id"
            ),
            nullable=False,
        ),
        sa.Column(
            "project_id",
            sa.String(32),
            sa.ForeignKey("projects.id", ondelete="SET NULL", name="fk_shared_reports_project_id"),
            nullable=True,
        ),
        sa.Column(
            "run_id",
            sa.String(32),
            sa.ForeignKey(
                "simulation_runs.id", ondelete="SET NULL", name="fk_shared_reports_run_id"
            ),
            nullable=True,
        ),
        sa.Column(
            "created_by",
            sa.String(32),
            sa.ForeignKey("users.id", ondelete="SET NULL", name="fk_shared_reports_created_by"),
            nullable=True,
        ),
        sa.Column("title", sa.String(200), nullable=False),
        sa.Column("payload", JSONType, nullable=True),
        sa.Column("payload_version", sa.Integer(), nullable=False),
        sa.Column("report_version", sa.String(16), nullable=False),
        sa.Column("result_digest", sa.String(64), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("view_count", sa.Integer(), server_default="0", nullable=False),
        sa.UniqueConstraint("slug", name="uq_shared_reports_slug"),
    )
    op.create_index(
        "ix_shared_reports_project_created", "shared_reports", ["project_id", "created_at"]
    )
    op.create_index(
        "ix_shared_reports_workspace_created", "shared_reports", ["workspace_id", "created_at"]
    )


def downgrade() -> None:
    op.drop_index("ix_shared_reports_workspace_created", table_name="shared_reports")
    op.drop_index("ix_shared_reports_project_created", table_name="shared_reports")
    op.drop_table("shared_reports")
