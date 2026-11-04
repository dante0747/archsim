package io.archsim.engine.pricing;

import org.jspecify.annotations.Nullable;

import io.archsim.model.pricing.Sku;
import io.archsim.model.pricing.SkuCategory;

/**
 * The instance type a part runs on in a price snapshot.
 *
 * @param category the price list it comes from
 * @param engine the engine it runs, for a database, cache or stream
 * @param sku the instance type; {@code null} when no type covers the size
 * @param selfManaged the engine has no managed offering in the snapshot, so it runs on
 * compute instances
 * @param named it is the part's own {@code instanceType}
 */
public record Machine(SkuCategory category, @Nullable String engine, @Nullable Sku sku, boolean selfManaged,
		boolean named) {

}
