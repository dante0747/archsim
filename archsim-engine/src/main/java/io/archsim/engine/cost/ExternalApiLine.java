package io.archsim.engine.cost;

import java.util.List;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.text.HalfUp;

/** A third-party API, priced per call at the base load. */
final class ExternalApiLine {

	private ExternalApiLine() {
	}

	/**
	 * An API's line.
	 * @param part the API
	 * @param price its price per million calls and where it came from (ADR-0081)
	 * @param use its usage at the base load; {@code null} when unknown
	 * @return its line; without a unit price when the API has none
	 */
	static CostLine of(CostPart part, ApiPrice price, @Nullable Usage use) {
		Double perMillion = price.perMillion();
		Double rate = (use != null) ? use.rps() : null;
		String basis = (rate != null) ? HalfUp.fixed(rate, 1) + " calls/s at the base load" : "per call";
		if (perMillion == null || rate == null) {
			return line(part, new Charge(perMillion, price.source(), 0.0, null), basis);
		}
		double calls = rate * CostEstimator.SECONDS_PER_MONTH;
		return line(part, new Charge(perMillion, price.source(), calls * perMillion / CostItem.MILLION,
				List.of(CostItem.perMillion("calls", calls, perMillion, "call"))), basis);
	}

	private static CostLine line(CostPart part, Charge charge, String basis) {
		return new CostLine(part.id(), part.label(), part.type(), part.technology(), 0, null, charge.unitPrice(),
				charge.source(), CostUnit.MILLION, charge.monthly(), basis, null, null, null, null, charge.items());
	}

	/**
	 * What an API's calls cost.
	 *
	 * @param unitPrice the price of a million calls; {@code null} when it has none
	 * @param source where the price came from; {@code null} when it has none
	 * @param monthly the calls' monthly cost
	 * @param items the calls, priced; {@code null} without a price and a call rate
	 */
	private record Charge(@Nullable Double unitPrice, @Nullable PriceBasis source, double monthly,
			@Nullable List<CostItem> items) {
	}

}
