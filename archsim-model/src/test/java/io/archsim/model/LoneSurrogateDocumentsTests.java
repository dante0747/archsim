package io.archsim.model;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.issue.Issue;
import io.archsim.model.issue.Severity;
import io.archsim.model.validation.DocumentValidator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Half of a surrogate pair in a design, which a client can send only as a JSON escape:
 * each issue below is the one the frozen engine 0.16.0 gave for the same design, when it
 * loaded the document or when it checked a part's settings.
 */
class LoneSurrogateDocumentsTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final DocumentValidator VALIDATOR = DocumentValidator.standard();

	private static final String LONE_SURROGATE = Character.toString(Character.MIN_HIGH_SURROGATE);

	private static final String NOT_UNICODE = "Input should be a valid string, unable to parse raw data as a "
			+ "unicode string";

	private static final String NOT_UNICODE_IN_SETTINGS = "input should be a valid string, unable to parse raw "
			+ "data as a unicode string";

	private static final String DESIGN = """
			{"schemaVersion": "1.7", "kind": "archsim.architecture", "metadata": {"name": "case"},
			 "architecture": {"autoSize": false,
			   "nodes": [{"id": "clients", "type": "traffic_source", "config": {}},
			             {"id": "api", "type": "external_api",
			              "config": {"latencyMs": {"kind": "constant", "value": 20}}}],
			   "edges": [{"id": "clients-api", "source": "clients", "target": "api"}]},
			 "workload": {"seed": 7, "warmupS": 1, "durationS": 6, "drainS": 600, "rps": 100,
			              "arrivalProcess": "deterministic"}}""";

	@ParameterizedTest(name = "{0} = {2} and a lone surrogate")
	@CsvSource(delimiter = '|', textBlock = """
			/kind                    | kind                    | ''
			/workload/arrivalProcess | workload.arrivalProcess | ''
			/workload/rps            | workload.rps            | ''
			/workload/rps            | workload.rps            | 1
			/workload/seed           | workload.seed           | ''
			/architecture/autoSize   | architecture.autoSize   | ''
			""")
	void load_loneSurrogateInASetting_isTheIssueTheFrozenEngineGave(String pointer, String path, String before) {
		ObjectNode design = design();
		put(design, pointer, before + LONE_SURROGATE);

		List<Issue> issues = loadIssues(design);

		assertThat(issues).containsExactly(new Issue(Severity.ERROR, "document.schema",
				path + ": " + NOT_UNICODE + " (at " + path + ")", null, null, path, null));
	}

	@Test
	void load_loneSurrogateInANodesType_isTheIssueNamingTheNode() {
		ObjectNode design = design();
		put(design, "/architecture/nodes/1/type", LONE_SURROGATE);

		List<Issue> issues = loadIssues(design);

		assertThat(issues).containsExactly(new Issue(Severity.ERROR, "document.schema",
				"node 'api': " + NOT_UNICODE + " (at architecture.nodes.1.type)", "api", null,
				"architecture.nodes.1.type", null));
	}

	@Test
	void load_loneSurrogateInANodesId_isTheIssueNamingTheNodeByIt() {
		ObjectNode design = design();
		put(design, "/architecture/nodes/0/id", LONE_SURROGATE);

		List<Issue> issues = loadIssues(design);

		assertThat(issues).containsExactly(new Issue(Severity.ERROR, "document.schema",
				"node '" + LONE_SURROGATE + "': " + NOT_UNICODE + " (at architecture.nodes.0.id)", LONE_SURROGATE, null,
				"architecture.nodes.0.id", null));
	}

	@ParameterizedTest(name = "an unknown key in {0}")
	@CsvSource(delimiter = '|', textBlock = """
			/metadata | metadata | /metadata/name | 5
			/workload | workload | /workload/rps  | -1
			''        | ''       | /kind          | '"another kind"'
			""")
	void load_loneSurrogateInAnUnknownKey_isTheOnlyIssueOfItsSection(String section, String path, String otherPointer,
			String otherError) {
		ObjectNode design = design();
		((ObjectNode) design.at(section)).put(LONE_SURROGATE, 1);
		JsonPointer other = JsonPointer.compile(otherPointer);
		((ObjectNode) design.at(other.head())).set(other.last().getMatchingProperty(), JSON.readTree(otherError));

		List<Issue> issues = loadIssues(design);

		assertThat(issues).containsExactly(new Issue(Severity.ERROR, "document.schema",
				path + ": " + NOT_UNICODE + " (at " + path + ")", null, null, path, null));
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', textBlock = """
			technology       | technology
			errorRate        | errorRate
			concurrencyLimit | concurrencyLimit
			rateLimitRps     | rateLimitRps
			latencyMs/value  | latencyMs.constant.value
			""")
	void validate_loneSurrogateInAPartsSetting_isTheIssueTheFrozenEngineGave(String setting, String path) {
		ObjectNode design = design();
		put(design, "/architecture/nodes/1/config/" + setting, LONE_SURROGATE);

		List<Issue> errors = VALIDATOR.validate(DesignDocuments.load(design)).errors();

		assertThat(errors).extracting(Issue::code, Issue::message, Issue::nodeId, Issue::path)
			.containsExactly(
					tuple("node.config_invalid", "api: '" + path + "' " + NOT_UNICODE_IN_SETTINGS, "api", path));
	}

	@Test
	void validate_loneSurrogateInAnUnknownKeyOfAPartsSettings_isTheSettingsOnlyIssue() {
		ObjectNode design = design();
		((ObjectNode) design.at("/architecture/nodes/1/config")).put(LONE_SURROGATE, 1);

		List<Issue> errors = VALIDATOR.validate(DesignDocuments.load(design)).errors();

		assertThat(errors).extracting(Issue::code, Issue::message, Issue::nodeId, Issue::path)
			.containsExactly(tuple("node.config_invalid", "api: '' " + NOT_UNICODE_IN_SETTINGS, "api", ""));
	}

	@Test
	void validate_loneSurrogateAsADistributionsKind_isTheTagWithReplacementCharacters() {
		ObjectNode design = design();
		put(design, "/architecture/nodes/1/config/latencyMs/kind", LONE_SURROGATE);

		List<Issue> errors = VALIDATOR.validate(DesignDocuments.load(design)).errors();

		String replaced = Character.toString(0xFFFD).repeat(3);
		assertThat(errors).extracting(Issue::code, Issue::message, Issue::nodeId, Issue::path)
			.containsExactly(tuple("node.config_invalid", "api: 'latencyMs' input tag '" + replaced
					+ "' found using 'kind' does not match any of the expected tags: 'constant', 'exponential', "
					+ "'uniform', 'lognormal', 'empirical'", "api", "latencyMs"));
	}

	private static ObjectNode design() {
		return (ObjectNode) JSON.readTree(DESIGN);
	}

	private static void put(ObjectNode design, String pointer, String text) {
		JsonPointer at = JsonPointer.compile(pointer);
		((ObjectNode) design.at(at.head())).put(at.last().getMatchingProperty(), text);
	}

	private static List<Issue> loadIssues(ObjectNode design) {
		return catchThrowableOfType(DocumentInvalidException.class, () -> DesignDocuments.load(design)).issues();
	}

}
