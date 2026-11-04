package io.archsim.engine;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Assumptions;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.cost.CostEstimator;
import io.archsim.engine.estimate.InstantEstimator;
import io.archsim.model.DesignDocuments;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.ProvenanceKind;
import io.archsim.model.pricing.PriceSnapshots;
import io.archsim.parity.GoldenEntry;
import io.archsim.parity.JsonDiff;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cost estimate. Its {@code nodes} and {@code pricing} inputs are the document's own,
 * and its {@code estimate} input the document's instant estimate, which the Java works
 * out itself. A cost after a run, with a run's report, is ported with the report
 * functions.
 */
final class CostFunctions {

	static final Map<String, Function<GoldenEntry, JsonNode>> PORTED = Map.ofEntries(
			Map.entry("archsim_engine.cost.estimate_cost", CostFunctions::estimateCost),
			Map.entry("archsim_engine.cost.NOT_MODELLED", (entry) -> GoldenJson.text(CostEstimator.NOT_MODELLED)));

	private static final CostEstimator COSTS = new CostEstimator(StandardComponents.registry(),
			PriceSnapshots.shipped());

	private static final InstantEstimator ESTIMATOR = new InstantEstimator(StandardComponents.registry());

	private CostFunctions() {
	}

	private static JsonNode estimateCost(GoldenEntry entry) {
		if (!entry.input("report").isNull()) {
			Assumptions.abort("a cost with a run's report is ported with the report functions (stage 3, S3)");
		}
		JsonNode input = entry.input("document");
		assertThat(JsonDiff.differences(input.path("architecture").path("nodes"), entry.input("nodes")))
			.as("the nodes are the document's")
			.isEmpty();
		assertThat(JsonDiff.differences(input.path("pricing"), entry.input("pricing")))
			.as("the pricing is the document's")
			.isEmpty();
		ArchitectureDocument document = DesignDocuments.parse(input);
		Map<String, Double> baseBusy = new LinkedHashMap<>();
		entry.input("baseBusy")
			.properties()
			.forEach((busy) -> baseBusy.put(busy.getKey(), busy.getValue().doubleValue()));
		return RecordCodec.write(COSTS.estimate(document, provenance(entry.input("provenance")), baseBusy,
				ESTIMATOR.estimate(document)));
	}

	private static Map<String, ProvenanceKind> provenance(JsonNode kinds) {
		Map<String, ProvenanceKind> provenance = new LinkedHashMap<>();
		kinds.properties()
			.forEach((kind) -> provenance.put(kind.getKey(),
					Arrays.stream(ProvenanceKind.values())
						.filter((known) -> known.json().equals(kind.getValue().asString()))
						.findFirst()
						.orElseThrow()));
		return provenance;
	}

}
