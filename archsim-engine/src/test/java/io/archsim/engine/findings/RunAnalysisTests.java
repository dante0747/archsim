package io.archsim.engine.findings;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.report.RunReport;
import io.archsim.model.DesignDocuments;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.parity.SchemaDepartures;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run's findings word a count of one request or call in the singular,
 * {@code 1 request}, never {@code 1 requests}. Each run is a recorded case of
 * {@code findings/cases.json} with one of its counts cut to one.
 */
class RunAnalysisTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String CASES = "/io/archsim/engine/findings/cases.json";

	@Test
	void analyze_oneFailureAtTheMainCause_namesItInTheSingular() throws IOException {
		Recorded run = Recorded.withOne("autoscaledToItsMaximum", "/summary/failureBreakdown/0/count");

		List<String> details = run.details();

		assertThat(details).contains("1 request failed at Checkout API: queue was full (100% of all failures).");
	}

	@Test
	void analyze_oneFailedRequestOfAThroughputGap_namesItInTheSingular() throws IOException {
		Recorded run = Recorded.withOne("autoscaledToItsMaximum", "/summary/failed");

		List<String> details = run.details();

		assertThat(details).contains("Success rate 82.1%; 1 request failed and 0 timed out in the measurement window.");
	}

	@Test
	void analyze_oneClientRequestFailedByAThirdParty_namesItInTheSingular() throws IOException {
		Recorded run = Recorded.withOne("thirdPartyErrorsReachingClients", "/summary/failureBreakdown/0/count");

		List<String> details = run.details();

		assertThat(details)
			.contains("20 of 659 calls (3.0%) failed with provider errors; 1 client request failed because of them.");
	}

	@Test
	void analyze_oneClientRequestFailedByAnOutage_namesItInTheSingular() throws IOException {
		Recorded run = Recorded.withOne("faultInTheWarmup", "/summary/failureBreakdown/1/count");

		List<String> details = run.details();

		assertThat(details).anyMatch((detail) -> detail
			.endsWith("refused 286 calls while it was down; 1 client request failed because of it."));
	}

	@Test
	void analyze_oneCallRefusedByAnOutage_namesItInTheSingular() throws IOException {
		Recorded run = Recorded.withOne("faultInTheWarmup", "/nodes/6/summary/rejected/outage");

		List<String> details = run.details();

		assertThat(details)
			.anyMatch((detail) -> detail.endsWith("Identity provider (introspection) refused 1 call while it was down; "
					+ "87 client requests failed because of it."));
	}

	/**
	 * A recorded run, at the current schema, with one of its counts cut to one.
	 *
	 * @param report its report, the count cut
	 * @param resolved the design it simulated
	 */
	private record Recorded(ObjectNode report, JsonNode resolved) {

		/**
		 * The recorded case's run, the count at a JSON Pointer of its report cut to one.
		 */
		static Recorded withOne(String name, String pointer) throws IOException {
			JsonNode input = recordedCase(name).path("input");
			ObjectNode report = (ObjectNode) SchemaDepartures.asGiven("report", input.path("report"));
			cutToOne(report, pointer);
			return new Recorded(report, SchemaDepartures.asGiven("resolved", input.path("resolved")));
		}

		List<String> details() {
			return new RunAnalysis(StandardComponents.registry())
				.analyze(RunReport.read(this.report), DesignDocuments.parse(this.resolved))
				.stream()
				.map(RunFinding::detail)
				.toList();
		}

		private static void cutToOne(ObjectNode report, String pointer) {
			int last = pointer.lastIndexOf('/');
			((ObjectNode) report.at(pointer.substring(0, last))).put(pointer.substring(last + 1), 1);
		}

		private static JsonNode recordedCase(String name) throws IOException {
			try (InputStream cases = Objects.requireNonNull(RunAnalysisTests.class.getResourceAsStream(CASES), CASES)) {
				for (JsonNode call : JSON.readTree(cases)) {
					if (name.equals(call.path("name").stringValue())) {
						return call;
					}
				}
			}
			throw new IllegalArgumentException(CASES + " has no case " + name);
		}

	}

}
