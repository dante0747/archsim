package io.archsim.engine.reporting;

import java.util.Comparator;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.metrics.Counter;
import io.archsim.engine.runtime.NodeMetrics;
import io.archsim.engine.runtime.RuntimeComponent;

/**
 * A part's summary and accounting in a report: its load, refusals, utilization, queue,
 * times, capacity and counters over the measurement window. A part's utilization is its
 * occupancy (its busy slots, or a balancer's requests in flight) over its slots.
 */
final class NodeEntry {

	private final RuntimeComponent component;

	private final NodeMetrics metrics;

	NodeEntry(RuntimeComponent component) {
		this.component = component;
		this.metrics = component.metrics();
	}

	ObjectNode summary() {
		ObjectNode summary = Figures.NODES.objectNode();
		long arrivals = this.metrics.arrivals().windowCount();
		summary.put("arrivals", arrivals);
		summary.set("arrivalRps", Figures.rounded(this.metrics.arrivals().windowRate()));
		summary.put("completed", this.metrics.completed().windowCount());
		summary.put("failedDownstream", this.metrics.failedDownstream().windowCount());
		ObjectNode rejected = summary.putObject("rejected");
		this.metrics.rejected()
			.entrySet()
			.stream()
			.sorted(Comparator.comparing((entry) -> entry.getKey().json()))
			.forEach((entry) -> rejected.put(entry.getKey().json(), entry.getValue().windowCount()));
		long rejectedTotal = this.metrics.rejected().values().stream().mapToLong(Counter::windowCount).sum();
		summary.put("rejectedTotal", rejectedTotal);
		summary.set("rejectionRate", Figures.ratio(rejectedTotal, arrivals));
		writeUtilization(summary);
		writeQueue(summary);
		summary.set("meanHoldMs", Figures.rounded(this.metrics.hold().windowMean()));
		summary.set("meanServiceMs", Figures.rounded(this.metrics.service().windowMean()));
		Capacity.of(this.component).write(summary);
		summary.set("hitRatio", HitRatio.of(this.metrics).map(HitRatio::summary).orElse(Figures.NODES.nullNode()));
		ObjectNode counters = summary.putObject("counters");
		this.metrics.counters()
			.entrySet()
			.stream()
			.sorted(Map.Entry.comparingByKey())
			.forEach((entry) -> counters.put(entry.getKey(), entry.getValue().windowCount()));
		return summary;
	}

	ObjectNode accounting() {
		ObjectNode accounting = Figures.NODES.objectNode();
		long arrivals = this.metrics.arrivals().total();
		long completed = this.metrics.completed().total();
		long failed = this.metrics.failedDownstream().total();
		long rejected = this.metrics.rejectedTotal();
		long inProgress = this.metrics.inflight().value();
		accounting.put("arrivals", arrivals);
		accounting.put("completed", completed);
		accounting.put("failedDownstream", failed);
		accounting.put("rejected", rejected);
		accounting.put("inProgress", inProgress);
		accounting.put("invariantHolds", arrivals == completed + failed + rejected + inProgress);
		return accounting;
	}

	private void writeUtilization(ObjectNode summary) {
		OptionalInt slots = this.metrics.slots();
		if (slots.isPresent()) {
			summary.put("slots", slots.getAsInt());
		}
		else {
			summary.putNull("slots");
		}
		OptionalDouble count = Capacity.slots(this.metrics);
		summary.set("utilization",
				count.isPresent() ? Figures.rounded(this.component.occupancy().windowMean() / count.getAsDouble())
						: Figures.NODES.nullNode());
		summary.set("cpuUtilization", (count.isPresent() && this.component.splitsCpu())
				? Figures.rounded(this.metrics.cpu().windowMean() / count.getAsDouble()) : Figures.NODES.nullNode());
	}

	private void writeQueue(ObjectNode summary) {
		summary.set("meanInSystem", Figures.rounded(this.metrics.inflight().windowMean()));
		summary.set("meanQueue", Figures.rounded(this.metrics.queue().windowMean()));
		summary.put("peakQueue", this.metrics.queue().windowPeak());
		summary.set("meanQueueWaitMs", Figures.rounded(this.metrics.queueWait().windowMean()));
		summary.set("p95QueueWaitMs", Figures.rounded(this.metrics.queueWaitHistogram().percentile(Percentiles.P95)));
		summary.set("p99QueueWaitMs", Figures.rounded(this.metrics.queueWaitHistogram().percentile(Percentiles.P99)));
	}

}
