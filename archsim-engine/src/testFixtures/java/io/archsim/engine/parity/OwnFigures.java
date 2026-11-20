package io.archsim.engine.parity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The figures a list names as the Java's own in a recorded case: figures a departure
 * moves that no rule can derive from the output, such as a latency that a wait it
 * shortens or lengthens runs through. Each reads as the frozen engine's only where the
 * Java's figure is the one listed, so the list pins it: a listed figure that moves again
 * still shows, and so does any figure not listed. An item of a list it names, such as a
 * finding of the design review, is one the Java adds: it is left out only where the
 * Java's item at that place is the one listed, after every figure is read.
 */
public final class OwnFigures {

	private OwnFigures() {
	}

	/**
	 * The Java's output with the figures listed as its own read as the frozen engine's,
	 * and the items listed as its own left out.
	 * @param java the Java's output; it is not changed
	 * @param python the frozen engine's output of the same call
	 * @param listed the Java's own figures and items, each by its JSON Pointer
	 * @return a copy of the Java's output
	 */
	public static JsonNode asRecorded(JsonNode java, JsonNode python, JsonNode listed) {
		JsonNode copy = java.deepCopy();
		List<Item> added = new ArrayList<>();
		for (String pointer : listed.propertyNames()) {
			JsonPointer place = JsonPointer.compile(pointer);
			if (!copy.at(place).equals(listed.get(pointer))) {
				continue;
			}
			switch (copy.at(place.head())) {
				case ObjectNode holder -> holder.set(place.last().getMatchingProperty(), python.at(place));
				case ArrayNode list -> added.add(new Item(list, place.last().getMatchingIndex()));
				default -> {
					// a figure of a value that holds none: there is nothing to read
				}
			}
		}
		// the last first, so that leaving an item out moves none still to leave out
		added.sort(Comparator.comparingInt(Item::index).reversed());
		added.forEach((item) -> item.list().remove(item.index()));
		return copy;
	}

	/**
	 * An item of a list of the Java's output.
	 *
	 * @param list the list
	 * @param index the item's place in it
	 */
	private record Item(ArrayNode list, int index) {

	}

}
