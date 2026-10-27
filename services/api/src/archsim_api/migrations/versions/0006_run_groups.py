"""Run groups and run modes: a ``run_groups`` table for runs of one input with seeds base to
base + N - 1 (and, later, sweeps), each run's ``group_id`` and ``seed_index``, and the ``mode``
it was simulated in (``full`` or ``sampled``), so a rerun reproduces it.

Revision ID: 0006
Revises: 0005
Create Date: 2026-10-27
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0006"
down_revision = "0005"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "run_groups",
        sa.Column("id", sa.String(32), primary_key=True),
        sa.Column(
            "workspace_id",
            sa.String(32),
            sa.ForeignKey("workspaces.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "project_id",
            sa.String(32),
            sa.ForeignKey("projects.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("kind", sa.String(16), nullable=False),
        sa.Column("base_seed", sa.BigInteger(), nullable=False),
        sa.Column("size", sa.Integer(), nullable=False),
        sa.Column("mode", sa.String(16), nullable=False, server_default="full"),
        sa.Column("scenario_id", sa.String(64), nullable=True),
        sa.Column("label", sa.String(200), nullable=True),
        sa.Column(
            "created_by",
            sa.String(32),
            sa.ForeignKey("users.id", ondelete="SET NULL"),
            nullable=True,
        ),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ix_run_groups_project_id", "run_groups", ["project_id"])
    with op.batch_alter_table("simulation_runs") as batch:
        batch.add_column(sa.Column("group_id", sa.String(32), nullable=True))
        batch.add_column(sa.Column("seed_index", sa.Integer(), nullable=True))
        batch.add_column(sa.Column("mode", sa.String(16), nullable=False, server_default="full"))
        batch.create_foreign_key(
            "fk_runs_group_id", "run_groups", ["group_id"], ["id"], ondelete="SET NULL"
        )
    op.create_index("ix_simulation_runs_group_id", "simulation_runs", ["group_id"])


def downgrade() -> None:
    op.drop_index("ix_simulation_runs_group_id", table_name="simulation_runs")
    with op.batch_alter_table("simulation_runs") as batch:
        batch.drop_constraint("fk_runs_group_id", type_="foreignkey")
        batch.drop_column("mode")
        batch.drop_column("seed_index")
        batch.drop_column("group_id")
    op.drop_index("ix_run_groups_project_id", table_name="run_groups")
    op.drop_table("run_groups")
