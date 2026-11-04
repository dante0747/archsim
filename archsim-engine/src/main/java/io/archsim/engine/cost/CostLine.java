package io.archsim.engine.cost;

import java.util.List;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.OmitWhenDefault;

/**
 * One part's monthly cost.
 *
 * @param id the part's id
 * @param label its label
 * @param type its component type
 * @param technology its technology; {@code null} when it has none
 * @param instances its billable instances; zero for a part billed per use
 * @param unitPrice its price per hour or per million; {@code null} when it has none
 * @param priceSource where the price came from; {@code null} when it has none
 * @param unit what the unit price is per
 * @param monthly its monthly cost
 * @param basis how the cost was worked out, in words
 * @param creditsMonthly a burstable type's surplus CPU credits a month; left out when
 * none
 * @param controlPlaneMonthly a managed cluster's control plane a month; left out when
 * none
 * @param sku the snapshot's instance type it runs on; left out when none
 * @param snapshotDate the date of the snapshot that priced it; left out when none did
 * @param items each priced quantity of a managed or per-request part; left out for other
 * parts
 */
public record CostLine(String id, String label, String type, @Nullable String technology, double instances,
		@Nullable Double unitPrice, @Nullable PriceBasis priceSource, CostUnit unit, double monthly, String basis,
		@OmitWhenDefault @Nullable Double creditsMonthly, @OmitWhenDefault @Nullable Double controlPlaneMonthly,
		@OmitWhenDefault @Nullable String sku, @OmitWhenDefault @Nullable String snapshotDate,
		@OmitWhenDefault @Nullable List<CostItem> items) {

	/**
	 * Copies the items, so the record does not change.
	 */
	public CostLine {
		items = (items != null) ? List.copyOf(items) : null;
	}

}
