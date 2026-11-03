package io.archsim.model.component;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.JsonEnum;

/**
 * What a technology does by default, for the settings a config leaves on {@code auto} (or
 * empty): PostgreSQL takes row locks, Redis runs commands on one thread, Envoy times out
 * after 15 s. A value is under the config field's JSON name; a technology that lacks one,
 * or says it has none ({@link #withoutDefault}), leaves the field's own fallback.
 *
 * @param tier how well ArchSim knows the technology
 * @param values the defaults, by the config field's JSON name: strings for choices,
 * numbers, distributions; {@code null} for a field the technology has no default for
 */
public record TechnologyProfile(Tier tier, Map<String, @Nullable Object> values) {

	/**
	 * Copies the defaults, keeping their order.
	 */
	public TechnologyProfile {
		Objects.requireNonNull(tier, "tier");
		values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
	}

	/**
	 * A profile of a core technology, to fill in with {@link #with}.
	 * @return an empty core profile
	 */
	public static TechnologyProfile core() {
		return new TechnologyProfile(Tier.CORE, Map.of());
	}

	/**
	 * A profile of an experimental technology, to fill in with {@link #with}.
	 * @return an empty experimental profile
	 */
	public static TechnologyProfile experimental() {
		return new TechnologyProfile(Tier.EXPERIMENTAL, Map.of());
	}

	/**
	 * This profile with a default for {@code field}, written as the field's own values
	 * are: a string for a choice, a number, or a distribution such as a log-normal.
	 * @param field the config field's JSON name
	 * @param value the technology's default for it
	 * @return a profile with the default added
	 */
	public TechnologyProfile with(String field, Object value) {
		return withValue(field, Objects.requireNonNull(value, field));
	}

	/**
	 * This profile saying the technology has no default for {@code field}, which then
	 * takes its own fallback. The editor shows that as the technology's default too.
	 * @param field the config field's JSON name
	 * @return a profile that names the field without a default
	 */
	public TechnologyProfile withoutDefault(String field) {
		return withValue(field, null);
	}

	/**
	 * The choice in effect: {@code explicit}, unless it is {@code auto}; then the
	 * technology's default, or {@code fallback} when it has none.
	 * @param <E> the setting's choices
	 * @param field the config field's JSON name
	 * @param explicit the config's setting
	 * @param auto the choice that defers to the technology
	 * @param fallback the choice when the technology has no default
	 * @return the choice in effect
	 */
	public <E extends Enum<E> & JsonEnum> E choice(String field, E explicit, E auto, E fallback) {
		if (explicit != auto) {
			return explicit;
		}
		@Nullable Object value = this.values.get(field);
		if (value == null) {
			return fallback;
		}
		return Arrays.stream(explicit.getDeclaringClass().getEnumConstants())
			.filter((constant) -> constant.json().equals(value))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("%s is not a %s".formatted(value, field)));
	}

	/**
	 * The number in effect: {@code explicit} when set, else the technology's default, if
	 * any.
	 * @param field the config field's JSON name
	 * @param explicit the config's setting; {@code null} when it is not set
	 * @return the number in effect, if there is one
	 */
	public Optional<Double> number(String field, @Nullable Double explicit) {
		if (explicit != null) {
			return Optional.of(explicit);
		}
		return Optional.ofNullable(this.values.get(field)).map((value) -> ((Number) value).doubleValue());
	}

	private TechnologyProfile withValue(String field, @Nullable Object value) {
		Map<String, @Nullable Object> more = new LinkedHashMap<>(this.values);
		more.put(field, value);
		return new TechnologyProfile(this.tier, more);
	}

}
