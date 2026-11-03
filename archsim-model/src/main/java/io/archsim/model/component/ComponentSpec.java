package io.archsim.model.component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.RecordCodec;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.document.RequestType;
import io.archsim.model.issue.Issue;

/**
 * What ArchSim knows about one component type without simulating it: its name, its ports,
 * its config, how request types flow through it, and its own validation rules. The
 * simulator adds how it behaves (stage 4).
 *
 * <p>
 * A new component type extends this class and is added to a {@link ComponentRegistry}.
 *
 * @param <C> the type's config record
 */
public abstract class ComponentSpec<C extends Record> {

	private final ComponentIdentity identity;

	private final Class<C> configType;

	private final List<Port> ports;

	/**
	 * Creates the spec of a component type.
	 * @param identity the type's names and category
	 * @param configType the type's config record
	 * @param ports the type's ports, each with its own name
	 */
	protected ComponentSpec(ComponentIdentity identity, Class<C> configType, List<Port> ports) {
		this.identity = Objects.requireNonNull(identity, "identity");
		this.configType = Objects.requireNonNull(configType, "configType");
		this.ports = List.copyOf(ports);
		if (ports.stream().map(Port::name).distinct().count() != ports.size()) {
			throw new IllegalArgumentException("component '%s' has duplicate port names".formatted(identity.type()));
		}
	}

	/**
	 * The type's name in documents, such as {@code service}.
	 * @return the type name
	 */
	public final String type() {
		return this.identity.type();
	}

	/**
	 * The name people see, such as {@code Service / API Servers}.
	 * @return the display name
	 */
	public final String displayName() {
		return this.identity.displayName();
	}

	public final Category category() {
		return this.identity.category();
	}

	public final Class<C> configType() {
		return this.configType;
	}

	public final List<Port> ports() {
		return this.ports;
	}

	/**
	 * Whether the type generates the workload (a client group).
	 * @return {@code true} for a client group
	 */
	public boolean isTrafficSource() {
		return false;
	}

	/**
	 * The profile of each of the type's technologies, by JSON name, in the order the
	 * technologies are declared; empty for a type without a {@code technology} setting.
	 * @return the technologies' profiles
	 */
	public Map<String, TechnologyProfile> technologies() {
		return Map.of();
	}

	public final Optional<Port> port(String name) {
		return this.ports.stream().filter((port) -> port.name().equals(name)).findFirst();
	}

	public final List<Port> inPorts() {
		return this.ports.stream().filter(Port::isIn).toList();
	}

	public final List<Port> outPorts() {
		return this.ports.stream().filter(Port::isOut).toList();
	}

	/**
	 * The node's config, its defaults filled in.
	 * @param config the node's config as it is in the document
	 * @return the config record
	 * @throws io.archsim.model.codec.BindingException with every error, when it does not
	 * fit
	 */
	public final C parseConfig(ObjectNode config) {
		return RecordCodec.read(config, this.configType);
	}

	/**
	 * The request types that may leave through {@code port}, given those that reach the
	 * node. By default what comes in goes out.
	 * @param port the name of one of the type's out ports
	 * @param incoming the request types that reach the node
	 * @param config the node's config
	 * @param view the design the node is in
	 * @return the request types that leave through the port
	 */
	public Set<RequestType> emittedTypes(String port, Set<RequestType> incoming, C config, GraphView view) {
		return incoming;
	}

	/**
	 * The type's own checks of a node, beyond its links' ports and counts.
	 * @param node the node to check
	 * @param config the node's config
	 * @param view the design the node is in
	 * @return the problems found, in the order they are reported
	 */
	public List<Issue> validate(NodeSpec node, C config, GraphView view) {
		return List.of();
	}

	@Override
	public String toString() {
		return this.identity.type();
	}

}
