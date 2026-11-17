package io.archsim.engine.cost;

import java.util.List;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.OmitWhenDefault;

/**
 * A design's monthly cost: its peak hours at the peak and the rest of the month at the
 * base load (ADR-0074). Written by {@code RecordCodec}.
 *
 * @param monthly the monthly cost
 * @param perMillionRequests the cost per million successful requests; {@code null} before
 * a run
 * @param peakHours the hours of the month billed at the peak: the workload's, or none for
 * a pattern whose peak is its base
 * @param hourly an hour at the base load and an hour at the peak
 * @param byNode each part's cost, the largest first
 * @param unpriced the ids of the parts the month leaves out, for want of a price (report
 * 1.17, ADR-0081); left out when there are none
 * @param notes how it was priced and what it leaves out
 * @param hasCost whether any part has a price
 * @param currency always {@code USD}
 * @param hoursPerMonth the hours a month is billed for
 * @param rates the rates it was priced at: the generic illustrative ones, or the
 * snapshot's
 * @param pricing the prices it used: the snapshot, the purchase option and the families
 */
public record CostEstimate(double monthly, @Nullable Double perMillionRequests, double peakHours, HourlyCost hourly,
		List<CostLine> byNode, @OmitWhenDefault @Default("[]") List<String> unpriced, List<String> notes,
		boolean hasCost, String currency, int hoursPerMonth, ObjectNode rates, ObjectNode pricing) {

	/**
	 * Copies the lists and the rates and prices, so the record does not change.
	 */
	public CostEstimate {
		byNode = List.copyOf(byNode);
		unpriced = List.copyOf(unpriced);
		notes = List.copyOf(notes);
		rates = rates.deepCopy();
		pricing = pricing.deepCopy();
	}

	/**
	 * The rates it was priced at, as a copy: a record does not change.
	 * @return a copy of the rates
	 */
	@Override
	public ObjectNode rates() {
		return this.rates.deepCopy();
	}

	/**
	 * The prices it used, as a copy: a record does not change.
	 * @return a copy of the prices
	 */
	@Override
	public ObjectNode pricing() {
		return this.pricing.deepCopy();
	}

}
