package io.archsim.engine.imports;

import java.util.Optional;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.ComponentSpec;

/**
 * The part an {@code ExternalName} Service becomes: the type and technology an ArchSim
 * export states, else a managed service its note or name names, else a third-party API.
 *
 * @param type the part type
 * @param technology its technology; {@code null} for none
 * @param described what it is, for the report
 */
record ExternalPart(String type, @Nullable String technology, String described) {

	/**
	 * The part a Service becomes, its annotations read in the order Python reads them.
	 * @param registry the component types
	 * @param annotations the Service's annotations
	 * @param name its name, as written
	 * @return the part
	 * @throws ImportShapeException when a name that is not text is searched for a managed
	 * technology
	 */
	static ExternalPart of(ComponentRegistry registry, ObjectNode annotations, JsonNode name) {
		String note = PythonValues.strOrEmpty(annotations.path("archsim.dev/note"));
		Optional<String> managed = ManagedTechnologies.inOrder()
			.stream()
			.filter((technology) -> note.contains(technology.replace('_', ' ')) || PythonValues.isIn(technology, name))
			.findFirst();
		String statedType = PythonValues.strOrEmpty(annotations.path("archsim.dev/type"));
		ExportLabels labels = new ExportLabels(PythonMappings.texts(annotations));
		Optional<ComponentSpec<?>> stated = registry.find(statedType);
		if (stated.isPresent() && labels.statesTechnology()) {
			return new ExternalPart(statedType, labels.technology().orElse(null), stated.get().displayName());
		}
		if (note.contains("third-party") || managed.isEmpty()) {
			return new ExternalPart("external_api", null, "third-party API");
		}
		return new ExternalPart(ManagedTechnologies.typeOf(managed.get()).orElseThrow(), managed.get(),
				"managed " + managed.get());
	}

}
