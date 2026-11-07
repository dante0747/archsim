package io.archsim.model.component;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.archsim.model.codec.JsonEnum;

/**
 * The profile of every technology of one component type. Built only when each technology
 * has one, so a lookup always finds it:
 *
 * <pre>{@code
 * TechnologyProfiles.builder(CacheTechnology.class)
 *     .with(CacheTechnology.REDIS, TechnologyProfile.core().with("singleThreaded", "on"))
 *     ...
 *     .build();
 * }</pre>
 *
 * @param <E> the component type's technologies
 */
public final class TechnologyProfiles<E extends Enum<E> & JsonEnum> {

	private final Map<E, TechnologyProfile> profiles;

	private final Map<String, TechnologyProfile> byName;

	private TechnologyProfiles(Map<E, TechnologyProfile> profiles) {
		this.profiles = profiles;
		Map<String, TechnologyProfile> named = new LinkedHashMap<>();
		profiles.forEach((technology, profile) -> named.put(technology.json(), profile));
		this.byName = Collections.unmodifiableMap(named);
	}

	/**
	 * Starts the profiles of a component type's technologies.
	 * @param <E> the technologies
	 * @param technologies the enum of the technologies
	 * @return a builder that takes one profile per technology
	 */
	public static <E extends Enum<E> & JsonEnum> Builder<E> builder(Class<E> technologies) {
		return new Builder<>(technologies);
	}

	/**
	 * The profile of {@code technology}.
	 * @param technology the technology whose profile to return
	 * @return its profile
	 */
	public TechnologyProfile of(E technology) {
		return Objects.requireNonNull(this.profiles.get(technology), technology::name);
	}

	/**
	 * Every profile by its technology's JSON name, in the order they were added: the
	 * Python engine's, which the catalog and the assumptions page list them in.
	 * @return the profiles, keyed by JSON name
	 */
	public Map<String, TechnologyProfile> byName() {
		return this.byName;
	}

	/**
	 * Collects one profile per technology.
	 *
	 * @param <E> the technologies
	 */
	public static final class Builder<E extends Enum<E> & JsonEnum> {

		private final Class<E> technologies;

		private final Map<E, TechnologyProfile> profiles;

		private Builder(Class<E> technologies) {
			this.technologies = technologies;
			this.profiles = new LinkedHashMap<>();
		}

		/**
		 * Adds the profile of one technology.
		 * @param technology the technology
		 * @param profile what it does by default
		 * @return this builder
		 * @throws IllegalArgumentException when the technology already has a profile
		 */
		public Builder<E> with(E technology, TechnologyProfile profile) {
			if (this.profiles.putIfAbsent(technology, Objects.requireNonNull(profile, "profile")) != null) {
				throw new IllegalArgumentException("technology given twice: " + technology);
			}
			return this;
		}

		/**
		 * The profiles.
		 * @return every technology's profile
		 * @throws IllegalArgumentException when a technology has none
		 */
		public TechnologyProfiles<E> build() {
			List<E> missing = Arrays.stream(this.technologies.getEnumConstants())
				.filter((technology) -> !this.profiles.containsKey(technology))
				.toList();
			if (!missing.isEmpty()) {
				throw new IllegalArgumentException("technologies without a profile: " + missing);
			}
			return new TechnologyProfiles<>(Collections.unmodifiableMap(new LinkedHashMap<>(this.profiles)));
		}

	}

}
