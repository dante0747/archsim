package io.archsim.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.issue.Issue;
import io.archsim.model.issue.Severity;
import io.archsim.parity.Repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Reading and writing documents. The golden files check every example against the Python
 * engine; these say what a reader of the API can rely on.
 */
class DesignDocumentsTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Every example (the templates) and the web app's test fixture: the documents the
	 * repo ships.
	 */
	static Stream<Path> documents() throws IOException {
		Path webFixture = Repository.root().resolve("apps/web/src/test/fixtures/starter.json");
		try (Stream<Path> examples = Files.list(Repository.examples())) {
			List<Path> designs = examples.filter((path) -> path.toString().endsWith(".json")).sorted().toList();
			return Stream.concat(designs.stream(), Stream.of(webFixture));
		}
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("documents")
	void toJson_shippedDocument_readsBackAsTheSameDocumentAndJson(Path file) {
		ArchitectureDocument read = DesignDocuments.load(JSON.readTree(file.toFile()));
		ObjectNode written = DesignDocuments.toJson(read);

		ArchitectureDocument again = DesignDocuments.load(written);
		assertThat(again).isEqualTo(read);
		assertThat(DesignDocuments.toJson(again)).isEqualTo(written);
	}

	@Test
	void load_emptyCurrentDocument_takesEveryDefault() {
		ArchitectureDocument document = DesignDocuments.load(JSON.readTree("{\"schemaVersion\": \"1.7\"}"));
		assertThat(document.metadata().name()).isEqualTo("Untitled architecture");
		assertThat(document.workload().rps()).isEqualTo(100.0);
		assertThat(document.pricing().isGeneric()).isTrue();
		assertThat(document.objectives().latencyP95Ms()).isEqualTo(300.0);
	}

	@Test
	void load_schemaErrors_becomeIssuesNamingTheirNodeOrLink() {
		JsonNode raw = JSON.readTree("""
				{"schemaVersion": "1.7",
				 "architecture": {"nodes": [{"id": "api", "type": ""}],
				                  "edges": [{"id": "e", "source": "api", "target": "db",
				                             "config": {"maxAttempts": 9}}]},
				 "colour": "blue"}""");
		DocumentInvalidException invalid = catchThrowableOfType(DocumentInvalidException.class,
				() -> DesignDocuments.load(raw));

		assertThat(invalid.issues()).containsExactly(
				new Issue(Severity.ERROR, "document.schema",
						"node 'api': String should have at least 1 character (at architecture.nodes.0.type)", "api",
						null, "architecture.nodes.0.type", null),
				new Issue(Severity.ERROR, "document.schema",
						"link 'e': Input should be less than or equal to 5 (at"
								+ " architecture.edges.0.config.maxAttempts)",
						null, "e", "architecture.edges.0.config.maxAttempts", null),
				new Issue(Severity.ERROR, "document.schema", "colour: Extra inputs are not permitted (at colour)", null,
						null, "colour", null));
		assertThat(invalid).hasMessage(
				"node 'api': String should have at least 1 character (at architecture.nodes.0.type)" + " (+2 more)");
	}

	@Test
	void load_newerSchema_isOneVersionIssue() {
		DocumentInvalidException invalid = catchThrowableOfType(DocumentInvalidException.class,
				() -> DesignDocuments.load(JSON.readTree("{\"schemaVersion\": \"9\"}")));
		assertThat(invalid.issues()).extracting(Issue::code, Issue::message)
			.containsExactly(tuple("document.version",
					"this document uses schema 9, newer than the supported 1.8. Upgrade ArchSim to open it."));
	}

	@Test
	void simulationInput_anyDocument_isWhatTheSimulatorReads() {
		ArchitectureDocument document = DesignDocuments.load(JSON.readTree("{\"schemaVersion\": \"1.7\"}"));
		assertThat(List.copyOf(DesignDocuments.simulationInput(document).propertyNames()))
			.containsExactly("schemaVersion", "metadata", "architecture", "workload", "pricing");
	}

}
