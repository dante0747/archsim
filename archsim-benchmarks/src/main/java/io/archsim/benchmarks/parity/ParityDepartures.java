package io.archsim.benchmarks.parity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * The parity page's words on the departures: the runs that do not replay the frozen
 * engine exactly, by the departure the baselines keep too little of to read as the frozen
 * engine's, and what the parity run reads as the frozen engine's where the engine has
 * changed on purpose since.
 */
final class ParityDepartures {

	/** Where an ADR is, from the page. */
	private static final String ADRS = "../adr/";

	/** Counting a cache's refused lookups, engine 1.2.1. */
	private static final String REFUSED_LOOKUPS_ADR = adr("0082", "count-a-caches-refused-lookups-in-its-hit-ratio");

	/** The departures the baselines keep too little of to read, by their names. */
	private static final Map<String, String> UNREAD = Map.of("refusedLookups",
			"a cache refused lookups, which its hit ratio by second and its formula count (engine 1.2.1, "
					+ REFUSED_LOOKUPS_ADR + "), and the baselines keep neither of the frozen engine's");

	/** What the parity run reads as the frozen engine's. */
	static final String READ = "- The departures: where the engine has changed on purpose since 0.16.0, each Java "
			+ "run is read as the frozen engine's from what the baselines keep of its run and from the frozen "
			+ "engine's report of the template's run in its golden file, as the golden reports are read ("
			+ adr("0096", "read-the-simulation-paritys-runs-as-the-frozen-engines-where-the-engine-changed-on-purpose")
			+ "): its design at the frozen engine's schema, its vCPUs as whole cores ("
			+ adr("0091", "count-a-vcpu-as-a-hyperthread-unless-the-design-or-its-instance-family-says-otherwise")
			+ ") and an API's price of 0, which the upgrade to schema 1.9 unsets, put back ("
			+ adr("0081", "price-an-unset-api-at-its-preset-or-leave-it-out-of-the-month")
			+ "); its summary without the message figures ("
			+ adr("0084", "judge-asynchronous-designs-by-their-messages-too") + "); a queue without its idle "
			+ "consumers (" + adr("0085", "size-a-kafka-broker-as-measured-and-name-idle-consumers") + "); a "
			+ "database of several instances with the frozen engine's CPU ceiling, capacity and the formula it "
			+ "wrote from the part's work per request ("
			+ adr("0083", "name-each-utilisation-and-compare-like-with-like") + "); and a cache that refused "
			+ "lookups with the frozen engine's hit ratio (" + REFUSED_LOOKUPS_ADR + "). Each is read only where "
			+ "it follows from the run's own figures, and the result digest is taken over the result so read: it "
			+ "is the frozen engine's only where the rest of the result, every series and formula included, is "
			+ "its.";

	private ParityDepartures() {
	}

	/**
	 * The runs that do not replay the frozen engine exactly, and why.
	 * @param parity {@code simulation-parity.json}
	 * @return a line on them; none when every run replays exactly
	 */
	static List<String> notReplayed(JsonNode parity) {
		long notExact = parity.get("runs").longValue() - parity.get("exactReplays").longValue();
		if (notExact == 0) {
			return List.of();
		}
		long departing = parity.path("departingRuns").longValue();
		long otherwise = notExact - departing;
		String only = (departing == 0)
				? "none departs only where the baselines keep too little to read them as the frozen engine's"
				: ParityNumbers.count(departing) + " " + depart(departing) + " only where the baselines keep too "
						+ "little to read them as the frozen engine's, every figure they keep its but the result "
						+ "digest: " + named(parity.path("departures"));
		String rest = (otherwise == 0) ? "none departs otherwise"
				: ParityNumbers.count(otherwise) + " " + depart(otherwise) + " otherwise";
		String runs = (notExact == 1) ? "run that does" : "runs that do";
		return List.of("- Of the " + ParityNumbers.count(notExact) + " " + runs + " not replay it exactly, " + only
				+ "; " + rest + ".");
	}

	// each departure in words, with its runs
	private static String named(JsonNode departures) {
		List<String> named = new ArrayList<>();
		departures.properties()
			.forEach((departure) -> named
				.add("in " + ParityNumbers.count(departure.getValue().longValue()) + ", " + words(departure.getKey())));
		return String.join("; ", named);
	}

	private static String words(String departure) {
		String words = UNREAD.get(departure);
		if (words == null) {
			throw new IllegalStateException("the parity page has no words for the departure " + departure);
		}
		return words;
	}

	private static String depart(long runs) {
		return (runs == 1) ? "departs" : "depart";
	}

	private static String adr(String number, String slug) {
		return "[ADR-" + number + "](" + ADRS + number + "-" + slug + ".md)";
	}

}
