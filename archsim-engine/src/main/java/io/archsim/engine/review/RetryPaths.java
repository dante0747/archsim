package io.archsim.engine.review;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import io.archsim.model.document.EdgeSpec;
import io.archsim.model.document.NodeSpec;

/**
 * The review's walk along every request path from each traffic source: where retries
 * multiply the attempts a part receives, and the longest synchronous chain. Layered
 * fan-out has exponentially many paths, so the walk stops after a fixed number of steps,
 * the same for the same design every time.
 */
final class RetryPaths {

	/** The most steps the walk takes over all the sources. */
	private static final int STEP_BUDGET = 20_000;

	/** The walk goes no deeper than paths of one part more than this. */
	private static final int LONGEST_PATH = 14;

	/** Retries are reported from this many attempts per request on. */
	private static final BigInteger AMPLIFIED = BigInteger.valueOf(4);

	/** From this many attempts per request on, amplification is of high severity. */
	private static final BigInteger SEVERELY_AMPLIFIED = BigInteger.valueOf(9);

	/** The chains of retries reported, at most. */
	private static final int MOST_REPORTED = 3;

	/** A chain is long from this many parts on. */
	private static final int LONG_CHAIN = 6;

	private final ReviewGraph graph;

	private final Findings findings;

	/**
	 * The path with the most attempts to each part, by the part's id, as first reached.
	 */
	private final Map<String, Route> worst = new LinkedHashMap<>();

	private @Nullable Route deepest;

	private int steps;

	RetryPaths(ReviewGraph graph, Findings findings) {
		this.graph = graph;
		this.findings = findings;
	}

	/**
	 * Walks every path, then reports up to three chains of retries and the longest chain.
	 */
	void check() {
		for (NodeSpec source : this.graph.sources()) {
			BigInteger attempts = attempts(this.graph.settings(source.id()).nonZero("maxAttempts").orElse(1));
			List<Factor> factors = (attempts.compareTo(BigInteger.ONE) > 0)
					? List.of(new Factor(this.graph.label(source.id()) + " (client)", attempts)) : List.of();
			walk(new Route(attempts, factors, List.of(source.id()), List.of()));
		}
		amplified().stream().limit(MOST_REPORTED).forEach(this::amplification);
		Route longest = this.deepest;
		if (longest != null && longest.path().size() >= LONG_CHAIN) {
			deepChain(longest);
		}
	}

	private void walk(Route route) {
		this.steps++;
		String id = route.end();
		Route previous = this.worst.get(id);
		if (route.path().size() > 1 && (previous == null || route.product().compareTo(previous.product()) > 0)) {
			this.worst.put(id, route);
		}
		Route longest = this.deepest;
		if (longest == null || route.path().size() > longest.path().size()) {
			this.deepest = route;
		}
		if (route.path().size() > LONGEST_PATH || this.steps > STEP_BUDGET) {
			return;
		}
		for (EdgeSpec edge : this.graph.outs(id)) {
			if (!route.path().contains(edge.target())) {
				BigInteger attempts = BigInteger.valueOf(edge.config().maxAttempts());
				walk(route.then(edge,
						new Factor(this.graph.label(edge.source()) + "→" + this.graph.label(edge.target()), attempts)));
			}
		}
	}

	/**
	 * The paths with enough attempts to report, each chain of retries once, at the part
	 * closest to where it lands: the parts behind its last retrying hop get the same
	 * attempts.
	 * @return the paths, the most attempts first
	 */
	private List<Route> amplified() {
		Map<String, Route> bySignature = new LinkedHashMap<>();
		for (Route route : this.worst.values()) {
			if (route.product().compareTo(AMPLIFIED) >= 0) {
				String signature = route.factors().stream().map(Factor::label).collect(Collectors.joining("|"));
				Route previous = bySignature.get(signature);
				if (previous == null || route.path().size() < previous.path().size()) {
					bySignature.put(signature, route);
				}
			}
		}
		List<Route> amplified = new ArrayList<>(bySignature.values());
		amplified.sort(Comparator.comparing(Route::product).reversed());
		return amplified;
	}

	private void amplification(Route route) {
		String landing = this.graph.label(route.end());
		String product = route.product().toString();
		String math = route.factors()
			.stream()
			.map((factor) -> factor.times().toString())
			.collect(Collectors.joining(" × "));
		String parts = route.factors()
			.stream()
			.map((factor) -> factor.label() + " ×" + factor.times())
			.collect(Collectors.joining(", "));
		this.findings.add(ReviewRule.RETRY_AMPLIFICATION,
				(route.product().compareTo(SEVERELY_AMPLIFIED) >= 0) ? Severity.HIGH : Severity.MEDIUM,
				new Involved(route.path(), route.edges()),
				new Wording("Up to " + product + " attempts reach " + landing + " per request",
						"Retries multiply along the path: " + math + " = " + product + " (" + parts + "). When "
								+ landing + " is failing, it receives " + product
								+ "× the traffic exactly when it is weakest.",
						"Retry at one layer only (usually the outermost), use a retry budget, and fail fast below "
								+ "it."));
	}

	private void deepChain(Route longest) {
		this.findings.add(ReviewRule.DEEP_CHAIN, Severity.LOW, new Involved(longest.path(), longest.edges()),
				new Wording((longest.path().size() - 1) + " synchronous hops on one request path",
						String.join(" → ", longest.path().stream().map(this.graph::label).toList())
								+ ": every hop adds latency and its own failure probability to the request.",
						"Collapse hops, call independent dependencies in parallel, or move non-critical work to a "
								+ "queue."));
	}

	/**
	 * A client's attempts, read leniently: the whole number of a setting, at least one.
	 * @param attempts the setting, one when missing or zero
	 * @return the whole attempts, at least one
	 */
	private static BigInteger attempts(double attempts) {
		BigInteger whole = new BigDecimal(attempts).toBigInteger();
		return whole.max(BigInteger.ONE);
	}

	/**
	 * One hop's attempts on a path.
	 *
	 * @param label the hop: a client, or a link from caller to callee
	 * @param times its attempts
	 */
	private record Factor(String label, BigInteger times) {
	}

	/**
	 * A path from a traffic source, and the attempts that reach its end.
	 *
	 * @param product the attempts that reach its end per request
	 * @param factors the hops that retry, in order
	 * @param path its parts, from the source
	 * @param edges its links
	 */
	private record Route(BigInteger product, List<Factor> factors, List<String> path, List<String> edges) {

		String end() {
			return this.path.get(this.path.size() - 1);
		}

		/**
		 * The path one link further.
		 * @param edge the link
		 * @param factor its attempts
		 * @return the longer path
		 */
		Route then(EdgeSpec edge, Factor factor) {
			List<Factor> moreFactors = new ArrayList<>(this.factors);
			if (factor.times().compareTo(BigInteger.ONE) > 0) {
				moreFactors.add(factor);
			}
			List<String> morePath = new ArrayList<>(this.path);
			morePath.add(edge.target());
			List<String> moreEdges = new ArrayList<>(this.edges);
			moreEdges.add(edge.id());
			return new Route(this.product.multiply(factor.times()), List.copyOf(moreFactors), List.copyOf(morePath),
					List.copyOf(moreEdges));
		}

	}

}
