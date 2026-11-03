package io.archsim.app.projects;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Projects end to end: making, reading, saving, copying, exporting and deleting them.
 */
class ProjectsTests extends ProjectTestSupport {

	@Test
	void create_nothing_isAnUntitledFirstRevision() {
		JsonNode project = created(post("/api/v1/projects", "{}"));

		assertThat(project.path("name").asString()).isEqualTo("Untitled architecture");
		assertThat(project.path("revision").asInt()).isEqualTo(1);
	}

	@Test
	void create_nothing_isPinnedToTheShippedPrices() {
		JsonNode project = created(post("/api/v1/projects", "{}"));

		assertThat(project.path("document").path("pricing").path("provider").asString()).isEqualTo("aws");
	}

	@Test
	void create_nothing_tellsItsTimesAsPydanticDoes() {
		JsonNode project = created(post("/api/v1/projects", "{}"));

		assertThat(project.path("createdAt").asString())
			.matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d(\\.\\d{6})?Z");
	}

	@Test
	void create_namedExample_isACopyNamedAsAsked() {
		JsonNode project = created(post("/api/v1/projects", """
				{"exampleId": "cache-aside-starter", "name": "Checkout"}"""));

		assertThat(project.path("document").path("metadata").path("name").asString()).isEqualTo("Checkout");
	}

	@Test
	void create_documentAndExample_isABadRequest() {
		assertThat(post("/api/v1/projects", """
				{"exampleId": "queueing-lab", "document": {}}""")).hasStatus(400)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Pass either document or exampleId, not both");
	}

	@Test
	void create_unknownExample_isNotFound() {
		assertThat(post("/api/v1/projects", """
				{"exampleId": "nope"}""")).hasStatus(404)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Unknown example 'nope'");
	}

	@Test
	void create_documentThatIsNoDesign_isRefusedWithItsIssues() {
		assertThat(post("/api/v1/projects", """
				{"document": {"kind": "nope"}}""")).hasStatus(422)
			.bodyJson()
			.extractingPath("$.detail.message")
			.asString()
			.startsWith("The document is not a valid ArchSim document: ");
	}

	@Test
	void importDesign_exportedDesign_isAProjectNamedAsAsked() {
		JsonNode project = created(post("/api/v1/projects/import?name=First&name=Imported", """
				{"schemaVersion": "1.0", "kind": "archsim.architecture", "metadata": {"name": "Old"},
				 "architecture": {"nodes": [], "edges": []}}"""));

		assertThat(project.path("name").asString()).isEqualTo("Imported");
	}

	@Test
	void importDesign_bodyThatIsNoObject_isRefusedAsFastApiRefusesIt() {
		assertThat(post("/api/v1/projects/import", "[1]")).hasStatus(422)
			.bodyJson()
			.extractingPath("$.detail[0].msg")
			.isEqualTo("Input should be a valid dictionary");
	}

	@Test
	void list_workspace_isItsProjectsMostRecentlySavedFirst() {
		workspace("listed1", "team", "owner");
		String older = created(post("/api/v1/projects?workspace=listed1", "{}")).path("id").asString();
		String newer = created(post("/api/v1/projects?workspace=listed1", "{}")).path("id").asString();

		JsonNode list = json(mvc().get().uri("/api/v1/projects?workspace=listed1").exchange());

		assertThat(list.valueStream().map((project) -> project.path("id").asString())).containsExactly(newer, older);
	}

	@Test
	void list_projectFromExample_countsItsPartsAndScenarios() {
		workspace("listed2", "team", "owner");
		created(post("/api/v1/projects?workspace=listed2", """
				{"exampleId": "cache-aside-starter", "name": "Counted"}"""));

		JsonNode summary = json(mvc().get().uri("/api/v1/projects?workspace=listed2").exchange()).get(0);

		assertThat(summary.path("nodeCount").asInt()).isPositive();
		assertThat(summary.path("scenarioCount").asInt()).isPositive();
	}

	@Test
	void get_project_isItWithItsDesign() {
		JsonNode made = created(post("/api/v1/projects", """
				{"name": "Fetched"}"""));

		JsonNode fetched = json(mvc().get().uri("/api/v1/projects/" + made.path("id").asString()).exchange());

		assertThat(fetched.path("document")).isEqualTo(made.path("document"));
	}

	@Test
	void update_staleRevision_isAConflict() {
		String id = created(post("/api/v1/projects", "{}")).path("id").asString();

		assertThat(put("/api/v1/projects/" + id, """
				{"name": "Later", "expectedRevision": 7}""")).hasStatus(409)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Project was modified elsewhere (revision 1, you had 7). Reload before saving.");
	}

	@Test
	void update_newName_isTheDesignsNameInTheNextRevision() {
		String id = created(post("/api/v1/projects", "{}")).path("id").asString();

		JsonNode saved = json(put("/api/v1/projects/" + id, """
				{"name": "Renamed", "expectedRevision": 1}"""));

		assertThat(saved.path("revision").asInt()).isEqualTo(2);
		assertThat(saved.path("document").path("metadata").path("name").asString()).isEqualTo("Renamed");
	}

	@Test
	void update_newDocument_isNormalizedUnderTheProjectsName() {
		String id = created(post("/api/v1/projects", """
				{"name": "Kept"}""")).path("id").asString();

		JsonNode saved = json(put("/api/v1/projects/" + id, """
				{"document": {"schemaVersion": "1.0", "kind": "archsim.architecture",
				 "metadata": {"name": "Other"}, "architecture": {"nodes": [], "edges": []}}}"""));

		assertThat(saved.path("document").path("metadata").path("name").asString()).isEqualTo("Kept");
		assertThat(saved.path("document").path("schemaVersion").asString()).isEqualTo("1.7");
	}

	@Test
	void duplicate_project_isACopyNamedSo() {
		String id = created(post("/api/v1/projects", """
				{"name": "Original"}""")).path("id").asString();

		JsonNode copy = created(post("/api/v1/projects/" + id + "/duplicate", ""));

		assertThat(copy.path("name").asString()).isEqualTo("Original (copy)");
	}

	@Test
	void duplicate_longName_isCutToOneHundredTwentyCharacters() {
		String name = "\uD83D\uDE00".repeat(118);
		String id = created(post("/api/v1/projects", "{\"name\": \"" + name + "\"}")).path("id").asString();

		JsonNode copy = created(post("/api/v1/projects/" + id + "/duplicate", ""));

		assertThat(copy.path("name").asString().codePoints().count()).isEqualTo(120);
	}

	@Test
	void duplicate_projectWithDescription_keepsIt() {
		String id = created(post("/api/v1/projects", """
				{"name": "Described", "description": "Checkout path"}""")).path("id").asString();

		JsonNode copy = created(post("/api/v1/projects/" + id + "/duplicate", ""));

		assertThat(copy.path("description").asString()).isEqualTo("Checkout path");
	}

	@Test
	void export_project_isAnAttachmentNamedAfterIt() {
		String id = created(post("/api/v1/projects", """
				{"name": "Checkout Flow!"}""")).path("id").asString();

		assertThat(mvc().get().uri("/api/v1/projects/" + id + "/export")).hasStatusOk()
			.hasHeader("Content-Disposition", "attachment; filename=\"checkout-flow.archsim.json\"");
	}

	@Test
	void export_project_isItsDesign() {
		JsonNode made = created(post("/api/v1/projects", """
				{"name": "Exported"}"""));

		JsonNode file = json(mvc().get().uri("/api/v1/projects/" + made.path("id").asString() + "/export").exchange());

		assertThat(file).isEqualTo(made.path("document"));
	}

	@Test
	void delete_project_answersNoContent() {
		String id = created(post("/api/v1/projects", "{}")).path("id").asString();

		assertThat(mvc().delete().uri("/api/v1/projects/" + id)).hasStatus(204);
	}

	@Test
	void get_deletedProject_isNotFound() {
		String id = created(post("/api/v1/projects", "{}")).path("id").asString();
		mvc().delete().uri("/api/v1/projects/" + id).exchange();

		assertThat(mvc().get().uri("/api/v1/projects/" + id)).hasStatus(404)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Project '" + id + "' not found");
	}

}
