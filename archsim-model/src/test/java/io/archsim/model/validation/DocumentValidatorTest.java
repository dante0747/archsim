package io.archsim.model.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import io.archsim.model.DesignDocuments;
import io.archsim.model.document.RequestType;
import io.archsim.model.issue.Issue;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * What validation says about a small design: clients → load balancer → service →
 * database. The golden files check every template and invalid fixture word for word;
 * these show the shape of it.
 */
class DocumentValidatorTest {

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
	void a_sound_design_is_valid_and_its_links_carry_what_reaches_them() {
		ValidationResult result = validate("", "");
		assertThat(result.valid()).isTrue();
		assertThat(result.issues()).isEmpty();
		assertThat(result.edgeFlows()).containsEntry("c-lb", Set.of(RequestType.READ, RequestType.WRITE))
			.containsEntry("api-db", Set.of(RequestType.READ, RequestType.WRITE));
	}

	@Test
	void a_link_to_a_missing_node_is_an_error_naming_the_link() {
		ValidationResult result = validate("", ", {\"id\": \"api-x\", \"source\": \"api\", \"target\": \"ghost\"}");
		assertThat(result.valid()).isFalse();
		assertThat(result.errors()).extracting(Issue::code, Issue::message, Issue::edgeId)
			.containsExactly(
					tuple("edge.unknown_node", "Link 'api-x' references node 'ghost', which does not exist.", "api-x"));
	}

	@Test
	void a_part_no_request_reaches_is_a_warning_and_the_design_stays_valid() {
		ValidationResult result = validate(", {\"id\": \"batch\", \"type\": \"service\"}", "");
		assertThat(result.valid()).isTrue();
		assertThat(result.warnings()).extracting(Issue::code, Issue::message)
			.containsExactly(
					tuple("node.unreachable", "batch receives no traffic (not reachable from the traffic source)."));
	}

	@Test
	void a_config_error_names_the_setting_in_lower_case_words() {
		ValidationResult result = validate(
				", {\"id\": \"q\", \"type\": \"message_queue\", \"config\": {\"partitions\": 0}}", "");
		assertThat(result.errors()).extracting(Issue::code, Issue::message, Issue::path)
			.contains(tuple("node.config_invalid", "q: 'partitions' input should be greater than or equal to 1",
					"partitions"));
		assertThat(result.configs()).doesNotContainKey("q");
	}

	@Test
	void a_loop_is_reported_once_from_where_the_search_closed_it() {
		ValidationResult result = validate("", ", {\"id\": \"api-lb\", \"source\": \"api\", \"target\": \"lb\"}");
		assertThat(result.errors()).extracting(Issue::code, Issue::message)
			.containsExactly(tuple("graph.cycle",
					"Requests would loop forever: LB → API → LB. Synchronous call graphs must be acyclic."));
	}

	@Test
	void the_bound_on_calls_per_request_can_be_lifted() {
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
