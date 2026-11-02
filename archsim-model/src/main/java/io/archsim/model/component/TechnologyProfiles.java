package io.archsim.model.component;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The profile of every technology of one component type. Built only when each technology
 * has one, so a lookup always finds it.
 *
 * @param <E> the component type's technologies
 */
public final class TechnologyProfiles<E extends Enum<E>> {

	private final Map<E, TechnologyProfile> profiles;

	/**
	 * Holds the profile of every technology.
	 * @param technologies the component type's technologies
	 * @param profiles the profile of each technology
	 * @throws IllegalArgumentException when a technology has no profile
	 */
	public TechnologyProfiles(Class<E> technologies, Map<E, TechnologyProfile> profiles) {
		this.profiles = new EnumMap<>(profiles);
		List<E> missing = Arrays.stream(technologies.getEnumConstants())
			.filter((technology) -> !profiles.containsKey(technology))
			.toList();
		if (!missing.isEmpty()) {
			throw new IllegalArgumentException("technologies without a profile: " + missing);
		}
	}

	/**
	 * The profile of {@code technology}.
	 * @param technology the technology whose profile to return
	 * @return its profile
	 */
	public TechnologyProfile of(E technology) {
		return Objects.requireNonNull(this.profiles.get(technology), technology::name);
	}

}
