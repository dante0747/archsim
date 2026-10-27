"""Review: comments on parts of a design, review sign-offs and the history of changes.

Revision ID: 0008
Revises: 0007
Create Date: 2026-10-27
"""

from __future__ import annotations

from datetime import datetime

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision = "0008"
down_revision = "0007"
branch_labels = None
depends_on = None

JSONType = sa.JSON().with_variant(postgresql.JSONB(), "postgresql")


def _ts(name: str, nullable: bool = False) -> sa.Column[datetime]:
    return sa.Column(name, sa.DateTime(timezone=True), nullable=nullable)


def _user(name: str) -> sa.Column[str]:
    return sa.Column(
        name, sa.String(32), sa.ForeignKey("users.id", ondelete="SET NULL"), nullable=True
    )


def upgrade() -> None:
    op.create_table(
        "comments",
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
        sa.Column("anchor_kind", sa.String(16), nullable=False),
        sa.Column("anchor_id", sa.String(64), nullable=True),
        sa.Column(
            "run_id",
            sa.String(32),
            sa.ForeignKey("simulation_runs.id", ondelete="SET NULL"),
            nullable=True,
        ),
        sa.Column(
            "parent_id",
            sa.String(32),
            sa.ForeignKey("comments.id", ondelete="CASCADE"),
            nullable=True,
        ),
        _user("author_id"),
        sa.Column("body", sa.Text(), nullable=False),
        _ts("created_at"),
        _ts("edited_at", nullable=True),
        _ts("resolved_at", nullable=True),
        _user("resolved_by"),
    )
    op.create_index("ix_comments_project_created", "comments", ["project_id", "created_at"])
    op.create_index("ix_comments_run_id", "comments", ["run_id"])
    op.create_index("ix_comments_parent_id", "comments", ["parent_id"])

    op.create_table(
        "review_signoffs",
        sa.Column("id", sa.String(32), primary_key=True),
        sa.Column(
            "project_id",
            sa.String(32),
            sa.ForeignKey("projects.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "run_id",
            sa.String(32),
            sa.ForeignKey("simulation_runs.id", ondelete="SET NULL"),
            nullable=True,
        ),
        sa.Column("project_revision", sa.Integer(), nullable=False),
        sa.Column("input_digest", sa.String(64), nullable=True),
        sa.Column("result_digest", sa.String(64), nullable=True),
        _user("reviewer_id"),
        sa.Column("decision", sa.String(24), nullable=False),
        sa.Column("note", sa.Text(), nullable=True),
        _ts("created_at"),
    )
    op.create_index("ix_signoffs_project_created", "review_signoffs", ["project_id", "created_at"])
    op.create_index("ix_review_signoffs_run_id", "review_signoffs", ["run_id"])

    op.create_table(
        "audit_events",
        sa.Column(
            "id",
            sa.BigInteger().with_variant(sa.Integer(), "sqlite"),
            primary_key=True,
            autoincrement=True,
        ),
        sa.Column(
            "workspace_id",
            sa.String(32),
            sa.ForeignKey("workspaces.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("project_id", sa.String(32), nullable=True),
        _user("actor_user_id"),
        sa.Column("actor_kind", sa.String(16), nullable=False),
        sa.Column("action", sa.String(64), nullable=False),
        sa.Column("target_kind", sa.String(32), nullable=False),
        sa.Column("target_id", sa.String(64), nullable=True),
        sa.Column("meta", JSONType, nullable=True),
        sa.Column("ip", sa.String(64), nullable=True),
        _ts("created_at"),
    )
    op.create_index("ix_audit_workspace_id", "audit_events", ["workspace_id", "id"])
    op.create_index("ix_audit_project_id", "audit_events", ["project_id", "id"])


def downgrade() -> None:
    op.drop_index("ix_audit_project_id", table_name="audit_events")
    op.drop_index("ix_audit_workspace_id", table_name="audit_events")
    op.drop_table("audit_events")
    op.drop_index("ix_review_signoffs_run_id", table_name="review_signoffs")
    op.drop_index("ix_signoffs_project_created", table_name="review_signoffs")
    op.drop_table("review_signoffs")
    op.drop_index("ix_comments_parent_id", table_name="comments")
    op.drop_index("ix_comments_run_id", table_name="comments")
    op.drop_index("ix_comments_project_created", table_name="comments")
    op.drop_table("comments")
