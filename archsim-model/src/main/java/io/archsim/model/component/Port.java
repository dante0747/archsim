package io.archsim.model.component;

import io.archsim.model.document.RequestType;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A named connection point of a component type: which way requests go through it, which request
 * types it takes, and how many links it needs and allows.
 *
 * @param name the name links refer to ({@code in}, {@code out}, {@code miss})
 * @param direction whether it receives or sends requests
 * @param label the name people see
 * @param description what goes through it
 * @param requestTypes the request types it accepts or emits
 * @param minEdges the fewest links it needs
 * @param maxEdges the most links it allows; {@code null}: no limit
 */
public record Port(
        String name,
        PortDirection direction,
        String label,
        String description,
        List<RequestType> requestTypes,
        int minEdges,
        @Nullable Integer maxEdges) {

    public Port {
        Objects.requireNonNull(name, "name");
        requestTypes = List.copyOf(requestTypes);
    }

    /** A port that receives reads and writes, with no limit on its links. */
    public static Port in(String name, String label, String description) {
        return new Port(name, PortDirection.IN, label, description, List.of(RequestType.values()), 0, null);
    }

    /** A port that sends reads and writes, with no limit on its links. */
    public static Port out(String name, String label, String description) {
        return new Port(name, PortDirection.OUT, label, description, List.of(RequestType.values()), 0, null);
    }

    public Port withMinEdges(int min) {
        return new Port(name, direction, label, description, requestTypes, min, maxEdges);
    }

    public Port withMaxEdges(int max) {
        return new Port(name, direction, label, description, requestTypes, minEdges, max);
    }

    public Port carrying(RequestType... types) {
        return new Port(name, direction, label, description, List.of(types), minEdges, maxEdges);
    }

    public boolean isIn() {
        return direction == PortDirection.IN;
    }

    public boolean isOut() {
        return direction == PortDirection.OUT;
    }
}
