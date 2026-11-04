package io.archsim.engine.provenance;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.DesignDocuments;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.document.ArchitectureDocument;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where each input of a design came from. The cases the golden files do not reach (inputs
 * measured before, parts left to validation) expect what the Python engine 0.16.0 gives.
 */
class InputProvenancesTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String API = """
			{"id": "api", "type": "service", "label": "API",
			 "config": {"serviceTimeMs": {"kind": "constant", "value": 10}}}""";

	private static final String GO_API = """
			{"id": "api", "type": "service",
			 "config": {"technology": "go", "serviceTimeMs": {"kind": "constant", "value": 10}}}""";

	private static final String MEASURED = """
			{"kind": "measured", "source": "prometheus", "query": "histogram_quantile(0.5, rate(x[5m]))",
			 "timeRange": "2026-10-01/2026-10-08", "fitError": 0.04, "fittedAt": "2026-10-09T00:00:00Z",
			 "value": %s}""";

	private static final String TEN_MS = """
			{"kind": "constant", "value": 10}""";

	private static final String DEFAULT_WORKLOAD = """
			{"rps": 100}""";

	private final InputProvenances inputs = new InputProvenances(StandardComponents.registry());

	@Test
	void of_aMeasuredInputStillAtItsValue_isMeasuredAsRecorded() {
		Input input = input(design(API, record("nodes/api/serviceTimeMs", MEASURED.formatted(TEN_MS))), "api",
				"serviceTimeMs");

		ObjectNode expected = (ObjectNode) json(MEASURED.formatted("null"));
		expected.remove("value");
		assertThat(input.value()).isEqualTo("constant 10 ms");
		assertThat(RecordCodec.write(input.provenance())).isEqualTo(expected);
	}

	@Test
	void of_aMeasuredInputChangedSince_isClassifiedAgainWithANote() {
		Input input = input(design(API, record("nodes/api/serviceTimeMs", MEASURED.formatted("""
				{"kind": "constant", "value": 12}"""))), "api", "serviceTimeMs");

		assertThat(RecordCodec.write(input.provenance())).isEqualTo(json("""
				{"kind": "assumed", "note": "changed since it was measured (prometheus, 2026-10-01/2026-10-08)"}"""));
	}

	@Test
	void of_aMeasuredInputChangedWithNoSourceOrTime_saysTelemetry() {
		Input input = input(design(API, record("nodes/api/serviceTimeMs", """
				{"kind": "measured", "value": 99}""")), "api", "serviceTimeMs");

		assertThat(input.provenance().note()).isEqualTo("changed since it was measured (telemetry)");
	}

	@Test
	void of_aMeasurementWithoutItsValue_isTakenAtItsWord() {
		String measured = """
				{"kind": "measured", "source": "otlp"}""";

		Input input = input(design(API, record("nodes/api/serviceTimeMs", measured)), "api", "serviceTimeMs");

		assertThat(RecordCodec.write(input.provenance())).isEqualTo(json(measured));
	}

	@Test
	void of_aDocumentedDefault_namesTheTechnologyWhoseDocumentationStatesIt() {
		Input input = input(design(GO_API, JSON.createObjectNode()), "api", "gcPauseMs");

		assertThat(input.value()).isEqualTo("0.1 ms");
		assertThat(RecordCodec.write(input.provenance())).isEqualTo(json("""
				{"kind": "documented", "source": "https://go.dev/doc/go1.8",
				 "note": "default for go, stated in its documentation"}"""));
	}

	@Test
	void of_anInputMeasuredBeforeThatIsNowItsTechnologysDefault_isDocumentedWithTheChangeNote() {
		Input input = input(design(GO_API, record("nodes/api/gcPauseMs", """
				{"kind": "measured", "source": "prometheus", "value": 3}""")), "api", "gcPauseMs");

		assertThat(RecordCodec.write(input.provenance())).isEqualTo(json("""
				{"kind": "documented", "source": "https://go.dev/doc/go1.8",
				 "note": "changed since it was measured (prometheus)"}"""));
	}

	@Test
	void of_anInputMeasuredBeforeThatIsNowTheDefault_isTheDefaultWithTheChangeNote() {
		Input input = input(design(GO_API, record("nodes/api/memoryBaselineMb", """
				{"kind": "measured", "source": "otlp", "value": 700}""")), "api", "memoryBaselineMb");

		assertThat(input.value()).isEqualTo("256 MiB");
		assertThat(RecordCodec.write(input.provenance())).isEqualTo(json("""
				{"kind": "illustrative", "source": "ArchSim default",
				 "note": "changed since it was measured (otlp)"}"""));
	}

	@Test
	void of_aRecordedSettingThatIsNotAnAssumption_isListedWithItsRecord() {
		String assumed = """
				{"kind": "assumed", "note": "from the runbook"}""";

		Input input = input(design(API, record("nodes/api/workersPerReplica", assumed)), "api", "workersPerReplica");

		assertThat(input.fieldLabel()).isEqualTo("Workers per replica");
		assertThat(input.value()).isEqualTo("4");
		assertThat(RecordCodec.write(input.provenance())).isEqualTo(json(assumed));
	}

	@Test
	void of_aMeasuredLoadCurve_readsAsPythonWritesItsPoints() {
		String points = """
				[{"rps": 100, "factor": 1.2}, {"rps": 2000.5, "factor": 3}]""";
		ObjectNode api = (ObjectNode) json(API);
		((ObjectNode) api.get("config")).set("serviceTimeByLoad", json(points));

		Input input = input(design(api.toString(), record("nodes/api/serviceTimeByLoad", """
				{"kind": "measured", "source": "prometheus", "value": %s}""".formatted(points))), "api",
				"serviceTimeByLoad");

		assertThat(input.value()).isEqualTo("[LoadPoint(rps=100.0, factor=1.2), LoadPoint(rps=2000.5, factor=3.0)]");
		assertThat(input.provenance().kind()).isEqualTo(InputKind.MEASURED);
	}

	@Test
	void of_aLinkMeasuredBefore_saysWhenItWasMeasured() {
		Input input = input(design(API, record("edges/e1/latencyMs", """
				{"kind": "measured", "timeRange": "last week", "value": {"kind": "constant", "value": 5}}""")), "e1",
				"latencyMs");

		assertThat(input.label()).isEqualTo("clients → api");
		assertThat(input.value()).isEqualTo("constant 3 ms");
		assertThat(RecordCodec.write(input.provenance())).isEqualTo(json("""
				{"kind": "assumed", "note": "changed since it was measured (last week)"}"""));
	}

	@Test
	void of_aMeasuredWorkloadInput_isMeasured() {
		String measured = """
				{"kind": "measured", "source": "otlp", "value": 0.75}""";

		Input input = input(design(API, record("workload/readRatio", measured), """
				{"rps": 100, "readRatio": 0.75}"""), null, "readRatio");

		assertThat(input.value()).isEqualTo("75%");
		assertThat(RecordCodec.write(input.provenance())).isEqualTo(json("""
				{"kind": "measured", "source": "otlp"}"""));
	}

	@Test
	void of_aMeasurementOffOnlyByFloatNoise_stillHolds() {
		Input input = input(design(API, record("workload/rps", """
				{"kind": "measured", "source": "otlp", "value": 100.0000000001}""")), null, "rps");

		assertThat(input.value()).isEqualTo("100 req/s");
		assertThat(input.provenance().kind()).isEqualTo(InputKind.MEASURED);
	}

	@Test
	void of_partsOfAnUnknownTypeOrWithAConfigThatIsNotValid_areLeftToValidation() {
		assertThat(this.inputs.of(designWithPartsLeftOut())).filteredOn((input) -> input.scope() == InputScope.NODE)
			.extracting(Input::id)
			.containsOnly("api");
	}

	@Test
	void counts_theInputsOfADesign_countEveryKindInTheReportsOrder() {
		List<Input> found = this.inputs.of(designWithPartsLeftOut());

		assertThat(InputProvenances.counts(found)).containsExactly(Map.entry(InputKind.MEASURED, 0),
				Map.entry(InputKind.DOCUMENTED, 0), Map.entry(InputKind.ASSUMED, 2),
				Map.entry(InputKind.ILLUSTRATIVE, 12));
	}

	@Test
	void of_anInput_isWrittenAsThePythonEngineWritesIt() {
		Input input = input(design(API, JSON.createObjectNode()), null, "rps");

		assertThat(RecordCodec.write(input)).isEqualTo(json("""
				{"scope": "workload", "id": null, "label": "Workload", "field": "rps", "fieldLabel": "Baseline load",
				 "value": "100 req/s", "provenance": {"kind": "illustrative", "source": "ArchSim default"}}"""));
	}

	private Input input(ArchitectureDocument design, @Nullable String id, String field) {
		return this.inputs.of(design)
			.stream()
			.filter((input) -> Objects.equals(input.id(), id) && input.field().equals(field))
			.findFirst()
			.orElseThrow();
	}

	/**
	 * A part of an unknown type, a service whose config is not valid, and the service.
	 */
	private static ArchitectureDocument designWithPartsLeftOut() {
		return design("""
				{"id": "x", "type": "mystery"}, {"id": "bad", "type": "service", "config": {"replicas": -1}}, %s"""
			.formatted(API), JSON.createObjectNode());
	}

	private static ObjectNode record(String path, String record) {
		ObjectNode provenance = JSON.createObjectNode();
		provenance.set(path, json(record));
		return provenance;
	}

	private static ArchitectureDocument design(String nodes, ObjectNode provenance) {
		return design(nodes, provenance, DEFAULT_WORKLOAD);
	}

	private static ArchitectureDocument design(String nodes, ObjectNode provenance, String workload) {
		ObjectNode design = (ObjectNode) json("""
				{"schemaVersion": "1.7", "kind": "archsim.architecture", "metadata": {"name": "t"},
				 "architecture": {"nodes": [{"id": "clients", "type": "traffic_source"}, %s],
				                  "edges": [{"id": "e1", "source": "clients", "target": "api",
				                             "config": {"latencyMs": {"kind": "constant", "value": 3}}}]},
				 "workload": %s}""".formatted(nodes, workload));
		design.set("provenance", provenance);
		return DesignDocuments.load(design);
	}

	private static JsonNode json(String text) {
		return JSON.readTree(text);
	}

}
