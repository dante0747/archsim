package io.archsim.model.codec;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Where a value sits in a JSON input: the object keys and array indexes that lead to it from the
 * root. It is pydantic's {@code loc}, so an error points at the same place in both
 * implementations.
 *
 * @param parts the keys ({@link String}) and indexes ({@link Integer}) from the root
 */
public record Location(List<Object> parts) {

    /** The root of the input. */
    public static final Location ROOT = new Location(List.of());

    public Location {
        parts = List.copyOf(parts);
        for (Object part : parts) {
            if (!(part instanceof String) && !(part instanceof Integer)) {
                throw new IllegalArgumentException("a location part is a key or an index: " + part);
            }
        }
    }

    /** The location of the value under {@code key} of the object here. */
    public Location child(String key) {
        return append(key);
    }

    /** The location of the item at {@code index} of the array here. */
    public Location child(int index) {
        return append(index);
    }

    public boolean isRoot() {
        return parts.isEmpty();
    }

    private Location append(Object part) {
        List<Object> next = new ArrayList<>(parts);
        next.add(part);
        return new Location(next);
    }

    /** The parts joined by dots, as Python's {@code ".".join(str(p) for p in loc)}. */
    @Override
    public String toString() {
        return parts.stream().map(String::valueOf).collect(Collectors.joining("."));
    }
}
