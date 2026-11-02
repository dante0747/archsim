package io.archsim.model.component;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The profile of every technology of one component type. Built only when each technology has one,
 * so a lookup always finds it.
 *
 * @param <E> the component type's technologies
 */
public final class TechnologyProfiles<E extends Enum<E>> {

    private final Map<E, TechnologyProfile> profiles;

    public TechnologyProfiles(Class<E> technologies, Map<E, TechnologyProfile> profiles) {
        this.profiles = new EnumMap<>(profiles);
        List<E> missing = Arrays.stream(technologies.getEnumConstants())
                .filter(t -> !profiles.containsKey(t))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("technologies without a profile: " + missing);
        }
    }

    /** The profile of {@code technology}. */
    public TechnologyProfile of(E technology) {
        return Objects.requireNonNull(profiles.get(technology), technology::name);
    }
}
