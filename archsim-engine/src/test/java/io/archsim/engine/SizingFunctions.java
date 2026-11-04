package io.archsim.engine;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.estimate.InstantEstimator;
import io.archsim.engine.scenario.ResolvedDesign;
import io.archsim.engine.scenario.ScenarioResolver;
import io.archsim.engine.simulation.SimulationLimits;
import io.archsim.engine.sizing.AutoSizer;
import io.archsim.engine.sizing.BaseBusy;
import io.archsim.engine.sizing.SizedDocument;
import io.archsim.engine.sizing.SizingSummaries;
import io.archsim.engine.sweep.SweepRange;
import io.archsim.model.DesignDocuments;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.pricing.PriceSnapshots;
import io.archsim.parity.GoldenEntry;

/** Auto-sizing and resolving the document a run simulates. */
final class SizingFunctions {

	static final Map<String, Function<GoldenEntry, JsonNode>> PORTED = Map.ofEntries(
			Map.entry("archsim_engine.sizing.auto_sizes", SizingFunctions::autoSizes),
			Map.entry("archsim_engine.sizing.apply_auto_sizes", SizingFunctions::applyAutoSizes),
			Map.entry("archsim_engine.sizing.base_busy", SizingFunctions::baseBusy),
			Map.entry("archsim_engine.scenarios.resolve_scenario_sized", SizingFunctions::resolveScenarioSized),
			Map.entry("archsim_engine.sweep.sweep_range", SizingFunctions::sweepRange),
			Map.entry("archsim_engine.sizing.sizing_summary", SizingFunctions::sizingSummary));

	private static final AutoSizer SIZER = new AutoSizer(StandardComponents.registry(), PriceSnapshots.shipped());

	private static final ScenarioResolver RESOLVER = new ScenarioResolver(StandardComponents.registry(),
			PriceSnapshots.shipped());

	private static final SizingSummaries SUMMARIES = new SizingSummaries(StandardComponents.registry(),
			PriceSnapshots.shipped());

	private static final InstantEstimator ESTIMATOR = new InstantEstimator(StandardComponents.registry());

	private SizingFunctions() {
	}

	/**
	 * Its {@code estimate} input is the resolved design's instant estimate, worked out
	 * here.
	 */
	private static JsonNode sizingSummary(GoldenEntry entry) {
		ArchitectureDocument resolved = DesignDocuments.parse(entry.input("resolved"));
		return RecordCodec.write(SUMMARIES.of(DesignDocuments.parse(entry.input("document")), resolved,
				GoldenSizes.read(entry.input("sizes")), ESTIMATOR.estimate(resolved)));
	}

	private static JsonNode autoSizes(GoldenEntry entry) {
		return GoldenSizes.write(SIZER.sizes(DesignDocuments.parse(entry.input("doc"))));
	}

	/** {@code {document, sizes}}. */
	private static JsonNode applyAutoSizes(GoldenEntry entry) {
		SizedDocument sized = SIZER.applyTo((ObjectNode) entry.input("doc").deepCopy());
		ObjectNode output = GoldenJson.NODES.objectNode();
		output.set("document", sized.document());
		output.set("sizes", GoldenSizes.write(sized.sizes()));
		return output;
	}

	private static JsonNode baseBusy(GoldenEntry entry) {
		ObjectNode output = GoldenJson.NODES.objectNode();
		BaseBusy.of(GoldenSizes.read(entry.input("sizes"))).forEach(output::put);
		return output;
	}

	/** {@code {resolved, scenario, sizes}}. */
	private static JsonNode resolveScenarioSized(GoldenEntry entry) {
		ResolvedDesign resolved = RESOLVER.resolve(DesignDocuments.parse(entry.input("doc")),
				GoldenJson.readNullableText(entry.input("scenarioId")));
		ObjectNode output = GoldenJson.NODES.objectNode();
		output.set("resolved", DesignDocuments.toJson(resolved.document()));
		output.set("scenario", GoldenJson.orNone(resolved.scenario().map(RecordCodec::write)));
		output.set("sizes", GoldenSizes.write(resolved.sizes()));
		return output;
	}

	/** {@code (lo, hi)}: the range around the resolved design's load. */
	private static JsonNode sweepRange(GoldenEntry entry) {
		ResolvedDesign resolved = RESOLVER.resolve(DesignDocuments.load(entry.input("document")),
				GoldenJson.readNullableText(entry.input("scenarioId")));
		SweepRange range = SweepRange.around(resolved.document().workload(), SimulationLimits.DEFAULT,
				entry.input("durationS").doubleValue());
		return GoldenJson.numbers(List.of(range.fromRps(), range.toRps()));
	}

}
