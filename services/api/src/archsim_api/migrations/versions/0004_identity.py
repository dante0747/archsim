"""Identity: users, sign-in identities, workspaces, memberships, invitations, sessions and API
tokens. Projects and runs move into workspaces: everything already stored goes to the
``default`` workspace, owned by the ``local`` user (who every request is without sign-in).

Revision ID: 0004
Revises: 0003
Create Date: 2026-10-27
"""

from __future__ import annotations

from datetime import UTC, datetime

import sqlalchemy as sa
from alembic import op

revision = "0004"
down_revision = "0003"
branch_labels = None
depends_on = None

DEFAULT_WORKSPACE_ID = "default"
LOCAL_USER_ID = "local"


def _ts(name: str, nullable: bool = False) -> sa.Column[datetime]:
    return sa.Column(name, sa.DateTime(timezone=True), nullable=nullable)


def upgrade() -> None:
    op.create_table(
        "users",
        sa.Column("id", sa.String(32), primary_key=True),
        sa.Column("email", sa.String(320), nullable=True),
        sa.Column("name", sa.String(200), nullable=False),
        sa.Column("avatar_url", sa.String(500), nullable=True),
        _ts("created_at"),
        _ts("last_seen_at", nullable=True),
        _ts("disabled_at", nullable=True),
    )
    op.create_index("ix_users_email", "users", ["email"])
    op.create_table(
        "identities",
        sa.Column("id", sa.String(32), primary_key=True),
        sa.Column(
            "user_id", sa.String(32), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False
        ),
        sa.Column("provider", sa.String(32), nullable=False),
        sa.Column("subject", sa.String(255), nullable=False),
        sa.Column("email", sa.String(320), nullable=True),
        sa.Column("email_verified", sa.Boolean(), nullable=False),
        sa.Column("login", sa.String(200), nullable=True),
        _ts("created_at"),
        _ts("last_login_at", nullable=True),
        sa.UniqueConstraint("provider", "subject", name="uq_identities_provider_subject"),
    )
    op.create_index("ix_identities_user_id", "identities", ["user_id"])
    op.create_table(
        "workspaces",
        sa.Column("id", sa.String(32), primary_key=True),
        sa.Column("name", sa.String(120), nullable=False),
        sa.Column("kind", sa.String(16), nullable=False),
        sa.Column("max_concurrent_runs", sa.Integer(), nullable=True),
        sa.Column("max_runs_per_hour", sa.Integer(), nullable=True),
        sa.Column(
            "created_by",
            sa.String(32),
            sa.ForeignKey("users.id", ondelete="SET NULL"),
            nullable=True,
        ),
        _ts("created_at"),
    )
    op.create_table(
        "memberships",
        sa.Column(
            "workspace_id",
            sa.String(32),
            sa.ForeignKey("workspaces.id", ondelete="CASCADE"),
            primary_key=True,
        ),
        sa.Column(
            "user_id",
            sa.String(32),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            primary_key=True,
        ),
        sa.Column("role", sa.String(16), nullable=False),
        _ts("created_at"),
    )
    op.create_index("ix_memberships_user_id", "memberships", ["user_id"])
    op.create_table(
        "invitations",
        sa.Column("id", sa.String(32), primary_key=True),
        sa.Column(
            "workspace_id",
            sa.String(32),
            sa.ForeignKey("workspaces.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("token_hash", sa.String(64), nullable=False, unique=True),
        sa.Column("role", sa.String(16), nullable=False),
        sa.Column("email", sa.String(320), nullable=True),
        sa.Column(
            "created_by",
            sa.String(32),
            sa.ForeignKey("users.id", ondelete="SET NULL"),
            nullable=True,
        ),
        _ts("created_at"),
        _ts("expires_at"),
        _ts("accepted_at", nullable=True),
        sa.Column(
            "accepted_by",
            sa.String(32),
            sa.ForeignKey("users.id", ondelete="SET NULL"),
            nullable=True,
        ),
    )
    op.create_index("ix_invitations_workspace_id", "invitations", ["workspace_id"])
    op.create_table(
        "sessions",
        sa.Column("id", sa.String(64), primary_key=True),
        sa.Column(
            "user_id", sa.String(32), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False
        ),
        _ts("created_at"),
        _ts("last_seen_at"),
        _ts("expires_at"),
        sa.Column("ip", sa.String(64), nullable=True),
        sa.Column("user_agent", sa.String(300), nullable=True),
    )
    op.create_index("ix_sessions_user_id", "sessions", ["user_id"])
    op.create_index("ix_sessions_expires_at", "sessions", ["expires_at"])
    op.create_table(
        "api_tokens",
        sa.Column("id", sa.String(32), primary_key=True),
        sa.Column(
            "user_id", sa.String(32), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False
        ),
        sa.Column(
            "workspace_id",
            sa.String(32),
            sa.ForeignKey("workspaces.id", ondelete="CASCADE"),
            nullable=True,
        ),
        sa.Column("name", sa.String(100), nullable=False),
        sa.Column("prefix", sa.String(24), nullable=False),
        sa.Column("token_hash", sa.String(64), nullable=False, unique=True),
        sa.Column("scope", sa.String(16), nullable=False),
        _ts("created_at"),
        _ts("last_used_at", nullable=True),
        _ts("expires_at", nullable=True),
        _ts("revoked_at", nullable=True),
    )
    op.create_index("ix_api_tokens_user_id", "api_tokens", ["user_id"])

    # The local user owns the default workspace, which takes everything stored so far.
    now = datetime.now(UTC)
    users = sa.table(
        "users", sa.column("id"), sa.column("name"), sa.column("created_at", sa.DateTime)
    )
    workspaces = sa.table(
        "workspaces",
        sa.column("id"),
        sa.column("name"),
        sa.column("kind"),
        sa.column("created_by"),
        sa.column("created_at", sa.DateTime),
    )
    memberships = sa.table(
        "memberships",
        sa.column("workspace_id"),
        sa.column("user_id"),
        sa.column("role"),
        sa.column("created_at", sa.DateTime),
    )
    op.bulk_insert(users, [{"id": LOCAL_USER_ID, "name": "Local user", "created_at": now}])
    op.bulk_insert(
        workspaces,
        [
            {
                "id": DEFAULT_WORKSPACE_ID,
                "name": "Default",
                "kind": "team",
                "created_by": LOCAL_USER_ID,
                "created_at": now,
            }
        ],
    )
    op.bulk_insert(
        memberships,
        [
            {
                "workspace_id": DEFAULT_WORKSPACE_ID,
                "user_id": LOCAL_USER_ID,
                "role": "owner",
                "created_at": now,
            }
        ],
    )

    for table, prefix in (("projects", "projects"), ("simulation_runs", "runs")):
        with op.batch_alter_table(table) as batch:
            batch.add_column(sa.Column("workspace_id", sa.String(32), nullable=True))
            batch.add_column(sa.Column("created_by", sa.String(32), nullable=True))
        op.execute(
            sa.text(f"UPDATE {table} SET workspace_id = :ws, created_by = :user").bindparams(
                ws=DEFAULT_WORKSPACE_ID, user=LOCAL_USER_ID
            )
        )
        with op.batch_alter_table(table) as batch:
            batch.alter_column("workspace_id", existing_type=sa.String(32), nullable=False)
            batch.create_foreign_key(
                f"fk_{prefix}_workspace_id",
                "workspaces",
                ["workspace_id"],
                ["id"],
                ondelete="CASCADE",
            )
            batch.create_foreign_key(
                f"fk_{prefix}_created_by", "users", ["created_by"], ["id"], ondelete="SET NULL"
            )
    op.create_index("ix_projects_workspace_updated", "projects", ["workspace_id", "updated_at"])
    op.create_index("ix_runs_workspace_status", "simulation_runs", ["workspace_id", "status"])


def downgrade() -> None:
    op.drop_index("ix_runs_workspace_status", table_name="simulation_runs")
    op.drop_index("ix_projects_workspace_updated", table_name="projects")
    for table, prefix in (("simulation_runs", "runs"), ("projects", "projects")):
        with op.batch_alter_table(table) as batch:
            batch.drop_constraint(f"fk_{prefix}_created_by", type_="foreignkey")
            batch.drop_constraint(f"fk_{prefix}_workspace_id", type_="foreignkey")
            batch.drop_column("created_by")
            batch.drop_column("workspace_id")
    op.drop_index("ix_api_tokens_user_id", table_name="api_tokens")
    op.drop_table("api_tokens")
    op.drop_index("ix_sessions_expires_at", table_name="sessions")
    op.drop_index("ix_sessions_user_id", table_name="sessions")
    op.drop_table("sessions")
    op.drop_index("ix_invitations_workspace_id", table_name="invitations")
    op.drop_table("invitations")
    op.drop_index("ix_memberships_user_id", table_name="memberships")
    op.drop_table("memberships")
    op.drop_table("workspaces")
    op.drop_index("ix_identities_user_id", table_name="identities")
    op.drop_table("identities")
    op.drop_index("ix_users_email", table_name="users")
    op.drop_table("users")
