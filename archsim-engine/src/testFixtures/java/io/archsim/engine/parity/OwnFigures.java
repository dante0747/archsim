package io.archsim.engine.parity;

import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The figures a list names as the Java's own in a recorded case: figures a departure
 * moves that no rule can derive from the output, such as a latency that a wait it
 * shortens or lengthens runs through. Each reads as the frozen engine's only where the
 * Java's figure is the one listed, so the list pins it: a listed figure that moves again
 * still shows, and so does any figure not listed.
 */
public final class OwnFigures {

	private OwnFigures() {
	}

	/**
	 * The Java's output with the figures listed as its own read as the frozen engine's.
	 * @param java the Java's output; it is not changed
	 * @param python the frozen engine's output of the same call
	 * @param listed the Java's own figures, each by its JSON Pointer
	 * @return a copy of the Java's output
	 */
	public static JsonNode asRecorded(JsonNode java, JsonNode python, JsonNode listed) {
		JsonNode copy = java.deepCopy();
		for (String pointer : listed.propertyNames()) {
			JsonPointer place = JsonPointer.compile(pointer);
			boolean own = copy.at(place).equals(listed.get(pointer));
			if (own && copy.at(place.head()) instanceof ObjectNode holder) {
				holder.set(place.last().getMatchingProperty(), python.at(place));
			}
		}
		return copy;
	}

}
