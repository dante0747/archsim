package io.archsim.engine.reporting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.SequencedMap;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.runtime.NodeMetrics;
import io.archsim.engine.runtime.RuntimeComponent;

/**
 * A part's capacity: the lowest of the throughput at which its slots fill (when a request
 * holds one as long as it did on average) and the ceilings its resource specs set (CPU
 * cores, memory), and what it would be were a request to hold a slot for its own work
 * alone. At saturation the slots' ceiling reflects the time spent waiting for a core or
 * memory, so a resource ceiling within 3% of it is named as the limit instead. Only a
 * part whose utilization is its busy slots has a slots' ceiling: not a load balancer,
 * whose utilization is the requests in flight over its connections.
 */
final class Capacity {

	private static final String SLOTS = "slots";

	private static final String CPU = "cpu";

	/** A resource ceiling within this share above the slots' is named as the limit. */
	private static final double NEAR = 1.03;

	private final SequencedMap<String, Double> limits;

	private final @Nullable String limitedBy;

	private final @Nullable Double capacity;

	private final OptionalDouble serviceOnly;

	private Capacity(SequencedMap<String, Double> limits, SequencedMap<String, Double> ceilings,
			OptionalDouble serviceOnly) {
		this.limits = limits;
		this.limitedBy = limitedBy(limits, ceilings);
		this.capacity = (this.limitedBy != null) ? ceilings.get(this.limitedBy) : null;
		this.serviceOnly = serviceOnly;
	}

	static Capacity of(RuntimeComponent component) {
		NodeMetrics metrics = component.metrics();
		OptionalDouble slots = slots(metrics);
		OptionalDouble holdMs = metrics.hold().windowMean();
		OptionalDouble serviceMs = metrics.service().windowMean();
		SequencedMap<String, Double> limits = component.resourceCapacity(holdMs, serviceMs);
		SequencedMap<String, Double> ceilings = new LinkedHashMap<>(limits);
		boolean poolBased = poolBased(component);
		if (poolBased && slots.isPresent() && holdMs.orElse(0.0) > 0) {
			ceilings.put(SLOTS, component.slotCapacity(slots.getAsDouble(), holdMs.getAsDouble(), serviceMs));
		}
		List<Double> serviceOnly = new ArrayList<>();
		if (limits.containsKey(CPU)) {
			serviceOnly.add(limits.get(CPU));
		}
		if (poolBased && slots.isPresent() && serviceMs.orElse(0.0) > 0) {
			serviceOnly.add(component.slotCapacity(slots.getAsDouble(), serviceMs.getAsDouble(), serviceMs));
		}
		return new Capacity(limits, ceilings, serviceOnly.stream().mapToDouble(Double::doubleValue).reduce(Math::min));
	}

	/**
	 * Whether a part's utilization is its busy slots, so its slots set a ceiling and have
	 * their formulas.
	 * @param component the part
	 * @return {@code true} unless the part measures its occupancy otherwise
	 */
	static boolean poolBased(RuntimeComponent component) {
		return component.occupancy() == component.metrics().busy();
	}

	/**
	 * A part's slots, as the capacity and the utilization count them.
	 * @param metrics the part's metrics
	 * @return its slots; empty when it has none, or none at all
	 */
	static OptionalDouble slots(NodeMetrics metrics) {
		return (metrics.slots().isPresent() && metrics.slots().getAsInt() != 0)
				? OptionalDouble.of(metrics.slots().getAsInt()) : OptionalDouble.empty();
	}

	void write(ObjectNode summary) {
		summary.set("capacityRps", rounded(this.capacity));
		summary.put("capacityLimit", this.limitedBy);
		summary.set("cpuCapacityRps", rounded(this.limits.get(CPU)));
		summary.set("serviceOnlyCapacityRps", Figures.rounded(this.serviceOnly));
	}

	/**
	 * What limits a part: the lowest ceiling, the first of equals; but when its slots' is
	 * lowest, the lowest of its resources' ceilings within 3% above it, as the time spent
	 * waiting for a core or memory fills the slots at saturation.
	 * @param limits the ceilings its resource specs set, in the part's order
	 * @param ceilings those and its slots', if it has slots
	 * @return the name of the ceiling that limits it; {@code null} without ceilings
	 */
	static @Nullable String limitedBy(SequencedMap<String, Double> limits, SequencedMap<String, Double> ceilings) {
		String lowest = lowest(ceilings);
		if (!SLOTS.equals(lowest)) {
			return lowest;
		}
		double slotCeiling = Objects.requireNonNull(ceilings.get(SLOTS));
		SequencedMap<String, Double> near = new LinkedHashMap<>();
		limits.forEach((name, ceiling) -> {
			if (ceiling <= slotCeiling * NEAR) {
				near.put(name, ceiling);
			}
		});
		return near.isEmpty() ? lowest : lowest(near);
	}

	private static @Nullable String lowest(SequencedMap<String, Double> ceilings) {
		Map.Entry<String, Double> lowest = null;
		for (Map.Entry<String, Double> ceiling : ceilings.entrySet()) {
			if (lowest == null || ceiling.getValue() < lowest.getValue()) {
				lowest = ceiling;
			}
		}
		return (lowest != null) ? lowest.getKey() : null;
	}

	private static JsonNode rounded(@Nullable Double value) {
		return (value != null) ? Figures.rounded(value) : Figures.NODES.nullNode();
	}

}
