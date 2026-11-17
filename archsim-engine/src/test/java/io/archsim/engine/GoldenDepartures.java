package io.archsim.engine;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.parity.CostDepartures;
import io.archsim.parity.GoldenEntry;
import io.archsim.parity.JsonDiff;
import io.archsim.parity.Parity;
import io.archsim.parity.PythonError;
import io.archsim.parity.SchemaDepartures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The golden entries the Java does not match word for word, each with why, and what it
 * still matches: the same kind of error, and its message up to where the Python's and the
 * Java's libraries word it differently; and a cost, compared as {@link CostDepartures}
 * compares it (ADR-0074), with the counts of the parts that scale that depart and the
 * lines whose rates at the base load depart, each with why. Every other entry of a ported
 * function must match exactly. Every output is read as the frozen engine would have
 * written it at its schema ({@link SchemaDepartures}).
 */
public final class GoldenDepartures {

	private static final Map<Recorded, Departure> DEPARTURES = Map.of(
			new Recorded("fixtures/importers", "/cases/compose-bad-yaml/entries/import_design"),
			new Departure("not valid YAML: while parsing a flow sequence\n",
					"SnakeYAML marks where the problem is in its own words, not PyYAML's (ADR-0043)"));

	/**
	 * The functions whose output holds a cost, and its key there: empty for the whole
	 * output.
	 */
	private static final Map<String, String> COSTS = Map.of("archsim_engine.cost.estimate_cost", "",
			"archsim_engine.analyze.analyze_document", "cost");

	/** Where a template's golden file records a run's entry, such as its cost. */
	private static final Pattern RUN_ENTRY = Pattern.compile("/runs/([^/]+)/entries/.+");

	private static final String EMPTY_CLUSTER = "no pod runs on the cluster, so its autoscaler keeps its minimum; "
			+ "Python billed the nodes the run measured, scaled to the base load, or the nodes it starts with";

	private static final String SETTLED_REPLICAS = "its autoscaler wants these replicas for its work at the base "
			+ "load; Python billed the run's average replicas, scaled to it";

	private static final String SETTLED_NODES = "its pods at the base load need these nodes, within its bounds; "
			+ "Python billed the run's average nodes, which lag its pods while nodes start or go";

	private static final String FIXED_CLUSTER = "a cluster without its autoscaler runs its nodes; Python billed the "
			+ "run's average, scaled to the base load";

	/**
	 * The counts of the parts that scale where the Java's depart from the frozen engine's
	 * (ADR-0074), by case: a report case's name, or a recorded simulation's.
	 */
	private static final Map<String, Counts> COUNTS = Map.ofEntries(emptyCluster("measuredAtTheBaseLoad"),
			emptyCluster("noBucketAtTheBaseLoad"), emptyCluster("noBucketInTheWindow"), emptyCluster("noOfferedRate"),
			emptyCluster("noThroughput"), emptyCluster("noRequestSucceeded"),
			emptyCluster("replicasAndNodesNotMeasured"), emptyCluster("aFunctionWithoutItsInflightSeries"),
			emptyCluster("aPartWithoutItsArrivalRate"), emptyCluster("aBaseLoadOfZero"),
			Map.entry("anAutoscalingServiceRampingUpAndDown", new Counts(Map.of("svc", 3.0), SETTLED_REPLICAS)),
			Map.entry("anAutoscalingServiceOnCpuBehindABalancer", new Counts(Map.of("svc", 4.0), SETTLED_REPLICAS)),
			Map.entry("podsWaitingForNodes", new Counts(Map.of("k8s", 4.0), SETTLED_NODES)),
			Map.entry("aClusterRemovingItsEmptyNodes", new Counts(Map.of("k8s", 2.0), SETTLED_NODES)),
			Map.entry("anAutoscalingServiceWhosePodsWait", new Counts(Map.of("k8s", 2.0), SETTLED_NODES)),
			Map.entry("aBalancerWhosePodsStayPending", new Counts(Map.of("k8s", 1.0), FIXED_CLUSTER)));

	private static final String NEAREST_THE_BASE = "no bucket of the run's window is at the base load, so the "
			+ "Java measures the base's rates in the buckets nearest it; Python scaled the whole window's down "
			+ "to it, so the peak's failures and timeouts moved them; the line is free, so it costs the same";

	private static final String ZERO_BASE = "no bucket is at a base load of 0, so the Java measures the "
			+ "function's time a request in the buckets nearest it, Python over the whole window; its rate is "
			+ "0 either way, so it costs the same";

	private static final String BLACK_FRIDAY = "templates/black-friday-kubernetes";

	private static final String AUTH_TOKEN = "templates/auth-token-introspection";

	private static final String BF_PAYMENTS = "53.8 calls/s at the base load";

	private static final String AUTH_IDP = "135.5 calls/s at the base load";

	/**
	 * The lines whose rates at the base load depart from the frozen engine's, by case: a
	 * report case's name, or a recorded run's (its template's golden file and the run).
	 */
	private static final Map<String, Bases> BASES = Map.ofEntries(
			nearestTheBase(runCase(BLACK_FRIDAY, "base"), "payments", BF_PAYMENTS),
			nearestTheBase(runCase(BLACK_FRIDAY, "payments-slow"), "payments", BF_PAYMENTS),
			nearestTheBase(runCase(AUTH_TOKEN, "base"), "idp", AUTH_IDP),
			nearestTheBase(runCase(AUTH_TOKEN, "idp-down"), "idp", AUTH_IDP),
			zeroBase("aBaseLoadOfZero", "fn", "0.0 invocations/s at the base load × 20.0 ms × 1,024 MB"));

	private GoldenDepartures() {
	}

	/**
	 * Checks an entry exactly, or as its departure says.
	 * @param entry the entry
	 * @param call the Java counterpart of its function
	 */
	static void check(GoldenEntry entry, Function<GoldenEntry, JsonNode> call) {
		Optional<Departure> departure = of(entry);
		String cost = COSTS.get(entry.fn());
		if (departure.isPresent()) {
			departure.get().check(entry, call);
		}
		else if (cost != null && !entry.raised()) {
			JsonNode expected = entry.expectedOutput();
			// a case file names an entry by its case (CaseFiles), a golden file by its
			// place, which no departing case shares
			JsonNode actual = SchemaDepartures.asRecorded(call.apply(entry), expected);
			assertThat(JsonDiff.differences(expected, comparable(expected, actual, cost, caseOf(entry))))
				.as("%s: the output differs from the Python engine's at", entry)
				.isEmpty();
		}
		else {
			Parity.check(entry, call);
		}
	}

	/**
	 * The cost lines that depart in a case.
	 * @param name the case: a report case's name, a recorded simulation's, or a recorded
	 * run's (its template's golden file and the run, such as
	 * {@code templates/black-friday-kubernetes base})
	 * @return what the Java counts and says where it departs; none when nothing departs
	 */
	public static CostDepartures.Departing departing(String name) {
		Counts counts = COUNTS.get(name);
		Bases bases = BASES.get(name);
		return new CostDepartures.Departing((counts != null) ? counts.byLine() : Map.of(),
				(bases != null) ? bases.byLine() : Map.of());
	}

	/**
	 * The departure of an entry.
	 * @param entry a golden entry
	 * @return how the Java departs from it; empty when it must match exactly
	 */
	static Optional<Departure> of(GoldenEntry entry) {
		return Optional.ofNullable(DEPARTURES.get(Recorded.of(entry)));
	}

	/**
	 * Every entry listed.
	 * @return each entry's file and pointer
	 */
	static Set<Recorded> entries() {
		return DEPARTURES.keySet();
	}

	// an output with its cost compared as CostDepartures compares it
	private static JsonNode comparable(JsonNode expected, JsonNode actual, String cost, String name) {
		if (cost.isEmpty()) {
			return CostDepartures.comparable(expected, actual, departing(name));
		}
		ObjectNode copy = (ObjectNode) actual.deepCopy();
		copy.set(cost, CostDepartures.comparable(expected.path(cost), actual.path(cost), departing(name)));
		return copy;
	}

	/**
	 * The case of a template's recorded run.
	 * @param file the template's golden file, such as
	 * {@code templates/black-friday-kubernetes}
	 * @param run the run, such as {@code base}
	 * @return its name, such as {@code templates/black-friday-kubernetes base}
	 */
	public static String runCase(String file, String run) {
		return file + " " + run;
	}

	// the case of an entry: its run's for an entry of a template's recorded run; else its
	// place, a case file's case
	private static String caseOf(GoldenEntry entry) {
		Matcher run = RUN_ENTRY.matcher(entry.pointer());
		return run.matches() ? runCase(entry.file(), run.group(1)) : entry.pointer();
	}

	// a report case whose cluster, k8s, no pod runs on
	private static Map.Entry<String, Counts> emptyCluster(String name) {
		return Map.entry(name, new Counts(Map.of("k8s", 1.0), EMPTY_CLUSTER));
	}

	// a recorded run with no bucket at its base load, whose line priced per request says
	// what is given
	private static Map.Entry<String, Bases> nearestTheBase(String run, String line, String basis) {
		return Map.entry(run, new Bases(Map.of(line, basis), NEAREST_THE_BASE));
	}

	// a report case with a base load of 0, whose function's line says what is given
	private static Map.Entry<String, Bases> zeroBase(String name, String line, String basis) {
		return Map.entry(name, new Bases(Map.of(line, basis), ZERO_BASE));
	}

	/**
	 * The counts the Java bills where they depart from the frozen engine's.
	 *
	 * @param byLine what the Java counts, by line id
	 * @param why why they depart
	 */
	record Counts(Map<String, Double> byLine, String why) {
	}

	/**
	 * What the Java says of lines whose rates at the base load depart from the frozen
	 * engine's.
	 *
	 * @param byLine each line's basis, by line id
	 * @param why why they depart
	 */
	record Bases(Map<String, String> byLine, String why) {
	}

	/**
	 * Where a golden file records a call.
	 *
	 * @param file the golden file, such as {@code fixtures/importers}
	 * @param pointer the entry's place in it
	 */
	record Recorded(String file, String pointer) {

		static Recorded of(GoldenEntry entry) {
			return new Recorded(entry.file(), entry.pointer());
		}

	}

	/**
	 * How the Java departs from an entry that raised.
	 *
	 * @param prefix where the messages agree: up to where the two libraries' words part
	 * @param why why the rest differs
	 */
	record Departure(String prefix, String why) {

		/**
		 * Checks the Java's error against the entry's: the same type, both messages
		 * starting with the prefix, and differing past it (else the entry is no departure
		 * and leaves the list).
		 * @param entry the entry
		 * @param call the Java counterpart of its function
		 */
		void check(GoldenEntry entry, Function<GoldenEntry, JsonNode> call) {
			try {
				JsonNode actual = call.apply(entry);
				fail("%s: the Python engine raised %s, Java returned %s", entry, entry.expectedError(), actual);
			}
			catch (PythonError ex) {
				JsonNode expected = entry.expectedError();
				assertThat(ex.error().path("type")).as("%s: the error's type", entry).isEqualTo(expected.path("type"));
				assertThat(expected.path("message").stringValue()).as("%s: the Python engine's message", entry)
					.startsWith(this.prefix);
				assertThat(ex.error().path("message").stringValue()).as("%s: the Java's message (%s)", entry, this.why)
					.startsWith(this.prefix)
					.isNotEqualTo(expected.path("message").stringValue());
			}
		}

	}

}
