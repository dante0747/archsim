package io.archsim.engine.cost;

import io.archsim.model.pricing.PriceSnapshot;

/** What pricing a design's parts found, for the estimate's notes. */
final class CostFlags {

	private boolean priced;

	private boolean snapshotPriced;

	private boolean sizePriced;

	private boolean presetKept;

	private boolean missingCallRate;

	private boolean impliedPods;

	/**
	 * A part priced per instance-hour: priced when its quote has a price, and by how.
	 * @param quote its quote
	 * @param snapshot the price snapshot: a preset's price is kept only when a snapshot
	 * does not cover the part
	 */
	void markQuote(Quote quote, PriceSnapshot snapshot) {
		this.priced |= quote.price() != null;
		this.sizePriced |= quote.basis() == PriceBasis.SIZE;
		this.snapshotPriced |= quote.basis() == PriceBasis.SNAPSHOT;
		this.presetKept |= quote.basis() == PriceBasis.PRESET && !snapshot.illustrative();
	}

	/**
	 * A third-party API's line: priced when it has a price per call, and missing its call
	 * rate when it has a price but no calls priced.
	 * @param line its line
	 */
	void markCalls(CostLine line) {
		if (line.unitPrice() != null) {
			this.priced = true;
			this.missingCallRate |= line.items() == null;
		}
	}

	/** A part has a price. */
	void markPriced() {
		this.priced = true;
	}

	/** A part is priced by the snapshot. */
	void markSnapshotPriced() {
		this.snapshotPriced = true;
	}

	/** A service's pods run on implied Kubernetes nodes. */
	void markImpliedPods() {
		this.impliedPods = true;
	}

	boolean priced() {
		return this.priced;
	}

	boolean snapshotPriced() {
		return this.snapshotPriced;
	}

	boolean sizePriced() {
		return this.sizePriced;
	}

	boolean presetKept() {
		return this.presetKept;
	}

	boolean missingCallRate() {
		return this.missingCallRate;
	}

	boolean impliedPods() {
		return this.impliedPods;
	}

}
