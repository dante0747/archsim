package io.archsim.engine.pricing;

import java.util.List;

import io.archsim.model.document.Burstable;

/**
 * How a design buys its machines, from its {@code pricing}.
 *
 * @param option the purchase option, such as {@code on_demand}
 * @param families the instance families the design names: they restrict the price lists
 * that have them, and allow a burstable family
 * @param burstable whether auto-sizing may pick a burstable type by itself
 */
public record Purchase(String option, List<String> families, Burstable burstable) {

	/** On demand, any family, burstable types where they pass the credit check. */
	public static final Purchase ON_DEMAND = new Purchase("on_demand", List.of(), Burstable.AUTO);

	/**
	 * Copies the families, so the record does not change.
	 */
	public Purchase {
		families = List.copyOf(families);
	}

}
