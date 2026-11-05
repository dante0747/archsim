package io.archsim.engine;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.findings.Classification;
import io.archsim.engine.findings.RunAnalysis;
import io.archsim.engine.report.RunReport;
import io.archsim.parity.GoldenEntry;
import io.archsim.parity.GoldenFile;
import io.archsim.parity.JsonDiff;
import io.archsim.parity.Parity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run's findings and each part's status. The findings: on the branches the golden files
 * do not reach (no traffic, wasted work, autoscaling, replication lag, pending pods,
 * memory and downstream bottlenecks, client timeouts, throttled stores and balancers,
 * faults in the warm-up, after the run or not recovered, and more), each case
 * ({@code findings/cases.json}, which {@code scripts/export_findings_cases.py} writes
 * from the frozen engine's reports, changed) checked as the golden files are. Each part's
 * status and why: on those cases, and on every run of the golden files, whose reports
 * carry the status the analysis set.
 */
@DisplayName("A run's findings and each part's status match the frozen Python engine's")
class FindingsCasesTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String CASES = "findings/cases.json";

	private static final JsonNode ENTRIES = read();

	@TestFactory
	Stream<DynamicTest> analyze_everyCase_givesTheFrozenEnginesFindings() {
		return cases().stream()
			.map((entry) -> DynamicTest.dynamicTest(entry.pointer(), () -> Parity.check(entry, EngineFunctions::call)));
	}

	@TestFactory
	Stream<DynamicTest> classify_everyCase_givesTheFrozenEnginesStatuses() {
		List<DynamicTest> tests = new ArrayList<>();
		for (JsonNode call : ENTRIES) {
			JsonNode report = call.path("input").path("report");
			tests.add(DynamicTest.dynamicTest(call.get("name").stringValue(),
					() -> assertThat(JsonDiff.differences(call.get("statuses"), statuses(report))).isEmpty()));
		}
		return tests.stream();
	}

	@TestFactory
	Stream<DynamicTest> classify_everyGoldenRun_givesTheStatusesItsReportCarries() {
		List<DynamicTest> tests = new ArrayList<>();
		for (String name : GoldenFile.templates()) {
			GoldenFile file = GoldenFile.read(name);
			file.root().path("runs").properties().forEach((run) -> {
				JsonNode report = run.getValue().path("report");
				tests.add(DynamicTest.dynamicTest(name + " " + run.getKey(),
						() -> assertThat(JsonDiff.differences(carried(report), statuses(report))).isEmpty()));
			});
		}
		return tests.stream();
	}

	/**
	 * Each part's status and why, as the analysis sets them: {@code {id: [status,
	 * reasons]}}.
	 */
	private static ObjectNode statuses(JsonNode report) {
		ObjectNode statuses = JsonNodeFactory.instance.objectNode();
		Map<String, Classification> classified = RunAnalysis.classify(RunReport.read(report));
		classified.forEach((id, status) -> {
			ArrayNode reasons = JsonNodeFactory.instance.arrayNode();
			status.reasons().forEach(reasons::add);
			statuses.putArray(id).add(status.status().json()).add(reasons);
		});
		return statuses;
	}

	/** The statuses a golden report carries, set by the analysis during its run. */
	private static ObjectNode carried(JsonNode report) {
		ObjectNode statuses = JsonNodeFactory.instance.objectNode();
		report.path("nodes")
			.forEach((node) -> statuses.putArray(node.path("id").stringValue())
				.add(node.path("status").stringValue())
				.add(node.path("statusReasons")));
		return statuses;
	}

	private static List<GoldenEntry> cases() {
		List<GoldenEntry> entries = new ArrayList<>();
		for (JsonNode call : ENTRIES) {
			entries.add(new GoldenEntry(CASES, call.get("name").stringValue(), call.get("fn").stringValue(),
					(ObjectNode) call.get("input"), call.get("output"), null));
		}
		return entries;
	}

	private static JsonNode read() {
		try (InputStream cases = Objects
			.requireNonNull(FindingsCasesTests.class.getResourceAsStream("/io/archsim/engine/" + CASES), CASES)) {
			return JSON.readTree(cases);
		}
		catch (IOException ex) {
			throw new IllegalStateException("cannot read " + CASES, ex);
		}
	}

}
