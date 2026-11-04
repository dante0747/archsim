package io.archsim.engine.sizing;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.OmitWhenDefault;

/**
 * One part's sizing summary.
 *
 * @param specs each spec it is simulated with, by JSON name, and where it came from
 * @param auto its auto size of each spec; {@code null} where it has none
 * @param peakRps the requests per second it carries at the peak, to three decimals
 * @param why how each auto size was worked out
 * @param pricePerHour the price per instance-hour of the size used; {@code null} for a
 * part billed per use
 * @param sku the snapshot's instance type the size is priced at; {@code null} for none
 * @param need what it needs, to four decimals; left out when it needs nothing
 * @param needText each spec's need against what its instance has; left out when there is
 * none
 * @param smallest its CPU need is below the smallest size it can get; left out when not
 * @param skipped why it is not sized; left out when it is
 */
public record PartSummary(Map<String, SpecValue> specs, Map<String, @Nullable Double> auto, double peakRps,
		Map<String, String> why, @Nullable Double pricePerHour, @Nullable String sku,
		@OmitWhenDefault @Nullable Map<String, Double> need, @OmitWhenDefault @Nullable Map<String, String> needText,
		@Default("false") @OmitWhenDefault boolean smallest, @OmitWhenDefault @Nullable String skipped) {

	/**
	 * Copies the maps, so the record does not change.
	 */
	public PartSummary {
		specs = Collections.unmodifiableMap(new LinkedHashMap<>(specs));
		auto = Collections.unmodifiableMap(new LinkedHashMap<>(auto));
		why = Collections.unmodifiableMap(new LinkedHashMap<>(why));
		need = (need != null) ? Collections.unmodifiableMap(new LinkedHashMap<>(need)) : null;
		needText = (needText != null) ? Collections.unmodifiableMap(new LinkedHashMap<>(needText)) : null;
	}

}
