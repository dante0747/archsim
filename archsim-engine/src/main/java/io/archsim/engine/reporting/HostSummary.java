package io.archsim.engine.reporting;

import java.util.Optional;

import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.runtime.SharedHost;

/**
 * A report's {@code host}: the design's host machine over the measurement window, its
 * parts' busy threads, the background's and the clients' traffic's, and the mean speed
 * work ran at.
 */
final class HostSummary {

	private HostSummary() {
	}

	/**
	 * The host of a run.
	 * @param run the finished run
	 * @return its summary; empty when the design has no host
	 */
	static Optional<ObjectNode> of(SimulatedRun run) {
		return run.context().host().map(HostSummary::write);
	}

	private static ObjectNode write(SharedHost host) {
		SharedHost.Window window = host.window();
		ObjectNode summary = Figures.NODES.objectNode();
		summary.put("physicalCores", host.machine().cores());
		summary.put("threads", host.machine().threads());
		summary.set("partsBusyThreads", Figures.rounded(window.partsBusyThreads()));
		summary.set("otherBusyThreads", Figures.rounded(window.otherBusyThreads()));
		summary.set("busyThreads", Figures.rounded(window.partsBusyThreads() + window.otherBusyThreads()));
		summary.set("meanSpeed", Figures.rounded(window.meanSpeed()));
		return summary;
	}

}
