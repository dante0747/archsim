package io.archsim.app.projects;

import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.access.Principal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every change to a project is in the history, with who made it and from where, stored in
 * the same transaction as the change.
 */
class ProjectHistoryTests extends ProjectTestSupport {

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private ProjectChanges changes;

	@Test
	void create_project_recordsItsName() {
		String id = created(post("/api/v1/projects", """
				{"name": "Audited"}""")).path("id").asString();

		String meta = jdbc().sql("SELECT meta::text FROM audit_events WHERE project_id = :id")
			.param("id", id)
			.query((rows, number) -> rows.getString(1))
			.single();
		assertThat(JSON.readTree(meta)).isEqualTo(JSON.readTree("{\"name\": \"Audited\"}"));
	}

	@Test
	void create_throughTheWebServer_recordsTheVisitorAsTheActor() {
		String id = created(mvc().post()
			.uri("/api/v1/projects")
			.header("X-ArchSim-Client-IP", "203.0.113.9")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}")
			.exchange()).path("id").asString();

		Map<String, @Nullable Object> actor = jdbc()
			.sql("SELECT actor_user_id, actor_kind, ip FROM audit_events WHERE project_id = :id")
			.param("id", id)
			.query()
			.singleRow();
		assertThat(actor).containsEntry("actor_user_id", "local")
			.containsEntry("actor_kind", "user")
			.containsEntry("ip", "203.0.113.9");
	}

	@Test
	void saveAndDelete_project_areEachRecorded() {
		String id = created(post("/api/v1/projects", "{}")).path("id").asString();
		put("/api/v1/projects/" + id, """
				{"name": "Saved"}""");

		mvc().delete().uri("/api/v1/projects/" + id).exchange();

		assertThat(actions(id)).containsExactly("project.create", "project.update", "project.delete");
	}

	@Test
	void create_transactionRolledBack_leavesNeitherProjectNorEvent() {
		ObjectNode design = JSON.createObjectNode();
		design.putObject("metadata").put("name", "Rolled back");

		String id = this.transactions.execute((status) -> {
			Project made = this.changes.create(new NewProject("default", design, null, null), Principal.local(null));
			status.setRollbackOnly();
			return made.id();
		});

		assertThat(jdbc().sql("SELECT count(*) FROM projects WHERE id = :id")
			.param("id", id)
			.query((rows, number) -> rows.getInt(1))
			.single()).isZero();
		assertThat(actions(id)).isEmpty();
	}

}
