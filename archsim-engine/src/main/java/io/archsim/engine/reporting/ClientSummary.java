package io.archsim.engine.reporting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.components.client.ClientGroup;
import io.archsim.engine.components.client.ClientMetrics;
import io.archsim.engine.components.client.Cohort;
import io.archsim.engine.components.client.FailureKey;
import io.archsim.engine.components.client.TrafficSourceRuntime;
import io.archsim.engine.numeric.FloatSum;

/**
 * A report's {@code summary}: what clients experienced in the measurement window, how
 * their failures split, each client group's share, and the accounting of every request.
 */
final class ClientSummary {

	private final SimulatedRun run;

	private final ClientMetrics clients;

	private final Labels labels;

	ClientSummary(SimulatedRun run, Labels labels) {
		this.run = run;
		this.clients = run.clients();
		this.labels = labels;
	}

	ObjectNode write() {
		Cohort measured = this.clients.measured();
		double windowS = this.run.windowSeconds();
		long arrived = measured.arrived();
		ObjectNode summary = Figures.NODES.objectNode();
		summary.put("arrived", arrived);
		summary.put("succeeded", measured.succeeded());
		summary.put("failed", measured.errors());
		summary.put("timedOut", measured.timeouts());
		summary.put("outstanding", measured.outstanding());
		summary.set("offeredRps", Figures.rounded((windowS != 0) ? arrived / windowS : 0.0));
		summary.set("throughputRps", Figures.rounded(this.clients.succeeded().windowRate()));
		summary.set("errorRps", Figures.rounded(this.clients.errors().windowRate()));
		summary.set("timeoutRps", Figures.rounded(this.clients.timeouts().windowRate()));
		summary.set("successRate", Figures.ratio(measured.succeeded(), arrived));
		summary.set("errorRate", Figures.ratio(measured.errors(), arrived));
		summary.set("timeoutRate", Figures.ratio(measured.timeouts(), arrived));
		summary.put("attempts", this.clients.attempts().windowCount());
		summary.set("retryAmplification",
				Figures.ratio(this.clients.attempts().windowCount(), this.clients.offered().windowCount()));
		writeLatencies(summary);
		summary.set("failureBreakdown", failureBreakdown());
		summary.set("clients", groups());
		summary.set("accounting", accounting());
		return summary;
	}

	private void writeLatencies(ObjectNode summary) {
		summary.set("latencyMs", Figures.latency(this.clients.latency().summary()));
		summary.set("latencyWithFailuresMs", Figures.latency(this.clients.latencyWithFailures().summary()));
		ObjectNode byOperation = summary.putObject("latencyByOp");
		this.clients.latencyByOperation()
			.forEach((operation, histogram) -> byOperation.set(operation.json(), Figures.latency(histogram.summary())));
	}

	// the measured failures by kind, most frequent first, equal counts in the order of
	// Python's text of their key
	private ArrayNode failureBreakdown() {
		Cohort measured = this.clients.measured();
		long failed = measured.errors() + measured.timeouts();
		List<Map.Entry<FailureKey, Long>> kinds = new ArrayList<>(this.clients.failures().entrySet());
		kinds.sort(Comparator.<Map.Entry<FailureKey, Long>>comparingLong((kind) -> -kind.getValue())
			.thenComparing((kind) -> kind.getKey().pythonTuple()));
		ArrayNode breakdown = Figures.NODES.arrayNode();
		for (Map.Entry<FailureKey, Long> kind : kinds) {
			FailureKey key = kind.getKey();
			ObjectNode failure = breakdown.addObject();
			failure.put("status", key.status().json());
			failure.put("reason", key.reasonName());
			failure.put("reasonText", (key.reason() != null) ? key.reason().text() : key.reasonName());
			failure.put("nodeId", key.nodeId());
			failure.put("nodeLabel", this.labels.of(key.nodeId()));
			failure.put("edgeId", key.edgeId());
			failure.put("count", kind.getValue());
			failure.set("share", Figures.ratio(kind.getValue(), failed));
		}
		return breakdown;
	}

	private ArrayNode groups() {
		List<TrafficSourceRuntime> sources = this.run.sources();
		double total = FloatSum.of(sources.stream().mapToDouble((source) -> source.config().trafficShare()));
		double totalShare = (total != 0) ? total : 1.0;
		double windowS = this.run.windowSeconds();
		ArrayNode groups = Figures.NODES.arrayNode();
		for (TrafficSourceRuntime source : sources) {
			ClientGroup group = source.group();
			Cohort measured = group.measured();
			ObjectNode entry = groups.addObject();
			entry.put("nodeId", source.id());
			entry.put("label", this.labels.of(source.id()));
			entry.put("type", source.node().type());
			entry.set("share", Figures.rounded(source.config().trafficShare() / totalShare));
			entry.put("arrived", measured.arrived());
			entry.put("succeeded", measured.succeeded());
			entry.put("failed", measured.errors());
			entry.put("timedOut", measured.timeouts());
			entry.set("offeredRps", Figures.rounded((windowS != 0) ? measured.arrived() / windowS : 0.0));
			entry.set("successRate", Figures.ratio(measured.succeeded(), measured.arrived()));
			entry.set("retryAmplification",
					Figures.ratio(group.attempts().windowCount(), group.offered().windowCount()));
			entry.set("latencyMs", Figures.latency(group.latency().summary()));
		}
		return groups;
	}

	private ObjectNode accounting() {
		Cohort total = this.clients.warmup().plus(this.clients.measured());
		ObjectNode accounting = Figures.NODES.objectNode();
		accounting.set("warmup", cohort(this.clients.warmup()));
		accounting.set("measured", cohort(this.clients.measured()));
		accounting.set("total", cohort(total));
		accounting.put("invariantHolds", invariantHolds(total));
		accounting.put("drained", this.run.context().idle());
		return accounting;
	}

	// every request the clients sent is accounted for, and those in flight are the ones
	// the client groups still wait on
	private boolean invariantHolds(Cohort total) {
		long inflight = this.run.sources().stream().mapToLong((source) -> source.metrics().inflight().value()).sum();
		return countersMatch(total) && inflight == total.outstanding() && total.outstanding() >= 0;
	}

	private boolean countersMatch(Cohort total) {
		boolean served = this.clients.offered().total() == total.arrived()
				&& this.clients.succeeded().total() == total.succeeded();
		return served && this.clients.errors().total() == total.errors()
				&& this.clients.timeouts().total() == total.timeouts();
	}

	private static ObjectNode cohort(Cohort cohort) {
		ObjectNode written = Figures.NODES.objectNode();
		written.put("arrived", cohort.arrived());
		written.put("succeeded", cohort.succeeded());
		written.put("failed", cohort.errors());
		written.put("timedOut", cohort.timeouts());
		written.put("outstanding", cohort.outstanding());
		return written;
	}

}
