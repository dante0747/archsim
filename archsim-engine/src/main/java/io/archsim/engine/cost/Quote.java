package io.archsim.engine.cost;

import org.jspecify.annotations.Nullable;

/**
 * A price per instance-hour and where it came from.
 *
 * @param price the price; {@code null} when the part has none
 * @param basis where it came from; {@code null} when the part has no price
 * @param sku the snapshot's instance type it is the price of
 * @param note a remark on how it was priced
 */
record Quote(@Nullable Double price, @Nullable PriceBasis basis, @Nullable String sku, @Nullable String note) {

	/** No price at all. */
	static final Quote NONE = new Quote(null, null, null, null);

}
