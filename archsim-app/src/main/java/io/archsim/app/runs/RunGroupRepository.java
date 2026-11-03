package io.archsim.app.runs;

import java.sql.Timestamp;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The {@code run_groups} table: runs of one input started together.
 */
@Repository
class RunGroupRepository {

	private final JdbcClient jdbc;

	RunGroupRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	void add(RunGroup group) {
		this.jdbc.sql("""
				INSERT INTO run_groups (id, workspace_id, project_id, kind, base_seed, size, mode, scenario_id, label,
				                        created_by, created_at)
				VALUES (:id, :workspace, :project, :kind, :base, :size, :mode, :scenario, :label, :createdBy, :now)""")
			.param("id", group.id())
			.param("workspace", group.workspaceId())
			.param("project", group.projectId())
			.param("kind", group.kind())
			.param("base", group.baseSeed())
			.param("size", group.size())
			.param("mode", group.mode())
			.param("scenario", group.scenarioId())
			.param("label", group.label())
			.param("createdBy", group.createdBy())
			.param("now", Timestamp.from(group.createdAt()))
			.update();
	}

	Optional<RunGroup> find(String id) {
		return this.jdbc.sql("""
				SELECT id, workspace_id, project_id, kind, base_seed, size, mode, scenario_id, label, created_by,
				       created_at
				FROM run_groups WHERE id = :id""")
			.param("id", id)
			.query((rows, number) -> new RunGroup(rows.getString("id"), rows.getString("workspace_id"),
					rows.getString("project_id"), rows.getString("kind"), rows.getLong("base_seed"),
					rows.getInt("size"), rows.getString("mode"), rows.getString("scenario_id"), rows.getString("label"),
					rows.getString("created_by"), rows.getTimestamp("created_at").toInstant()))
			.optional();
	}

}
