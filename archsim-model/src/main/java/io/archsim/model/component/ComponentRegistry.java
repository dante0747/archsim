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

	/**
	 * Registers component types, in order.
	 * @param specs the types to register
	 * @throws IllegalArgumentException when two of them have the same type name
	 */
	public ComponentRegistry(List<? extends ComponentSpec<?>> specs) {
		for (ComponentSpec<?> spec : specs) {
			if (this.specs.putIfAbsent(spec.type(), spec) != null) {
				throw new IllegalArgumentException("component type '%s' is already registered".formatted(spec.type()));
			}
		}
	}

	/**
	 * The type named {@code type}, if it is registered.
	 * @param type the type's name in documents
	 * @return the type's spec, if it is registered
	 */
	public Optional<ComponentSpec<?>> find(String type) {
		return Optional.ofNullable(this.specs.get(type));
	}

	public boolean contains(String type) {
		return this.specs.containsKey(type);
	}

	/**
	 * Every registered type name, in order.
	 * @return the type names
	 */
	public List<String> types() {
		return List.copyOf(this.specs.keySet());
	}

	public List<ComponentSpec<?>> specs() {
		return List.copyOf(this.specs.values());
	}

}
