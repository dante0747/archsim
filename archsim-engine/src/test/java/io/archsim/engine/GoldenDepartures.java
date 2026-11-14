package io.archsim.engine;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

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
 * compares it (ADR-0074), with the counts of the parts that scale that depart, each with
 * why. Every other entry of a ported function must match exactly. Every output is read as
 * the frozen engine would have written it at its schema ({@link SchemaDepartures}).
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
			JsonNode actual = SchemaDepartures.asRecorded(call.apply(entry));
			assertThat(JsonDiff.differences(expected, comparable(expected, actual, cost, entry.pointer())))
				.as("%s: the output differs from the Python engine's at", entry)
				.isEmpty();
		}
		else {
			Parity.check(entry, call);
		}
	}

	/**
	 * The counts that depart in a case, by line id.
	 * @param name the case: a report case's name, or a recorded simulation's
	 * @return what the Java counts where it departs; empty when nothing departs
	 */
	public static Map<String, Double> counts(String name) {
		Counts counts = COUNTS.get(name);
		return (counts != null) ? counts.byLine() : Map.of();
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
			return CostDepartures.comparable(expected, actual, counts(name));
		}
		ObjectNode copy = (ObjectNode) actual.deepCopy();
		copy.set(cost, CostDepartures.comparable(expected.path(cost), actual.path(cost), counts(name)));
		return copy;
	}

	// a report case whose cluster, k8s, no pod runs on
	private static Map.Entry<String, Counts> emptyCluster(String name) {
		return Map.entry(name, new Counts(Map.of("k8s", 1.0), EMPTY_CLUSTER));
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
