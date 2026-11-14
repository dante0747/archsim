package io.archsim.engine.cost;

import java.util.List;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

/**
 * A design priced at one load of its workload ({@link LevelPricing}).
 *
 * @param lines each part's line at the load, the dearest first
 * @param monthly a month at the load
 * @param successfulRps the successful requests a second at the load; {@code null} before
 * a run, or when the run measured none
 * @param notes how it was priced and what it leaves out
 * @param hasCost whether any part has a price
 * @param rates the rates it was priced at
 * @param pricing the prices it used
 */
record PricedLoad(List<CostLine> lines, double monthly, @Nullable Double successfulRps, List<String> notes,
		boolean hasCost, ObjectNode rates, ObjectNode pricing) {

	/**
	 * Copies the lists and the rates and prices, so the record does not change.
	 */
	PricedLoad {
		lines = List.copyOf(lines);
		notes = List.copyOf(notes);
		rates = rates.deepCopy();
		pricing = pricing.deepCopy();
	}

}
