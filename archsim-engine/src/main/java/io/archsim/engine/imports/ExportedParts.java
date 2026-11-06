package io.archsim.engine.imports;

import java.util.List;

import tools.jackson.databind.node.ObjectNode;

/**
 * What an import says of the parts nothing deploys that an ArchSim export carries.
 */
final class ExportedParts {

	private ExportedParts() {
	}

	/**
	 * The parts' ids, for the report.
	 * @param parts the parts
	 * @return their ids, comma separated
	 */
	static String ids(List<ObjectNode> parts) {
		return String.join(", ", parts.stream().map((part) -> part.path("id").stringValue()).toList());
	}

	/**
	 * Whether the parts include one of a type.
	 * @param parts the parts
	 * @param type the type
	 * @return {@code true} when one is of the type
	 */
	static boolean has(List<ObjectNode> parts, String type) {
		return parts.stream().anyMatch((part) -> type.equals(part.path("type").stringValue()));
	}

}
