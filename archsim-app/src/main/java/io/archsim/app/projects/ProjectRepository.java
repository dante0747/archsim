package io.archsim.app.projects;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code projects} table.
 */
@Repository
class ProjectRepository {

	private static final String COLUMNS = """
			id, workspace_id, created_by, name, description, document::text AS document, revision, source_example,
			created_at, updated_at""";

	/**
	 * Most recently updated first; projects updated at the same instant (by two of the
	 * API's servers) by id, so a list keeps its order and a pick of one is always the
	 * same project.
	 */
	private static final String MOST_RECENTLY_UPDATED_FIRST = " ORDER BY updated_at DESC, id DESC";

	/** The latest never-saved copy of an example someone made in a workspace. */
	private static final String UNEDITED_COPY = """
			WHERE workspace_id = :workspace AND created_by = :user AND source_example = :example AND revision = 1"""
			+ MOST_RECENTLY_UPDATED_FIRST + " LIMIT 1";

	private final JdbcClient jdbc;

	private final JsonMapper json;

	ProjectRepository(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/**
	 * The workspace a project is in.
	 * @param id the project
	 * @return its workspace's id, if the project exists
	 */
	Optional<String> workspaceOf(String id) {
		return this.jdbc.sql("SELECT workspace_id FROM projects WHERE id = :id")
			.param("id", id)
			.query((rows, number) -> rows.getString("workspace_id"))
			.optional();
	}

	Optional<Project> find(String id) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM projects WHERE id = :id")
			.param("id", id)
			.query(this::project)
			.optional();
	}

	/**
	 * A workspace's projects.
	 * @param workspaceId the workspace
	 * @return its projects, most recently saved first
	 */
	List<Project> inWorkspace(String workspaceId) {
		return this.jdbc
			.sql("SELECT " + COLUMNS + " FROM projects WHERE workspace_id = :workspace" + MOST_RECENTLY_UPDATED_FIRST)
			.param("workspace", workspaceId)
			.query(this::project)
			.list();
	}

	int count(String workspaceId) {
		return this.jdbc.sql("SELECT count(*) FROM projects WHERE workspace_id = :workspace")
			.param("workspace", workspaceId)
			.query((rows, number) -> rows.getInt(1))
			.single();
	}

	/**
	 * The latest copy of an example a user made in a workspace and never saved.
	 * @param workspaceId the workspace
	 * @param userId the user
	 * @param exampleId the example
	 * @return the copy, if there is one
	 */
	Optional<Project> uneditedCopy(String workspaceId, String userId, String exampleId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM projects " + UNEDITED_COPY)
			.param("workspace", workspaceId)
			.param("user", userId)
			.param("example", exampleId)
			.query(this::project)
			.optional();
	}

	void insert(Project project) {
		StatementSpec insert = this.jdbc.sql("""
				INSERT INTO projects (id, workspace_id, created_by, name, description, document, schema_version,
				                      revision, source_example, created_at, updated_at)
				VALUES (:id, :workspace, :createdBy, :name, :description, CAST(:document AS jsonb), :schemaVersion,
				        :revision, :sourceExample, :createdAt, :updatedAt)""")
			.param("id", project.id())
			.param("workspace", project.workspaceId())
			.param("createdBy", project.createdBy())
			.param("sourceExample", project.sourceExample())
			.param("createdAt", Timestamp.from(project.createdAt()));
		withChanges(insert, project).update();
	}

	/**
	 * Saves a project's new revision, if no other save got there first.
	 * @param project the project as saved
	 * @param previousRevision the revision the save was made from
	 * @return {@code false} when the stored project is no longer at that revision
	 */
	boolean update(Project project, int previousRevision) {
		StatementSpec update = this.jdbc.sql("""
				UPDATE projects SET name = :name, description = :description, document = CAST(:document AS jsonb),
				  schema_version = :schemaVersion, revision = :revision, updated_at = :updatedAt
				WHERE id = :id AND revision = :previousRevision""")
			.param("id", project.id())
			.param("previousRevision", previousRevision);
		return withChanges(update, project).update() == 1;
	}

	void delete(String id) {
		this.jdbc.sql("DELETE FROM projects WHERE id = :id").param("id", id).update();
	}

	/**
	 * Sets the parameters a save writes.
	 * @param statement an insert or update naming them
	 * @param project the project as saved
	 * @return the statement
	 */
	private StatementSpec withChanges(StatementSpec statement, Project project) {
		return statement.param("name", project.name())
			.param("description", project.description())
			.param("document", this.json.writeValueAsString(project.document()))
			.param("schemaVersion", project.schemaVersion())
			.param("revision", project.revision())
			.param("updatedAt", Timestamp.from(project.updatedAt()));
	}

	private Project project(ResultSet rows, int number) throws SQLException {
		return new Project(rows.getString("id"), rows.getString("workspace_id"), rows.getString("created_by"),
				rows.getString("name"), rows.getString("description"),
				(ObjectNode) this.json.readTree(rows.getString("document")), rows.getInt("revision"),
				rows.getString("source_example"), rows.getObject("created_at", OffsetDateTime.class).toInstant(),
				rows.getObject("updated_at", OffsetDateTime.class).toInstant());
	}

}
