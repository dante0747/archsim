package io.archsim.engine.review;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.stream.IntStream;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.load.ComponentFamily;
import io.archsim.engine.numeric.FloatSum;
import io.archsim.engine.settings.LenientSettings;
import io.archsim.engine.text.NumberText;
import io.archsim.model.document.EdgeConfig;
import io.archsim.model.document.EdgeSpec;
import io.archsim.model.document.NodeSpec;

/**
 * The review's rules about calls between parts: a call without a timeout, retries sent at
 * once, and a caller that gives up before its callee does.
 */
final class CallRules {

	/** How much a call without a timeout matters, by what it calls. */
	private static final Map<ComponentFamily, Severity> UNTIMED = Map.of(ComponentFamily.EXTERNAL, Severity.HIGH,
			ComponentFamily.DATABASE, Severity.MEDIUM, ComponentFamily.COMPUTE, Severity.MEDIUM,
			ComponentFamily.MESSAGING, Severity.LOW, ComponentFamily.CACHE, Severity.LOW);

	private final ReviewGraph graph;

	private final Findings findings;

	CallRules(ReviewGraph graph, Findings findings) {
		this.graph = graph;
		this.findings = findings;
	}

	/**
	 * Checks every part's calls: the calls each part makes without a timeout, one finding
	 * per caller.
	 */
	void missingTimeouts() {
		for (NodeSpec node : this.graph.architecture().nodes()) {
			ComponentFamily family = this.graph.family(node.id());
			if (family == ComponentFamily.TRAFFIC) {
				continue;
			}
			List<EdgeSpec> missing = this.graph.outs(node.id())
				.stream()
				.filter((edge) -> edge.config().timeoutMs() == null
						&& UNTIMED.containsKey(this.graph.family(edge.target())))
				.toList();
			if (!missing.isEmpty()) {
				missingTimeout(node.id(), family, missing);
			}
		}
	}

	/**
	 * Checks every link, then every traffic source, for retries without backoff.
	 */
	void retriesWithoutBackoff() {
		for (EdgeSpec edge : this.graph.architecture().edges()) {
			EdgeConfig config = edge.config();
			if (config.maxAttempts() > 1 && config.retryBackoffMs() == 0) {
				String callee = this.graph.label(edge.target());
				this.findings.add(ReviewRule.RETRY_NO_BACKOFF, Severity.MEDIUM,
						new Involved(List.of(edge.source(), edge.target()), List.of(edge.id())),
						new Wording(this.graph.label(edge.source()) + " retries " + callee + " immediately",
								config.maxAttempts() + " attempts with 0 ms backoff: when " + callee
										+ " struggles, retries arrive at once and add load exactly when it can least "
										+ "absorb it.",
								"Use exponential backoff with jitter (e.g. 50–100 ms base) and a retry budget."));
			}
		}
		for (NodeSpec source : this.graph.sources()) {
			LenientSettings settings = this.graph.settings(source.id());
			double attempts = settings.nonZero("maxAttempts").orElse(1);
			if (attempts > 1 && settings.nonZero("retryBackoffMs").orElse(0) == 0) {
				this.findings.add(ReviewRule.RETRY_NO_BACKOFF, Severity.MEDIUM, Involved.node(source.id()),
						new Wording(this.graph.label(source.id()) + " retry without backoff",
								"Clients retry " + NumberText.plain(attempts)
										+ " times immediately after a failure or timeout, multiplying load during an "
										+ "incident.",
								"Add exponential backoff with jitter on the client."));
			}
		}
	}

	/**
	 * Checks whether the caller of each link between parts the design has waits less than
	 * its callee may wait on its own calls.
	 */
	void timeoutInversions() {
		for (EdgeSpec edge : this.graph.architecture().edges()) {
			if (!this.graph.has(edge.source()) || !this.graph.has(edge.target())) {
				continue;
			}
			boolean fromSource = this.graph.family(edge.source()) == ComponentFamily.TRAFFIC;
			OptionalDouble callerTimeout = fromSource ? this.graph.settings(edge.source()).number("timeoutMs")
					: optional(edge.config().timeoutMs());
			Optional<Budget> budget = budget(edge.target());
			if (callerTimeout.isPresent() && budget.isPresent()
					&& callerTimeout.getAsDouble() < budget.get().milliseconds()) {
				timeoutInversion(edge, fromSource, callerTimeout.getAsDouble(), budget.get());
			}
		}
	}

	private void missingTimeout(String id, ComponentFamily family, List<EdgeSpec> missing) {
		Severity severity = missing.stream()
			.map((edge) -> (family == ComponentFamily.NETWORK) ? Severity.LOW
					: Objects.requireNonNull(UNTIMED.get(this.graph.family(edge.target()))))
			.min(Comparator.naturalOrder())
			.orElseThrow();
		List<String> names = missing.stream().map((edge) -> this.graph.label(edge.target())).toList();
		String who = (names.size() == 1) ? names.get(0) : names.size() + " dependencies";
		String name = this.graph.label(id);
		List<String> nodeIds = new ArrayList<>(List.of(id));
		missing.forEach((edge) -> nodeIds.add(edge.target()));
		this.findings.add(ReviewRule.MISSING_TIMEOUT, severity,
				new Involved(nodeIds, missing.stream().map(EdgeSpec::id).toList()),
				new Wording(name + " waits indefinitely on " + who,
						"No timeout on " + String.join(", ", names) + ": a slow or hung dependency keeps " + name
								+ "'s workers waiting until its pool fills and requests queue behind them.",
						"Set per-call timeouts a little above each dependency's p99 (for example 2× p99), keeping "
								+ "their sum inside the caller's own budget."));
	}

	private void timeoutInversion(EdgeSpec edge, boolean fromSource, double callerTimeout, Budget budget) {
		EdgeConfig via = budget.via().config();
		int attempts = via.maxAttempts();
		double timeout = (via.timeoutMs() != null) ? via.timeoutMs() : 0.0;
		String caller = this.graph.label(edge.source());
		String callee = this.graph.label(edge.target());
		String retries = (attempts > 1)
				? " (" + ReviewText.milliseconds(timeout) + " × " + attempts + " attempts + backoff)" : "";
		this.findings.add(ReviewRule.TIMEOUT_INVERSION, Severity.MEDIUM,
				new Involved(List.of(edge.source(), edge.target(), budget.via().target()),
						List.of(edge.id(), budget.via().id())),
				new Wording(caller + " gives up before " + callee + " does",
						(fromSource ? caller + " (client timeout)" : caller) + " waits "
								+ ReviewText.milliseconds(callerTimeout) + " for " + callee + ", but " + callee
								+ " may wait up to " + ReviewText.milliseconds(budget.milliseconds()) + " on "
								+ this.graph.label(budget.via().target()) + retries
								+ ": it keeps working on requests nobody is waiting for.",
						"Make timeouts shrink downstream (each caller waits longer than its callee's whole budget) or "
								+ "propagate deadlines."));
	}

	/**
	 * The longest a part may wait on one of its own calls with a timeout: every attempt's
	 * timeout and the backoffs between them.
	 * @param id the part
	 * @return the longest wait, and the call it is on; empty when no call has a timeout
	 */
	private Optional<Budget> budget(String id) {
		Budget longest = null;
		for (EdgeSpec edge : this.graph.outs(id)) {
			EdgeConfig config = edge.config();
			Double timeout = config.timeoutMs();
			if (timeout == null) {
				continue;
			}
			int attempts = config.maxAttempts();
			double backoff = config.retryBackoffMs();
			double backoffs = FloatSum
				.of(IntStream.range(0, attempts - 1).mapToDouble((retry) -> Math.scalb(backoff, retry)));
			double milliseconds = timeout * attempts + backoffs;
			if (longest == null || milliseconds > longest.milliseconds()) {
				longest = new Budget(milliseconds, edge);
			}
		}
		return Optional.ofNullable(longest);
	}

	private static OptionalDouble optional(@Nullable Double value) {
		return (value != null) ? OptionalDouble.of(value) : OptionalDouble.empty();
	}

	/**
	 * How long a part may wait on one of its calls.
	 *
	 * @param milliseconds the wait, every attempt and backoff included
	 * @param via the call
	 */
	private record Budget(double milliseconds, EdgeSpec via) {
	}

}
