package io.archsim.engine.estimate;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.load.CacheMisses;
import io.archsim.engine.load.LoadEstimate;
import io.archsim.engine.load.LoadEstimator;
import io.archsim.engine.scenario.ScenarioResolver;
import io.archsim.engine.simulator.SimulationCases;
import io.archsim.model.DesignDocuments;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.Workload;
import io.archsim.model.pricing.PriceSnapshots;
import io.archsim.parity.GoldenFile;
import io.archsim.parity.SchemaDepartures;

/**
 * Every design recorded with the frozen engine's estimate of it: the golden files'
 * estimates and runs, the reports their calibrations replayed, the recorded simulations,
 * and the estimate's and the analysis's own cases. A report's design is the one it
 * simulated, which its input holds.
 */
final class RecordedEstimates {

	private static final String ESTIMATE_DESIGN = "archsim_engine.estimate.estimate_design";

	private static final String SIMULATIONS = "simulations";

	private static final String ESTIMATE = "estimate";

	private static final String REPORT = "report";

	private static final String NAME = "name";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final ScenarioResolver RESOLVER = new ScenarioResolver(StandardComponents.registry(),
			PriceSnapshots.shipped());

	private static final LoadEstimator LOADS = new LoadEstimator(StandardComponents.registry());

	private static final InstantEstimator ESTIMATOR = new InstantEstimator(StandardComponents.registry());

	private RecordedEstimates() {
	}

	/**
	 * Every recorded design with the frozen engine's estimate of it.
	 * @return the designs, the golden files' first
	 * @throws IOException when a case file cannot be read
	 */
	static List<RecordedEstimate> all() throws IOException {
		List<RecordedEstimate> designs = new ArrayList<>();
		GoldenFile.all().stream().map(GoldenFile::read).forEach((file) -> addGolden(file, designs));
		SimulationCases.recorded()
			.forEach((recorded) -> designs
				.add(simulated(Source.SIMULATION, recorded.get(NAME).stringValue(), recorded.get(REPORT))));
		for (JsonNode entry : cases("cases.json")) {
			designs.add(new RecordedEstimate(Source.ESTIMATE_CASE, entry.path(NAME).asString(),
					DesignDocuments.parse(SchemaDepartures.asCurrent(entry.path("resolved"))), entry.path(ESTIMATE)));
		}
		for (JsonNode entry : cases("/io/archsim/engine/analysis/cases.json")) {
			if (entry.path("analysis").has("output")) {
				designs.add(new RecordedEstimate(Source.ANALYSIS_CASE, entry.path(NAME).asString(), resolved(entry),
						entry.at("/analysis/output/estimate")));
			}
		}
		return designs;
	}

	// a golden file's estimates, the reports its calibrations replayed, and its runs
	private static void addGolden(GoldenFile file, List<RecordedEstimate> designs) {
		file.entries(ESTIMATE_DESIGN)
			.forEach((entry) -> designs.add(new RecordedEstimate(Source.GOLDEN_ESTIMATE, entry.toString(),
					DesignDocuments.parse(entry.input("resolved")), entry.expectedOutput())));
		file.entries()
			.stream()
			.filter((entry) -> entry.input().has(SIMULATIONS))
			.forEach((entry) -> entry.input()
				.get(SIMULATIONS)
				.forEach((report) -> designs.add(simulated(Source.CALIBRATION_REPLAY, entry.toString(), report))));
		file.root()
			.path("runs")
			.properties()
			.forEach((run) -> designs
				.add(simulated(Source.GOLDEN_RUN, file.name() + " " + run.getKey(), run.getValue().get(REPORT))));
	}

	// a recorded report: the design it simulated, which its input holds, and its estimate
	private static RecordedEstimate simulated(Source source, String name, JsonNode report) {
		ObjectNode design = (ObjectNode) SchemaDepartures.asCurrent(report.get("input"));
		design.put("kind", ArchitectureDocument.KIND);
		return new RecordedEstimate(source, name, DesignDocuments.parse(design), report.path(ESTIMATE));
	}

	// an analysis case's design as its scenario runs it
	private static ArchitectureDocument resolved(JsonNode entry) {
		return RESOLVER.resolve(DesignDocuments.load(entry.path("design")), entry.path("scenarioId").asString(null))
			.document();
	}

	private static JsonNode cases(String resource) throws IOException {
		try (InputStream in = Objects.requireNonNull(RecordedEstimates.class.getResourceAsStream(resource), resource)) {
			return JSON.readTree(in).path("cases");
		}
	}

	/** Where a design is recorded with the frozen engine's estimate of it. */
	enum Source {

		/** A golden file's {@code estimate_design} entry. */
		GOLDEN_ESTIMATE,

		/** A report a golden calibration replayed. */
		CALIBRATION_REPLAY,

		/** A golden template's recorded run. */
		GOLDEN_RUN,

		/** A recorded simulation ({@code simulator/cases.json.gz}). */
		SIMULATION,

		/** A case of the estimate's own ({@code estimate/cases.json}). */
		ESTIMATE_CASE,

		/** A case of the analysis's own ({@code analysis/cases.json}). */
		ANALYSIS_CASE

	}

	/**
	 * A design recorded with the frozen engine's estimate of it.
	 *
	 * @param source the kind of recording
	 * @param name where it is recorded
	 * @param design the resolved design
	 * @param python the frozen engine's estimate
	 */
	record RecordedEstimate(Source source, String name, ArchitectureDocument design, JsonNode python) {

		/**
		 * The design's load at its workload's peak, each TTL cache's misses estimated as
		 * the instant estimate estimates them.
		 * @return the parts' and links' loads
		 */
		LoadEstimate peakLoad() {
			Workload workload = this.design.workload();
			double peak = workload.peakRate();
			Map<String, Double> misses = LOADS.ttlMissShares(this.design.architecture(), workload, peak);
			return LOADS.estimate(this.design.architecture(), workload, peak, new CacheMisses(misses, null));
		}

		/**
		 * The design as the estimate reads it.
		 * @return its graph
		 */
		EstimateGraph graph() {
			return EstimateGraph.of(this.design.architecture(), StandardComponents.registry());
		}

		/**
		 * The Java's estimate of the design's parts.
		 * @return the parts, in the order the frozen engine's estimate has them
		 */
		JsonNode javaParts() {
			return ESTIMATOR.estimate(this.design).toJson().path("parts");
		}

		/**
		 * The frozen engine's estimate of the design's parts.
		 * @return the parts
		 */
		JsonNode pythonParts() {
			return this.python.path("parts");
		}

	}

}
