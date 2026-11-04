package io.archsim.engine.pricing;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.RecordCodec;
import io.archsim.model.document.Pricing;
import io.archsim.model.pricing.PriceSnapshot;
import io.archsim.model.pricing.PriceSnapshots;
import io.archsim.model.pricing.PricingException;

/**
 * The price snapshot a design names and how it buys from it.
 *
 * @param snapshot the snapshot; the generic rates when the one named is not available
 * here (validation says so)
 * @param purchase the purchase option, families and burstable policy
 * @param unavailable why the snapshot named is not available here; {@code null} when it
 * is
 */
public record PricingChoice(PriceSnapshot snapshot, Purchase purchase, @Nullable String unavailable) {

	/**
	 * The choice a design's pricing makes.
	 * @param pricing the design's pricing
	 * @param prices the price snapshots available
	 * @return the snapshot and purchase
	 */
	public static PricingChoice of(Pricing pricing, PriceSnapshots prices) {
		Purchase purchase = new Purchase(pricing.purchaseOption(), pricing.families(), pricing.burstable());
		try {
			return new PricingChoice(prices.forPricing(RecordCodec.write(pricing)), purchase, null);
		}
		catch (PricingException ex) {
			return new PricingChoice(PriceSnapshot.GENERIC, purchase, ex.getMessage());
		}
	}

	/**
	 * The instance types the design may buy.
	 * @return the snapshot's types, bought as the design buys them
	 */
	public InstanceTypes instanceTypes() {
		return new InstanceTypes(this.snapshot, this.purchase);
	}

}
