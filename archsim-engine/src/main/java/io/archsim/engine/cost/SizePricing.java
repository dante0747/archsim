package io.archsim.engine.cost;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.archsim.model.catalog.ComponentFacts;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.pricing.PriceSnapshots;

/**
 * The price per instance-hour of a part's size, whatever price is set on it: its instance
 * type with a price snapshot, else its size at generic rates.
 */
public final class SizePricing {

	private final ComponentRegistry registry;

	private final PriceSnapshots prices;

	private final ComponentFacts facts;

	/**
	 * Prices the sizes of parts of a registry's types.
	 * @param registry the component types
	 * @param prices the price snapshots on this machine
	 */
	public SizePricing(ComponentRegistry registry, PriceSnapshots prices) {
		this.registry = registry;
		this.prices = prices;
		this.facts = new ComponentFacts(registry);
	}

	/**
	 * The prices a design names.
	 * @param document the design
	 * @return its parts' size prices: the snapshot its pricing names, the generic rates
	 * when this machine does not have it (validation says so)
	 */
	public Prices of(ArchitectureDocument document) {
		DesignPricing pricing = DesignPricing.of(document.pricing(), this.prices);
		return new Prices(new Quotes(pricing.snapshot(), pricing.purchase(), Map.of()));
	}

	/** The size prices of one design's parts. */
	public final class Prices {

		private final Quotes quotes;

		Prices(Quotes quotes) {
			this.quotes = quotes;
		}

		/**
		 * The price of a part's size.
		 * @param node the part
		 * @return its price per instance-hour and the instance type it is the price of
		 */
		public SizePrice of(NodeSpec node) {
			Quote quote = this.quotes.sizeQuote(new CostPart(node, SizePricing.this.registry, SizePricing.this.facts));
			return new SizePrice(quote.price(), quote.sku());
		}

	}

	/**
	 * The price of a part's size.
	 *
	 * @param pricePerHour the price per instance-hour; {@code null} when the part has no
	 * size
	 * @param sku the snapshot's instance type it is the price of; {@code null} when none
	 */
	public record SizePrice(@Nullable Double pricePerHour, @Nullable String sku) {
	}

}
