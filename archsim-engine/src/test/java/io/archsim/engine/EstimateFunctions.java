package io.archsim.engine;

import java.util.Map;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;

import io.archsim.engine.estimate.InstantEstimator;
import io.archsim.model.DesignDocuments;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.parity.GoldenEntry;

/**
 * The instant estimate. Its {@code snapshot} input is the resolved document's
 * {@code simulation_input}, which the Java reads from the document itself.
 */
final class EstimateFunctions {

	static final Map<String, Function<GoldenEntry, JsonNode>> PORTED = Map.ofEntries(
			Map.entry("archsim_engine.estimate.estimate_design", EstimateFunctions::estimateDesign),
			Map.entry("archsim_engine.estimate.METHOD", (entry) -> GoldenJson.text(InstantEstimator.METHOD)), Map.entry(
					"archsim_engine.estimate.NOT_INCLUDED", (entry) -> GoldenJson.text(InstantEstimator.NOT_INCLUDED)));

	private static final InstantEstimator ESTIMATOR = new InstantEstimator(StandardComponents.registry());

	private EstimateFunctions() {
	}

	private static JsonNode estimateDesign(GoldenEntry entry) {
		return ESTIMATOR.estimate(DesignDocuments.parse(entry.input("resolved"))).toJson();
	}

}
