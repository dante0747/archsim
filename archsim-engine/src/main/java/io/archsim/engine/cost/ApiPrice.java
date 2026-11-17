package io.archsim.engine.cost;

import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.archsim.model.catalog.Preset;
import io.archsim.model.document.ProvenanceKind;

/**
 * A third-party API's price per million calls, and where it came from (ADR-0081), by the
 * rule a part's instance-hour price follows ({@link Quotes}): a price a person set on the
 * part; else a technology preset's illustrative price, written on the part or, left
 * empty, the first of its technology's that has one; else none, which leaves the API out
 * of the month.
 */
sealed interface ApiPrice {

	/** The setting an API's price per million calls is set with. */
	String PER_MILLION = "costPerMillionRequests";

	/**
	 * An API's price. A written price counts as a person's when its provenance is
	 * measured or assumed, or when no preset of its technology has that price.
	 * @param part the API
	 * @param kind where its written price's value came from, by the design's provenance;
	 * {@code null} when it says nothing
	 * @return the price
	 */
	static ApiPrice of(CostPart part, @Nullable ProvenanceKind kind) {
		Double own = part.writtenNumber(PER_MILLION);
		if (own == null) {
			return firstPricedPreset(part, null).orElse(Unpriced.INSTANCE);
		}
		if (kind == ProvenanceKind.MEASURED || kind == ProvenanceKind.ASSUMED) {
			return new SetOnThePart(own);
		}
		return firstPricedPreset(part, own).orElseGet(() -> new SetOnThePart(own));
	}

	/**
	 * The price of a million calls.
	 * @return the price; {@code null} for an API without one
	 */
	@Nullable Double perMillion();

	/**
	 * Where the price came from.
	 * @return its source; {@code null} for an API without a price
	 */
	@Nullable PriceBasis source();

	/**
	 * What the cost's notes say of the price.
	 * @param part the API
	 * @return the note; empty for a price a person set
	 */
	Optional<String> note(CostPart part);

	/**
	 * The first preset of the part's technology with a price, the given one when it is
	 * not {@code null}.
	 * @param part the API
	 * @param price the price it must have, compared as a number; {@code null} for any
	 * @return the preset's price; empty when no preset of its technology has such a price
	 */
	private static Optional<ApiPrice> firstPricedPreset(CostPart part, @Nullable Double price) {
		for (Preset preset : part.presets()) {
			Double its = preset.setting(PER_MILLION).map(CostPart::number).orElse(null);
			// compared as numbers, so -0.0 is 0.0, as Python compares them
			if (preset.technology().equals(part.technology()) && its != null
					&& (price == null || its.doubleValue() == price.doubleValue())) {
				return Optional.of(new FromPreset(preset, its));
			}
		}
		return Optional.empty();
	}

	/**
	 * A price a person set on the part.
	 *
	 * @param perMillion the price of a million calls
	 */
	record SetOnThePart(Double perMillion) implements ApiPrice {

		@Override
		public PriceBasis source() {
			return PriceBasis.SET;
		}

		@Override
		public Optional<String> note(CostPart part) {
			return Optional.empty();
		}

	}

	/**
	 * A technology preset's illustrative price.
	 *
	 * @param preset the preset
	 * @param perMillion its price of a million calls
	 */
	record FromPreset(Preset preset, Double perMillion) implements ApiPrice {

		@Override
		public PriceBasis source() {
			return PriceBasis.PRESET;
		}

		@Override
		public Optional<String> note(CostPart part) {
			return Optional.of(CostNotes.presetPrice(part, this.preset, this.perMillion));
		}

	}

	/** No price: the month leaves the API out. */
	enum Unpriced implements ApiPrice {

		/** The one API without a price. */
		INSTANCE;

		@Override
		public @Nullable Double perMillion() {
			return null;
		}

		@Override
		public @Nullable PriceBasis source() {
			return null;
		}

		@Override
		public Optional<String> note(CostPart part) {
			return Optional.of(CostNotes.unpriced(part));
		}

	}

}
