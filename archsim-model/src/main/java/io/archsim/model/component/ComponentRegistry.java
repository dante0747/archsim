package io.archsim.model.component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The component types a design may use, by type name, in the order they were added (which
 * is the order messages list them in).
 * {@link io.archsim.model.component.standard.StandardComponents} has ArchSim's own; a
 * registry may add more.
 */
public final class ComponentRegistry {

	private final Map<String, ComponentSpec<?>> specs = new LinkedHashMap<>();

	public ComponentRegistry(List<? extends ComponentSpec<?>> specs) {
		for (ComponentSpec<?> spec : specs) {
			if (this.specs.putIfAbsent(spec.type(), spec) != null) {
				throw new IllegalArgumentException("component type '%s' is already registered".formatted(spec.type()));
			}
		}
	}

	/** The type named {@code type}, if it is registered. */
	public Optional<ComponentSpec<?>> find(String type) {
		return Optional.ofNullable(specs.get(type));
	}

	public boolean contains(String type) {
		return specs.containsKey(type);
	}

	/** Every registered type name, in order. */
	public List<String> types() {
		return List.copyOf(specs.keySet());
	}

	public List<ComponentSpec<?>> specs() {
		return List.copyOf(specs.values());
	}

}
