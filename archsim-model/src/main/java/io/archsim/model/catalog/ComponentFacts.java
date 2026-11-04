package io.archsim.model.catalog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.ComponentSpec;

/**
 * What the catalog knows about each type of a registry, as typed facts: the same
 * specifications and presentation data {@link ComponentCatalog} writes for the editor,
 * for code that reads them (the analysis classifies each input with them).
 */
public final class ComponentFacts {

	private final Map<String, TypeFacts> types;

	/**
	 * The facts of every type in a registry.
	 * @param registry the component types
	 * @throws IllegalStateException when a type has no presentation data on the classpath
	 */
	public ComponentFacts(ComponentRegistry registry) {
		Map<String, TypeFacts> byType = new LinkedHashMap<>();
		for (ComponentSpec<?> spec : registry.specs()) {
			byType.put(spec.type(), new TypeFacts(spec, Presentation.of(spec.type())));
		}
		this.types = Collections.unmodifiableMap(byType);
	}

	/**
	 * The facts of one type.
	 * @param type the type's name
	 * @return its facts; empty for a type the registry does not have
	 */
	public Optional<TypeFacts> type(String type) {
		return Optional.ofNullable(this.types.get(type));
	}

}
