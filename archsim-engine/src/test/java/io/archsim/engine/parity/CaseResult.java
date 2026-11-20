package io.archsim.engine.parity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiPredicate;
import java.util.stream.IntStream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.parity.JsonDiff;

/**
 * A template case's Java runs, against the frozen engine's.
 *
 * @param template the template
 * @param scenario the scenario, or {@code base}
 * @param runs how many runs
 * @param exact how many gave exactly Python's figures, or Python's error
 * @param digestsEqual how many gave Python's result digest
 * @param departing the runs that depart only where the baselines keep too little to read
 * them as the frozen engine's
 * @param failures the runs that failed on each side
 * @param metrics each metric against the bar
 */
record CaseResult(String template, String scenario, int runs, int exact, int digestsEqual, Departing departing,
		Failures failures, List<MetricVerdict> metrics) {

	private static final String DIGEST = "resultDigest";

	private static final String ERROR = "error";

	/**
	 * Copies the metrics, so the record does not change.
	 */
	CaseResult {
		metrics = List.copyOf(metrics);
	}

	/**
	 * A case's Java runs judged against Python's: run by run for an exact replay, and
	 * over the seeds by the bar.
	 * @param recorded the case and Python's runs
	 * @param runs the Java runs of the same seeds, in order
	 * @return the result
	 */
	static CaseResult of(RecordedCase recorded, List<JavaRun> runs) {
		List<JsonNode> python = recorded.runs().stream().map(CaseResult::withoutSeed).toList();
		List<JsonNode> java = runs.stream().map(JavaRun::figures).toList();
		int exact = count(python, java, (one, other) -> JsonDiff.differences(one, other).isEmpty());
		int digests = count(python, java,
				(one, other) -> one.path(DIGEST).isString() && one.path(DIGEST).equals(other.path(DIGEST)));
		Failures failures = new Failures(count(python, java, (one, other) -> one.has(ERROR)),
				count(python, java, (one, other) -> other.has(ERROR)),
				count(python, java, (one, other) -> !one.path(ERROR).equals(other.path(ERROR))));
		return new CaseResult(recorded.template(), recorded.scenario(), java.size(), exact, digests,
				Departing.of(python, runs), failures, ParityBar.judge(python, java));
	}

	/**
	 * What missed the bar: the runs, when a seed failed otherwise than Python's (on one
	 * side only, or with another error), then each metric that did.
	 * @return what missed, in words
	 */
	List<String> missed() {
		List<String> missed = new ArrayList<>();
		if (this.failures.mismatched() > 0) {
			int seeds = this.failures.mismatched();
			missed.add("%d %s failed otherwise than Python's (Java failed %d, Python %d)".formatted(seeds,
					(seeds == 1) ? "seed" : "seeds", this.failures.java(), this.failures.python()));
		}
		this.metrics.stream().filter((verdict) -> !verdict.holds()).map(MetricVerdict::metric).forEach(missed::add);
		return List.copyOf(missed);
	}

	boolean meetsTheBar() {
		return missed().isEmpty();
	}

	boolean insideRange() {
		return this.metrics.stream().allMatch(MetricVerdict::insideRange);
	}

	boolean withinTwoPercent() {
		return this.metrics.stream().allMatch(MetricVerdict::withinTwoPercent);
	}

	boolean failuresMatch() {
		return this.failures.mismatched() == 0;
	}

	boolean replaysExactly() {
		return this.exact == this.runs;
	}

	private static JsonNode withoutSeed(JsonNode run) {
		ObjectNode copy = (ObjectNode) run.deepCopy();
		copy.remove("seed");
		return copy;
	}

	// how many of the seeds' Python and Java runs pass the test
	private static int count(List<JsonNode> python, List<JsonNode> java, BiPredicate<JsonNode, JsonNode> test) {
		return (int) IntStream.range(0, java.size())
			.filter((index) -> test.test(python.get(index), java.get(index)))
			.count();
	}

	/**
	 * The runs that depart only where the baselines keep too little to read them as the
	 * frozen engine's: every figure kept is Python's but the result digest, and the run
	 * shows such a departure ({@link RunDepartures#unread}).
	 *
	 * @param runs how many
	 * @param byDeparture how many show each departure, by its name
	 */
	record Departing(int runs, Map<String, Integer> byDeparture) {

		private static final List<String> RESULT_DIGEST_ALONE = List.of("/" + DIGEST);

		/**
		 * Copies the counts, in the order of their names, so the record does not change.
		 */
		Departing {
			byDeparture = Collections.unmodifiableMap(new TreeMap<>(byDeparture));
		}

		// the seeds' Java runs that depart from Python's so
		static Departing of(List<JsonNode> python, List<JavaRun> java) {
			int runs = 0;
			Map<String, Integer> byDeparture = new TreeMap<>();
			for (int index = 0; index < java.size(); index++) {
				JavaRun run = java.get(index);
				boolean digestAlone = RESULT_DIGEST_ALONE
					.equals(JsonDiff.differences(python.get(index), run.figures()));
				if (digestAlone && !run.departures().isEmpty()) {
					runs++;
					run.departures().forEach((departure) -> byDeparture.merge(departure, 1, Integer::sum));
				}
			}
			return new Departing(runs, byDeparture);
		}

	}

	/**
	 * The runs that failed.
	 *
	 * @param python how many of Python's
	 * @param java how many of Java's
	 * @param mismatched how many seeds failed otherwise than Python's: on one side only,
	 * or with another error
	 */
	record Failures(int python, int java, int mismatched) {

	}

}
