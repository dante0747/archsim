package io.archsim.engine.reporting;

import java.util.OptionalDouble;

import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.runtime.NodeMetrics;

/**
 * A part's capacity: the throughput at which its slots fill when a request holds one as
 * long as it did on average, and what it would be were a request to hold a slot for its
 * own work alone.
 */
final class Capacity {

	private static final String SLOTS = "slots";

	private final OptionalDouble slotCeiling;

	private final OptionalDouble serviceOnly;

	private Capacity(OptionalDouble slotCeiling, OptionalDouble serviceOnly) {
		this.slotCeiling = slotCeiling;
		this.serviceOnly = serviceOnly;
	}

	static Capacity of(NodeMetrics metrics) {
		OptionalDouble slots = slots(metrics);
		if (slots.isEmpty()) {
			return new Capacity(OptionalDouble.empty(), OptionalDouble.empty());
		}
		return new Capacity(throughput(slots.getAsDouble(), metrics.hold().windowMean()),
				throughput(slots.getAsDouble(), metrics.service().windowMean()));
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
		summary.set("capacityRps", Figures.rounded(this.slotCeiling));
		if (this.slotCeiling.isPresent()) {
			summary.put("capacityLimit", SLOTS);
		}
		else {
			summary.putNull("capacityLimit");
		}
		summary.putNull("cpuCapacityRps");
		summary.set("serviceOnlyCapacityRps", Figures.rounded(this.serviceOnly));
	}

	private static OptionalDouble throughput(double slots, OptionalDouble holdMs) {
		if (holdMs.isEmpty() || holdMs.getAsDouble() == 0) {
			return OptionalDouble.empty();
		}
		return OptionalDouble.of(slots * Figures.MS_PER_SECOND / holdMs.getAsDouble());
	}

}
