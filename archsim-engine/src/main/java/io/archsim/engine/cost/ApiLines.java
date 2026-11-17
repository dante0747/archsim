package io.archsim.engine.cost;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.archsim.model.document.ProvenanceKind;

/**
 * A design's third-party APIs priced at one load: each one's line, the ones without a
 * price, which the month leaves out, and the notes on where their prices came from
 * (ADR-0081).
 */
final class ApiLines {

	private final Map<String, ProvenanceKind> provenance;

	private final List<String> unpriced = new ArrayList<>();

	private final List<String> notes = new ArrayList<>();

	/**
	 * No API priced yet.
	 * @param provenance where the design's values came from, by input path
	 */
	ApiLines(Map<String, ProvenanceKind> provenance) {
		this.provenance = provenance;
	}

	/**
	 * Prices an API.
	 * @param part the API
	 * @param use its usage at the load; {@code null} when unknown
	 * @return its line
	 */
	CostLine add(CostPart part, @Nullable Usage use) {
		ApiPrice price = ApiPrice.of(part, this.provenance.get("nodes/" + part.id() + "/" + ApiPrice.PER_MILLION));
		if (price == ApiPrice.Unpriced.INSTANCE) {
			this.unpriced.add(part.id());
		}
		price.note(part).ifPresent(this.notes::add);
		return ExternalApiLine.of(part, price, use);
	}

	/**
	 * The APIs without a price.
	 * @return their ids, in the design's order
	 */
	List<String> unpriced() {
		return List.copyOf(this.unpriced);
	}

	/**
	 * The notes on the APIs' prices.
	 * @return the notes, in the design's order
	 */
	List<String> notes() {
		return List.copyOf(this.notes);
	}

}
