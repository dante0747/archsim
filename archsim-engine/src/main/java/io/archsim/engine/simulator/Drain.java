package io.archsim.engine.simulator;

import java.util.List;

import io.archsim.model.component.Category;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.client.ClientConfig;
import io.archsim.model.document.ArchitectureDocument;

/**
 * How long a run goes on after its arrivals end. The run still stops as soon as nothing
 * is queued or in flight.
 */
final class Drain {

	private static final double MS_PER_SECOND = 1000.0;

	/** The longest drain a run gets unless the workload sets its own. */
	private static final double MAX_DRAIN_S = 600.0;

	/** The least drain a design with a messaging part gets. */
	private static final double ASYNC_MIN_DRAIN_S = 60.0;

	private Drain() {
	}

	/**
	 * The workload's drain, or the time by which every client has given up or finished;
	 * with a messaging part, at least a minute, as its backlog outlives the clients'
	 * deadlines.
	 * @param resolved the design as resolved
	 * @param sources its client groups' settings
	 * @param registry the component types
	 * @return the drain, in seconds
	 */
	static double seconds(ArchitectureDocument resolved, List<ClientConfig> sources, ComponentRegistry registry) {
		Double set = resolved.workload().drainS();
		if (set != null) {
			return set;
		}
		double drainS = sources.stream().mapToDouble(Drain::clientDeadline).max().orElseThrow();
		if (hasMessaging(resolved, registry)) {
			return Math.min(MAX_DRAIN_S, Math.max(drainS, ASYNC_MIN_DRAIN_S));
		}
		return drainS;
	}

	private static boolean hasMessaging(ArchitectureDocument resolved, ComponentRegistry registry) {
		return resolved.architecture()
			.nodes()
			.stream()
			.anyMatch((node) -> registry.find(node.type()).orElseThrow().category() == Category.MESSAGING);
	}

	private static double clientDeadline(ClientConfig client) {
		int attempts = client.maxAttempts();
		double backoff = client.retryBackoffMs() * ((1L << (attempts - 1)) - 1);
		double deadlineMs = client.timeoutMs() * attempts + backoff;
		return Math.min(deadlineMs / MS_PER_SECOND + 1.0, MAX_DRAIN_S);
	}

}
