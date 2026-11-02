package io.archsim.model.validation;

import java.util.Set;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.model.DesignDocuments;
import io.archsim.model.document.RequestType;
import io.archsim.model.issue.Issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * What validation says about a small design: clients → load balancer → service →
 * database. The golden files check every template and invalid fixture word for word;
 * these show the shape of it.
 */
class DocumentValidatorTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final DocumentValidator VALIDATOR = DocumentValidator.standard();

	private static final String DESIGN = """
			{"schemaVersion": "1.7",
			 "architecture": {
			   "nodes": [
			     {"id": "clients", "type": "traffic_source", "label": "Clients"},
			     {"id": "lb", "type": "load_balancer", "label": "LB"},
			     {"id": "api", "type": "service", "label": "API"},
			     {"id": "db", "type": "database", "label": "DB"}%s],
			   "edges": [
			     {"id": "c-lb", "source": "clients", "target": "lb"},
			     {"id": "lb-api", "source": "lb", "target": "api"},
			     {"id": "api-db", "source": "api", "target": "db"}%s]}}""";

	private static ValidationResult validate(String moreNodes, String moreEdges) {
		return VALIDATOR.validate(DesignDocuments.load(JSON.readTree(DESIGN.formatted(moreNodes, moreEdges))));
	}

	@Test
	void validate_soundDesign_isValidAndItsLinksCarryWhatReachesThem() {
		ValidationResult result = validate("", "");
		assertThat(result.valid()).isTrue();
		assertThat(result.issues()).isEmpty();
		assertThat(result.edgeFlows()).containsEntry("c-lb", Set.of(RequestType.READ, RequestType.WRITE))
			.containsEntry("api-db", Set.of(RequestType.READ, RequestType.WRITE));
	}

	@Test
	void validate_linkToMissingNode_isAnErrorNamingTheLink() {
		ValidationResult result = validate("", ", {\"id\": \"api-x\", \"source\": \"api\", \"target\": \"ghost\"}");
		assertThat(result.valid()).isFalse();
		assertThat(result.errors()).extracting(Issue::code, Issue::message, Issue::edgeId)
			.containsExactly(
					tuple("edge.unknown_node", "Link 'api-x' references node 'ghost', which does not exist.", "api-x"));
	}

	@Test
	void validate_partNoRequestReaches_isAWarningAndTheDesignStaysValid() {
		ValidationResult result = validate(", {\"id\": \"batch\", \"type\": \"service\"}", "");
		assertThat(result.valid()).isTrue();
		assertThat(result.warnings()).extracting(Issue::code, Issue::message)
			.containsExactly(
					tuple("node.unreachable", "batch receives no traffic (not reachable from the traffic source)."));
	}

	@Test
	void validate_configError_namesTheSettingInLowerCaseWords() {
		ValidationResult result = validate(
				", {\"id\": \"q\", \"type\": \"message_queue\", \"config\": {\"partitions\": 0}}", "");
		assertThat(result.errors()).extracting(Issue::code, Issue::message, Issue::path)
			.contains(tuple("node.config_invalid", "q: 'partitions' input should be greater than or equal to 1",
					"partitions"));
		assertThat(result.configs()).doesNotContainKey("q");
	}

	@Test
	void validate_loop_isReportedOnceFromWhereTheSearchClosedIt() {
		ValidationResult result = validate("", ", {\"id\": \"api-lb\", \"source\": \"api\", \"target\": \"lb\"}");
		assertThat(result.errors()).extracting(Issue::code, Issue::message)
			.containsExactly(tuple("graph.cycle",
					"Requests would loop forever: LB → API → LB. Synchronous call graphs must be acyclic."));
	}

	@Test
	void validate_boundOnCallsPerRequest_canBeLifted() {
		String retries = ", \"config\": {\"maxAttempts\": 5}";
		String design = DESIGN.formatted("", "")
			.replace("\"target\": \"api\"}", "\"target\": \"api\"" + retries + "}")
			.replace("\"target\": \"db\"}", "\"target\": \"db\"" + retries + "}");
		var document = DesignDocuments.load(JSON.readTree(design));
		assertThat(VALIDATOR.validate(document, 10.0).errors()).extracting(Issue::code)
			.containsExactly("design.amplification");
		assertThat(VALIDATOR.validate(document, null).issues()).isEmpty();
	}

}
