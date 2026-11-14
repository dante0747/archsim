package io.archsim.engine.cost;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Builds the lines of managed parts, which a price snapshot prices at its rates, each
 * dated by the snapshot.
 */
final class SnapshotLines {

	private final @Nullable String snapshotDate;

	/**
	 * Builds lines priced by a snapshot.
	 * @param snapshotDate the date of the snapshot
	 */
	SnapshotLines(@Nullable String snapshotDate) {
		this.snapshotDate = snapshotDate;
	}

	/**
	 * A managed part's line.
	 * @param part the part
	 * @param billing how it is billed
	 * @param basis how it was worked out
	 * @param items each priced quantity
	 * @return the line, priced by the snapshot
	 */
	CostLine line(CostPart part, Billing billing, String basis, List<CostItem> items) {
		return new CostLine(part.id(), part.label(), part.type(), part.technology(), billing.instances(), null,
				billing.unitPrice(), PriceBasis.SNAPSHOT, billing.unit(), billing.monthly(), basis, null, null, null,
				this.snapshotDate, items);
	}

	/**
	 * How a managed part is billed.
	 *
	 * @param instances its billable instances
	 * @param unitPrice its price per hour or per million; {@code null} when unknown
	 * @param unit what the unit price is per
	 * @param monthly its monthly cost
	 */
	record Billing(double instances, @Nullable Double unitPrice, CostUnit unit, double monthly) {
	}

}
