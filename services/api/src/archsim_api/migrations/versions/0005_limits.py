"""Rate limits and per-run limits: a ``rate_limits`` counter table shared by every API instance,
and the largest each run may grow (from its workspace's limits when it was queued).

Revision ID: 0005
Revises: 0004
Create Date: 2026-10-27
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0005"
down_revision = "0004"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "rate_limits",
        sa.Column("key", sa.String(200), primary_key=True),
        sa.Column("window_start", sa.BigInteger(), primary_key=True, autoincrement=False),
        sa.Column("count", sa.Integer(), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ix_rate_limits_expires_at", "rate_limits", ["expires_at"])
    with op.batch_alter_table("simulation_runs") as batch:
        batch.add_column(sa.Column("max_requests", sa.BigInteger(), nullable=True))
        batch.add_column(sa.Column("max_wall_seconds", sa.Float(), nullable=True))


def downgrade() -> None:
    with op.batch_alter_table("simulation_runs") as batch:
        batch.drop_column("max_wall_seconds")
        batch.drop_column("max_requests")
    op.drop_index("ix_rate_limits_expires_at", table_name="rate_limits")
    op.drop_table("rate_limits")
