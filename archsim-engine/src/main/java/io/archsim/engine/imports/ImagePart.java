package io.archsim.engine.imports;

import java.util.List;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.imports.ImageKind.Use;
import io.archsim.model.component.ComponentRegistry;

/**
 * The part a container becomes: the type and technology its image says, or the technology
 * an ArchSim export states for an infrastructure image (which picks the type its image
 * serves: a Redis image runs a cache or Redis Streams).
 *
 * @param image what the image is; {@code null} for a container built from a context
 * @param type the part type
 * @param technology the technology; {@code null} for none (an application, or one an
 * export states as generic)
 * @param displayName the type's name, with its technology, for the report
 */
record ImagePart(@Nullable ImageKind image, String type, @Nullable String technology, String displayName) {

	/** The types a technology an export states may belong to, in the order looked at. */
	private static final List<String> TYPES_BY_TECHNOLOGY = List.of("database", "cache", "message_queue",
			"load_balancer");

	/**
	 * The part a container becomes.
	 * @param registry the component types
	 * @param image its image reference; empty for one built from a context
	 * @param labels its labels or annotations, which an export states its technology in
	 * @return the part
	 */
	static ImagePart of(ComponentRegistry registry, String image, ExportLabels labels) {
		ImageKind kind = image.isEmpty() ? null : Images.classify(image);
		String type = (kind != null) ? kind.type() : "service";
		String technology = (kind != null) ? kind.technology() : null;
		if (labels.statesTechnology() && kind != null && kind.use() == Use.INFRASTRUCTURE) {
			technology = labels.technology().orElse(null);
			type = typeFor(registry, type, technology);
		}
		String displayName = registry.find(type).orElseThrow().displayName()
				+ ((technology != null) ? " (" + technology + ")" : "");
		return new ImagePart(kind, type, technology, displayName);
	}

	/**
	 * The part type a stated technology belongs to: its image's type when it serves it,
	 * else the first type with a profile of it.
	 * @param registry the component types
	 * @param type the type the image says
	 * @param technology the technology stated; {@code null} for generic
	 * @return the type
	 */
	private static String typeFor(ComponentRegistry registry, String type, @Nullable String technology) {
		if (technology == null
				|| registry.find(type).map((spec) -> spec.technologies().containsKey(technology)).orElse(true)) {
			return type;
		}
		return TYPES_BY_TECHNOLOGY.stream()
			.filter((other) -> registry.find(other)
				.map((spec) -> spec.technologies().containsKey(technology))
				.orElse(false))
			.findFirst()
			.orElse(type);
	}

}
