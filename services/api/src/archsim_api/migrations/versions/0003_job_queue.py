"""Durable job queue: leases on runs, cancel requests, worker heartbeats.

Revision ID: 0003
Revises: 0002
Create Date: 2026-10-27
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0003"
down_revision = "0002"
branch_labels = None
depends_on = None


def upgrade() -> None:
    with op.batch_alter_table("simulation_runs") as batch:
        batch.add_column(sa.Column("attempts", sa.Integer(), nullable=False, server_default="0"))
        batch.add_column(sa.Column("lease_owner", sa.String(64), nullable=True))
        batch.add_column(sa.Column("lease_expires_at", sa.DateTime(timezone=True), nullable=True))
        batch.add_column(
            sa.Column("cancel_requested_at", sa.DateTime(timezone=True), nullable=True)
        )
    op.create_index("ix_runs_status_created", "simulation_runs", ["status", "created_at"])
    op.create_table(
        "job_workers",
        sa.Column("id", sa.String(64), primary_key=True),
        sa.Column("hostname", sa.String(255), nullable=False),
        sa.Column("pid", sa.Integer(), nullable=False),
        sa.Column("slots", sa.Integer(), nullable=False),
        sa.Column("running", sa.Integer(), nullable=False),
        sa.Column("status", sa.String(16), nullable=False),
        sa.Column("started_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("heartbeat_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ix_job_workers_heartbeat_at", "job_workers", ["heartbeat_at"])


def downgrade() -> None:
    op.drop_index("ix_job_workers_heartbeat_at", table_name="job_workers")
    op.drop_table("job_workers")
    op.drop_index("ix_runs_status_created", table_name="simulation_runs")
    with op.batch_alter_table("simulation_runs") as batch:
        batch.drop_column("cancel_requested_at")
        batch.drop_column("lease_expires_at")
        batch.drop_column("lease_owner")
        batch.drop_column("attempts")
