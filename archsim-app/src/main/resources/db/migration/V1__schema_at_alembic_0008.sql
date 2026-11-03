-- The schema the Python API's Alembic migrations build, at their last revision (0008), which
-- Flyway takes over (ADR-0032). On a database Alembic made, Flyway records this as its baseline
-- and runs nothing; on an empty one, it builds the same tables, column for column and name for
-- name (parity/schema/alembic-0008.json is what Alembic builds; a test compares the two).
--
-- alembic_version is kept at 0008, so the Python API and workers, which migrate with Alembic,
-- find a database this script made up to date while both run (stage 2 of the Java port).

CREATE TABLE users (
    id VARCHAR(32) NOT NULL,
    email VARCHAR(320),
    name VARCHAR(200) NOT NULL,
    avatar_url VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen_at TIMESTAMP WITH TIME ZONE,
    disabled_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);
CREATE INDEX ix_users_email ON users (email);

CREATE TABLE workspaces (
    id VARCHAR(32) NOT NULL,
    name VARCHAR(120) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    max_concurrent_runs INTEGER,
    max_runs_per_hour INTEGER,
    created_by VARCHAR(32),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (id),
    FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL
);

CREATE TABLE identities (
    id VARCHAR(32) NOT NULL,
    user_id VARCHAR(32) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    email VARCHAR(320),
    email_verified BOOLEAN NOT NULL,
    login VARCHAR(200),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_login_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id),
    CONSTRAINT uq_identities_provider_subject UNIQUE (provider, subject),
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX ix_identities_user_id ON identities (user_id);

CREATE TABLE memberships (
    workspace_id VARCHAR(32) NOT NULL,
    user_id VARCHAR(32) NOT NULL,
    role VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (workspace_id, user_id),
    FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX ix_memberships_user_id ON memberships (user_id);

CREATE TABLE invitations (
    id VARCHAR(32) NOT NULL,
    workspace_id VARCHAR(32) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    role VARCHAR(16) NOT NULL,
    email VARCHAR(320),
    created_by VARCHAR(32),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    accepted_at TIMESTAMP WITH TIME ZONE,
    accepted_by VARCHAR(32),
    PRIMARY KEY (id),
    FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    UNIQUE (token_hash),
    FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL,
    FOREIGN KEY (accepted_by) REFERENCES users (id) ON DELETE SET NULL
);
CREATE INDEX ix_invitations_workspace_id ON invitations (workspace_id);

CREATE TABLE sessions (
    id VARCHAR(64) NOT NULL,
    user_id VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ip VARCHAR(64),
    user_agent VARCHAR(300),
    PRIMARY KEY (id),
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX ix_sessions_expires_at ON sessions (expires_at);
CREATE INDEX ix_sessions_user_id ON sessions (user_id);

CREATE TABLE api_tokens (
    id VARCHAR(32) NOT NULL,
    user_id VARCHAR(32) NOT NULL,
    workspace_id VARCHAR(32),
    name VARCHAR(100) NOT NULL,
    prefix VARCHAR(24) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    scope VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_used_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,
    revoked_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id),
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    UNIQUE (token_hash)
);
CREATE INDEX ix_api_tokens_user_id ON api_tokens (user_id);

-- Columns in the order Alembic's revisions added them.
CREATE TABLE projects (
    id VARCHAR(32) NOT NULL,
    name VARCHAR(120) NOT NULL,
    description TEXT,
    document JSONB NOT NULL,
    schema_version VARCHAR(16) NOT NULL,
    revision INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    source_example VARCHAR(100),
    workspace_id VARCHAR(32) NOT NULL,
    created_by VARCHAR(32),
    PRIMARY KEY (id),
    CONSTRAINT fk_projects_workspace_id FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_projects_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL
);
CREATE INDEX ix_projects_source_example ON projects (source_example);
CREATE INDEX ix_projects_workspace_updated ON projects (workspace_id, updated_at);

CREATE TABLE run_groups (
    id VARCHAR(32) NOT NULL,
    workspace_id VARCHAR(32) NOT NULL,
    project_id VARCHAR(32) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    base_seed BIGINT NOT NULL,
    size INTEGER NOT NULL,
    mode VARCHAR(16) DEFAULT 'full' NOT NULL,
    scenario_id VARCHAR(64),
    label VARCHAR(200),
    created_by VARCHAR(32),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (id),
    FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
    FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL
);
CREATE INDEX ix_run_groups_project_id ON run_groups (project_id);

-- Columns in the order Alembic's revisions added them.
CREATE TABLE simulation_runs (
    id VARCHAR(32) NOT NULL,
    project_id VARCHAR(32) NOT NULL,
    label VARCHAR(200),
    scenario_id VARCHAR(64),
    scenario_name VARCHAR(120),
    status VARCHAR(16) NOT NULL,
    progress DOUBLE PRECISION NOT NULL,
    error JSONB,
    seed BIGINT NOT NULL,
    engine_version VARCHAR(32) NOT NULL,
    input_digest VARCHAR(64),
    result_digest VARCHAR(64),
    summary JSONB,
    wall_seconds DOUBLE PRECISION,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE,
    input_document JSONB NOT NULL,
    report JSONB,
    attempts INTEGER DEFAULT 0 NOT NULL,
    lease_owner VARCHAR(64),
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    cancel_requested_at TIMESTAMP WITH TIME ZONE,
    workspace_id VARCHAR(32) NOT NULL,
    created_by VARCHAR(32),
    max_requests BIGINT,
    max_wall_seconds DOUBLE PRECISION,
    group_id VARCHAR(32),
    seed_index INTEGER,
    mode VARCHAR(16) DEFAULT 'full' NOT NULL,
    PRIMARY KEY (id),
    FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
    CONSTRAINT fk_runs_workspace_id FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_runs_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_runs_group_id FOREIGN KEY (group_id) REFERENCES run_groups (id) ON DELETE SET NULL
);
CREATE INDEX ix_runs_project_created ON simulation_runs (project_id, created_at);
CREATE INDEX ix_simulation_runs_project_id ON simulation_runs (project_id);
CREATE INDEX ix_simulation_runs_status ON simulation_runs (status);
CREATE INDEX ix_runs_status_created ON simulation_runs (status, created_at);
CREATE INDEX ix_runs_workspace_status ON simulation_runs (workspace_id, status);
CREATE INDEX ix_simulation_runs_group_id ON simulation_runs (group_id);

CREATE TABLE job_workers (
    id VARCHAR(64) NOT NULL,
    hostname VARCHAR(255) NOT NULL,
    pid INTEGER NOT NULL,
    slots INTEGER NOT NULL,
    running INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    heartbeat_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX ix_job_workers_heartbeat_at ON job_workers (heartbeat_at);

CREATE TABLE rate_limits (
    key VARCHAR(200) NOT NULL,
    window_start BIGINT NOT NULL,
    count INTEGER NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (key, window_start)
);
CREATE INDEX ix_rate_limits_expires_at ON rate_limits (expires_at);

CREATE TABLE shared_reports (
    id VARCHAR(32) NOT NULL,
    slug VARCHAR(32) NOT NULL,
    workspace_id VARCHAR(32) NOT NULL,
    project_id VARCHAR(32),
    run_id VARCHAR(32),
    created_by VARCHAR(32),
    title VARCHAR(200) NOT NULL,
    payload JSONB,
    payload_version INTEGER NOT NULL,
    report_version VARCHAR(16) NOT NULL,
    result_digest VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE,
    revoked_at TIMESTAMP WITH TIME ZONE,
    view_count INTEGER DEFAULT 0 NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_shared_reports_slug UNIQUE (slug),
    CONSTRAINT fk_shared_reports_workspace_id FOREIGN KEY (workspace_id) REFERENCES workspaces (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_shared_reports_project_id FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE SET NULL,
    CONSTRAINT fk_shared_reports_run_id FOREIGN KEY (run_id) REFERENCES simulation_runs (id) ON DELETE SET NULL,
    CONSTRAINT fk_shared_reports_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL
);
CREATE INDEX ix_shared_reports_project_created ON shared_reports (project_id, created_at);
CREATE INDEX ix_shared_reports_workspace_created ON shared_reports (workspace_id, created_at);

CREATE TABLE comments (
    id VARCHAR(32) NOT NULL,
    workspace_id VARCHAR(32) NOT NULL,
    project_id VARCHAR(32) NOT NULL,
    anchor_kind VARCHAR(16) NOT NULL,
    anchor_id VARCHAR(64),
    run_id VARCHAR(32),
    parent_id VARCHAR(32),
    author_id VARCHAR(32),
    body TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    edited_at TIMESTAMP WITH TIME ZONE,
    resolved_at TIMESTAMP WITH TIME ZONE,
    resolved_by VARCHAR(32),
    PRIMARY KEY (id),
    FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
    FOREIGN KEY (run_id) REFERENCES simulation_runs (id) ON DELETE SET NULL,
    FOREIGN KEY (parent_id) REFERENCES comments (id) ON DELETE CASCADE,
    FOREIGN KEY (author_id) REFERENCES users (id) ON DELETE SET NULL,
    FOREIGN KEY (resolved_by) REFERENCES users (id) ON DELETE SET NULL
);
CREATE INDEX ix_comments_project_created ON comments (project_id, created_at);
CREATE INDEX ix_comments_run_id ON comments (run_id);
CREATE INDEX ix_comments_parent_id ON comments (parent_id);

CREATE TABLE review_signoffs (
    id VARCHAR(32) NOT NULL,
    project_id VARCHAR(32) NOT NULL,
    run_id VARCHAR(32),
    project_revision INTEGER NOT NULL,
    input_digest VARCHAR(64),
    result_digest VARCHAR(64),
    reviewer_id VARCHAR(32),
    decision VARCHAR(24) NOT NULL,
    note TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (id),
    FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
    FOREIGN KEY (run_id) REFERENCES simulation_runs (id) ON DELETE SET NULL,
    FOREIGN KEY (reviewer_id) REFERENCES users (id) ON DELETE SET NULL
);
CREATE INDEX ix_signoffs_project_created ON review_signoffs (project_id, created_at);
CREATE INDEX ix_review_signoffs_run_id ON review_signoffs (run_id);

CREATE TABLE audit_events (
    id BIGSERIAL NOT NULL,
    workspace_id VARCHAR(32) NOT NULL,
    project_id VARCHAR(32),
    actor_user_id VARCHAR(32),
    actor_kind VARCHAR(16) NOT NULL,
    action VARCHAR(64) NOT NULL,
    target_kind VARCHAR(32) NOT NULL,
    target_id VARCHAR(64),
    meta JSONB,
    ip VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (id),
    FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    FOREIGN KEY (actor_user_id) REFERENCES users (id) ON DELETE SET NULL
);
CREATE INDEX ix_audit_workspace_id ON audit_events (workspace_id, id);
CREATE INDEX ix_audit_project_id ON audit_events (project_id, id);

-- The local user, who is every request without sign-in, owns the default workspace.
INSERT INTO users (id, name, created_at) VALUES ('local', 'Local user', now());
INSERT INTO workspaces (id, name, kind, created_by, created_at) VALUES ('default', 'Default', 'team', 'local', now());
INSERT INTO memberships (workspace_id, user_id, role, created_at) VALUES ('default', 'local', 'owner', now());

CREATE TABLE alembic_version (
    version_num VARCHAR(32) NOT NULL,
    CONSTRAINT alembic_version_pkc PRIMARY KEY (version_num)
);
INSERT INTO alembic_version (version_num) VALUES ('0008');
