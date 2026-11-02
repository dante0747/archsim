package io.archsim.model.component;

import io.archsim.model.codec.RecordCodec;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.document.RequestType;
import io.archsim.model.validation.Issue;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.node.ObjectNode;

/**
 * What ArchSim knows about one component type without simulating it: its name, its ports, its
 * config, how request types flow through it, and its own validation rules. The simulator adds
 * how it behaves (stage 4).
 *
 * <p>A new component type extends this class and is added to a {@link ComponentRegistry}.
 *
 * @param <C> the type's config record
 */
public abstract class ComponentSpec<C extends Record> {

    private final String type;
    private final String displayName;
    private final Category category;
    private final Class<C> configType;
    private final List<Port> ports;

    protected ComponentSpec(String type, String displayName, Category category, Class<C> configType, List<Port> ports) {
        this.type = Objects.requireNonNull(type, "type");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.category = Objects.requireNonNull(category, "category");
        this.configType = Objects.requireNonNull(configType, "configType");
        this.ports = List.copyOf(ports);
        if (ports.stream().map(Port::name).distinct().count() != ports.size()) {
            throw new IllegalArgumentException("component '%s' has duplicate port names".formatted(type));
        }
    }

    /** The type's name in documents, such as {@code service}. */
    public final String type() {
        return type;
    }

    /** The name people see, such as {@code Service / API Servers}. */
    public final String displayName() {
        return displayName;
    }

    public final Category category() {
        return category;
    }

    public final Class<C> configType() {
        return configType;
    }

    public final List<Port> ports() {
        return ports;
    }

    /** Whether the type generates the workload (a client group). */
    public boolean isTrafficSource() {
        return false;
    }

    public final Optional<Port> port(String name) {
        return ports.stream().filter(p -> p.name().equals(name)).findFirst();
    }

    public final List<Port> inPorts() {
        return ports.stream().filter(Port::isIn).toList();
    }

    public final List<Port> outPorts() {
        return ports.stream().filter(Port::isOut).toList();
    }

    /**
     * The node's config, its defaults filled in.
     *
     * @throws io.archsim.model.codec.BindingException with every error, when it does not fit
     */
    public final C parseConfig(ObjectNode config) {
        return RecordCodec.read(config, configType);
    }

    /**
     * The request types that may leave through {@code port}, given those that reach the node.
     * By default what comes in goes out.
     */
    public Set<RequestType> emittedTypes(String port, Set<RequestType> incoming, C config, GraphView view) {
        return incoming;
    }

    /** The type's own checks of a node, beyond its links' ports and counts. */
    public List<Issue> validate(NodeSpec node, C config, GraphView view) {
        return List.of();
    }

    @Override
    public String toString() {
        return type;
    }
}
