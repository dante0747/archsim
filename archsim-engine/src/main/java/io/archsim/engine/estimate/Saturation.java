package io.archsim.engine.estimate;

import java.util.Collection;
import java.util.List;
import java.util.function.ToDoubleFunction;

import org.jspecify.annotations.Nullable;

import io.archsim.model.component.queue.MessageQueueSpec;

/**
 * Which parts limit a design, read from their rounded estimates as the Python engine
 * reads them. Equal values go to the part estimated first.
 *
 * @param busiest the part that uses most of its slots or CPU; {@code null} when no part
 * has a utilization
 * @param firstLimited the first part to reach a limit (its slots, CPU or a rate limit),
 * which sets the saturation point; {@code null} when no part has a load
 * @param overloaded the parts that cannot keep up, in the order they were estimated
 * @param rps the load the design saturates at: the peak over the first limited part's
 * load; {@code null} without a part whose load is above zero
 */
record Saturation(@Nullable EstimatedPart busiest, @Nullable EstimatedPart firstLimited, List<EstimatedPart> overloaded,
		@Nullable Double rps) {

	/**
	 * Copies the overloaded parts.
	 */
	Saturation {
		overloaded = List.copyOf(overloaded);
	}

	/**
	 * What limits a design.
	 * @param parts the parts' estimates, in the order they were made
	 * @param peak the peak load, in requests per second
	 * @return its limits
	 */
	static Saturation of(Collection<PartEstimate> parts, double peak) {
		List<EstimatedPart> estimated = parts.stream()
			.filter(EstimatedPart.class::isInstance)
			.map(EstimatedPart.class::cast)
			.toList();
		List<EstimatedPart> measured = estimated.stream().filter((part) -> part.utilization() != null).toList();
		List<EstimatedPart> loaded = estimated.stream().filter((part) -> part.load() != null).toList();
		EstimatedPart busiest = measured.isEmpty() ? null
				: firstOfLargest(measured, (part) -> orZero(part.utilization()));
		EstimatedPart first = loaded.isEmpty() ? null : firstOfLargest(loaded, (part) -> orZero(part.load()));
		Double load = (first != null) ? first.load() : null;
		return new Saturation(busiest, first,
				estimated.stream().filter((part) -> part.status() == PartStatus.OVERLOADED).toList(),
				(load != null && load != 0) ? peak / load : null);
	}

	/**
	 * Whether a part on a client's request path is overloaded. A queue whose consumers
	 * fall behind builds a backlog; its producers are not slowed.
	 * @return whether the clients' requests reach a part that cannot keep up
	 */
	boolean pathOverloaded() {
		return this.overloaded.stream().anyMatch((part) -> part.onPath() && !MessageQueueSpec.TYPE.equals(part.type()));
	}

	/**
	 * The item with the largest key, the first of equal ones, as Python's {@code max}.
	 * @param <T> the items' type
	 * @param items the items, at least one
	 * @param key each item's key
	 * @return the first item with the largest key
	 */
	static <T> T firstOfLargest(List<T> items, ToDoubleFunction<T> key) {
		T largest = items.get(0);
		for (T item : items) {
			if (key.applyAsDouble(item) > key.applyAsDouble(largest)) {
				largest = item;
			}
		}
		return largest;
	}

	/**
	 * A share, with an unknown one read as zero, as the Python engine's {@code or 0.0}.
	 * @param share the share; {@code null} when unknown
	 * @return the share, or zero
	 */
	static double orZero(@Nullable Double share) {
		return (share != null) ? share : 0.0;
	}

}
