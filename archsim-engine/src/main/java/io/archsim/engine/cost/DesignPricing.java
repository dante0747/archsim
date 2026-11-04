package io.archsim.engine.cost;

import java.util.Optional;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.pricing.PricingChoice;
import io.archsim.engine.pricing.Purchase;
import io.archsim.model.document.Burstable;
import io.archsim.model.document.Pricing;
import io.archsim.model.pricing.PriceSnapshot;
import io.archsim.model.pricing.PriceSnapshots;

/**
 * The prices a design's pricing names, as the cost estimate reads them: its price
 * snapshot, else the generic rates with a note, and the purchase option and families it
 * buys with. A burstable type is bought only when it stays under its baseline or its
 * family is named, whatever the pricing says.
 */
final class DesignPricing {

	private final PriceSnapshot snapshot;

	private final Purchase purchase;

	private final @Nullable String note;

	private DesignPricing(PriceSnapshot snapshot, Purchase purchase, @Nullable String note) {
		this.snapshot = snapshot;
		this.purchase = purchase;
		this.note = note;
	}

	/**
	 * What a design's pricing names.
	 * @param pricing the design's pricing
	 * @param prices the price snapshots on this machine
	 * @return its prices
	 */
	static DesignPricing of(Pricing pricing, PriceSnapshots prices) {
		PricingChoice choice = PricingChoice.of(pricing, prices);
		// the Python engine's quote picks a machine without the design's burstable policy
		Purchase purchase = new Purchase(choice.purchase().option(), choice.purchase().families(), Burstable.AUTO);
		String unavailable = choice.unavailable();
		return new DesignPricing(choice.snapshot(), purchase,
				(unavailable != null) ? unavailable + " Priced at generic illustrative rates instead." : null);
	}

	PriceSnapshot snapshot() {
		return this.snapshot;
	}

	Purchase purchase() {
		return this.purchase;
	}

	/**
	 * Why the design is priced at the generic rates.
	 * @return the note; empty when this machine has the snapshot the design names
	 */
	Optional<String> note() {
		return Optional.ofNullable(this.note);
	}

	/**
	 * A rate of the snapshot's.
	 * @param name the rate's name
	 * @return the rate; {@code null} when the snapshot does not have it
	 */
	@Nullable Double rate(String name) {
		return this.snapshot.rates().get(name);
	}

	/**
	 * The rates the estimate was priced at, as the estimate reports them.
	 * @return the generic rates, or the snapshot's
	 */
	ObjectNode rates() {
		ObjectNode rates = JsonNodeFactory.instance.objectNode();
		if (this.snapshot.illustrative()) {
			return rates.put("kind", "illustrative")
				.put("perVcpuHour", GenericRates.VCPU_HOUR)
				.put("perGibHour", GenericRates.GIB_HOUR)
				.put("perStorageGibMonth", GenericRates.STORAGE_GIB_MONTH);
		}
		rates.put("kind", "snapshot");
		this.snapshot.rates().forEach((name, value) -> {
			if (value != null) {
				rates.put(name, value);
			}
		});
		return rates;
	}

	/**
	 * The snapshot and how the design buys from it, as the estimate reports them.
	 * @return the pricing
	 */
	ObjectNode toJson() {
		ObjectNode pricing = this.snapshot.toJson();
		pricing.put("purchaseOption", this.purchase.option());
		this.purchase.families().forEach(pricing.putArray("families")::add);
		return pricing;
	}

}
