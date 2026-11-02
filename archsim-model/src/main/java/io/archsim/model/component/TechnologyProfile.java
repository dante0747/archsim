package io.archsim.model.component;

import io.archsim.model.codec.JsonEnum;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * What a technology does by default, for the settings a config leaves on {@code auto} (or
 * empty): PostgreSQL takes row locks, Redis runs commands on one thread, Envoy times out
 * after 15 s. A value is under the config field's JSON name; a technology that lacks one
 * leaves the field's own fallback.
 *
 * @param tier how well ArchSim knows the technology
 * @param values the defaults, by the config field's JSON name: strings for choices,
 * numbers
 */
public record TechnologyProfile(Tier tier, Map<String, Object> values) {

	public TechnologyProfile {
		Objects.requireNonNull(tier, "tier");
		values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
	}

	/** A profile of a core technology, to fill in with {@link #with}. */
	public static TechnologyProfile core() {
		return new TechnologyProfile(Tier.CORE, Map.of());
	}

	/** A profile of an experimental technology, to fill in with {@link #with}. */
	public static TechnologyProfile experimental() {
		return new TechnologyProfile(Tier.EXPERIMENTAL, Map.of());
	}

	/**
	 * This profile with a default for {@code field}: a string for a choice, a number
	 * otherwise.
	 */
	public TechnologyProfile with(String field, Object value) {
		Map<String, Object> more = new LinkedHashMap<>(values);
		more.put(field, value);
		return new TechnologyProfile(tier, more);
	}

	/**
	 * The choice in effect: {@code explicit}, unless it is {@code auto}; then the
	 * technology's default, or {@code fallback} when it has none.
	 */
	public <E extends Enum<E> & JsonEnum> E choice(String field, E explicit, E auto, E fallback) {
		if (explicit != auto) {
			return explicit;
		}
		Object value = values.get(field);
		if (value == null) {
			return fallback;
		}
		return Arrays.stream(explicit.getDeclaringClass().getEnumConstants())
			.filter(c -> c.json().equals(value))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("%s is not a %s".formatted(value, field)));
	}

	/**
	 * The number in effect: {@code explicit} when set, else the technology's default, if
	 * any.
	 */
	public Optional<Double> number(String field, @Nullable Double explicit) {
		if (explicit != null) {
			return Optional.of(explicit);
		}
		return Optional.ofNullable(values.get(field)).map(v -> ((Number) v).doubleValue());
	}
}
