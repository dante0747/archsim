package io.archsim.engine.parity;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.text.PythonFormat;

/**
 * Where the Java departs from the frozen engine on a cache's lookups and the advice on a
 * cache that fails (engine 1.2.1, ADR-0082), and how it is read as the frozen engine's
 * all the same, guided by the frozen engine's output of the same call.
 * <ul>
 * <li>A cache counts the lookups it refused ({@code refusedLookups}) and its hit ratio
 * counts them as reads it did not serve, where Python counted neither. Such a part reads
 * as Python's once its hit ratio is shown to be hits over hits, misses and refused
 * lookups and Python's over hits and misses; its series no higher than Python's in any
 * bucket, and 0 where Python's is empty (a bucket whose every lookup it refused: the
 * report has no counts by bucket, so the check is one-sided); and its hit ratio formula
 * its own counters and ratio. Its counter is then left out, and its hit ratio, series and
 * formula are Python's.</li>
 * <li>Its finding then names the refused reads: with that clause left out and Python's
 * hit ratio in it, the Java's title and detail must be Python's, and its misses a second
 * Python's; its evidence is then Python's.</li>
 * <li>The advice after a cache's outage asks only for what its callers do not do yet,
 * where Python always asked for both a fallback on a failed lookup and a read on a miss:
 * Python's advice reads as the Java's when the Java's is one of its forms, which
 * {@code CacheFallbackTests} pins.</li>
 * <li>The design review asks a part only for the half of the fallback it lacks, where
 * Python asked for both: Python's advice reads as the Java's when both name the same
 * cache and part.</li>
 * </ul>
 */
public final class CacheDepartures {

	private static final String REFUSED = "refusedLookups";

	private static final String HIT_RATIO = "hitRatio";

	private static final String SUGGESTION = "suggestion";

	private static final String DETAIL = "detail";

	private static final String EVIDENCE = "evidence";

	/**
	 * The advice after a cache's outage, the frozen engine's whatever the callers did.
	 */
	private static final String FALL_BACK = "Treat cache errors as misses and fall back to the database: "
			+ "turn on 'Carry on if this call fails' on the link to the cache and set the link to the database to "
			+ "'Reads only on a cache miss' (check the database can take every read), or run a replicated cache.";

	/** The Java's forms of that advice, asking only for what is missing. */
	private static final Pattern FALL_BACK_FORMS = Pattern.compile("Its callers?, .+, already treats? a failed lookup "
			+ "on .+|Treat cache errors as misses and fall back to the database: (turn on|give) .+, or run a "
			+ "replicated cache\\.");

	/** The review's advice to a part without a fallback, Python's. */
	private static final Pattern REVIEW_ADVICE = Pattern.compile("Turn on 'Carry on if this call fails' on the link to "
			+ "(?<cache>.+) and give (?<part>.+) a link to the data store set to 'Reads only on a cache miss', so "
			+ "reads fall back to the data store \\(check it can take every read\\), or run the cache with "
			+ "replicas\\.");

	/**
	 * The Java's to a part that reads the data store on a miss, or carries on without.
	 */
	private static final Pattern REVIEW_HALF_ADVICE = Pattern.compile("Turn on 'Carry on if this call fails' on "
			+ "the link to (?<cache>.+), so a failed lookup counts as a miss and (?<part>.+) reads .+ instead "
			+ "\\(check it can take every read\\), or run the cache with replicas\\.|Give (?<part2>.+) a link to the "
			+ "data store set to 'Reads only on a cache miss', so reads fall back to the data store when (?<cache2>.+) "
			+ "fails \\(check it can take every read\\), or run the cache with replicas\\.");

	/** A finding's clause on the lookups its cache refused. */
	private static final Pattern REFUSED_CLAUSE = Pattern.compile("; it refused [0-9,]+ reads/s, which .+(?=\\.$)");

	/** A hit ratio as a finding writes it, a whole percent. */
	private static final Pattern PERCENT = Pattern.compile("[0-9]+%");

	/** How far a rounded ratio may be from the one worked out. */
	private static final double ROUNDING = 1e-4;

	private CacheDepartures() {
	}

	/**
	 * The Java's output with its departures on caches read as the frozen engine's.
	 * @param java the Java's output, which is not changed
	 * @param python the frozen engine's output of the same call
	 * @return a copy of the Java's output
	 */
	public static JsonNode asRecorded(JsonNode java, JsonNode python) {
		JsonNode copy = java.deepCopy();
		put(copy, python);
		return copy;
	}

	private static void put(JsonNode java, JsonNode python) {
		if (java instanceof ObjectNode object && python.isObject()) {
			putPart(object, python);
			putFinding(object, python);
			python.propertyNames().forEach((key) -> {
				if (object.has(key)) {
					put(object.get(key), python.get(key));
				}
			});
		}
		else if (java.isArray() && python.isArray()) {
			for (int index = 0; index < Math.min(java.size(), python.size()); index++) {
				put(java.get(index), python.get(index));
			}
		}
	}

	// a report's part that refused lookups: its counter, hit ratio, series and formula
	private static void putPart(ObjectNode java, JsonNode python) {
		JsonNode summary = java.path("summary");
		JsonNode series = java.path("timeseries").path(HIT_RATIO);
		boolean refused = summary.path("counters").path(REFUSED).asLong(0) > 0;
		if (!refused || !refusedLookupsCount(summary, python.path("summary"))
				|| !noHigher(series, python.path("timeseries").path(HIT_RATIO))) {
			return;
		}
		int formula = hitRatioFormula(java.path("explain"));
		boolean pythons = formula >= 0
				&& "hit_ratio".equals(python.path("explain").path(formula).path("id").stringValue(""));
		if (!pythons || !isOwnFormula(java.path("explain").get(formula), summary)) {
			return;
		}
		((ObjectNode) summary.get("counters")).remove(REFUSED);
		((ObjectNode) summary).set(HIT_RATIO, python.path("summary").get(HIT_RATIO));
		if (series.isArray()) {
			((ObjectNode) java.get("timeseries")).set(HIT_RATIO, python.path("timeseries").get(HIT_RATIO));
		}
		((ArrayNode) java.get("explain")).set(formula, python.path("explain").get(formula));
	}

	// the Java's hit ratio is hits over hits, misses and refused lookups, Python's over
	// hits and misses
	private static boolean refusedLookupsCount(JsonNode java, JsonNode python) {
		JsonNode counters = java.path("counters");
		double hits = counters.path("hits").asDouble(0);
		double misses = counters.path("misses").asDouble(0);
		double refused = counters.path(REFUSED).asDouble(0);
		boolean javas = Math.abs(java.path(HIT_RATIO).asDouble(-1) - hits / (hits + misses + refused)) < ROUNDING;
		boolean pythons = Math.abs(python.path(HIT_RATIO).asDouble(-1) - hits / (hits + misses)) < ROUNDING;
		return javas && pythons;
	}

	private static int hitRatioFormula(JsonNode explain) {
		for (int index = 0; index < explain.size(); index++) {
			if ("hit_ratio".equals(explain.get(index).path("id").stringValue(""))) {
				return index;
			}
		}
		return -1;
	}

	// the formula is worked out from the part's own counters, to its own hit ratio
	private static boolean isOwnFormula(JsonNode formula, JsonNode summary) {
		JsonNode counters = summary.path("counters");
		String hits = PythonFormat.format(counters.path("hits").asLong(0), ",");
		String substituted = hits + " ÷ (" + hits + " + " + PythonFormat.format(counters.path("misses").asLong(0), ",")
				+ " + " + PythonFormat.format(counters.path(REFUSED).asLong(0), ",") + ")";
		return substituted.equals(formula.path("substituted").stringValue(""))
				&& Math.abs(formula.path("result").asDouble(-1) - summary.path(HIT_RATIO).asDouble()) < ROUNDING;
	}

	// each of the Java's buckets no higher than Python's; 0 where Python's is empty, a
	// bucket whose every lookup was refused
	private static boolean noHigher(JsonNode java, JsonNode python) {
		if (java.size() != python.size()) {
			return false;
		}
		for (int index = 0; index < java.size(); index++) {
			if (!bucketNoHigher(java.get(index), python.get(index))) {
				return false;
			}
		}
		return true;
	}

	private static boolean bucketNoHigher(JsonNode ours, JsonNode theirs) {
		if (theirs.isNull()) {
			return ours.isNull() || ours.asDouble() == 0;
		}
		return !ours.isNull() && ours.asDouble() <= theirs.asDouble() + ROUNDING;
	}

	// a run's cache finding that names refused reads, and the advice on a failing cache
	private static void putFinding(ObjectNode java, JsonNode python) {
		String kind = java.path("kind").stringValue("");
		if ("cache".equals(kind) && java.path(EVIDENCE).has("refusedLookupRps") && sameButRefused(java, python)) {
			java.set("title", python.path("title"));
			java.set(DETAIL, python.path(DETAIL));
			java.set(EVIDENCE, python.path(EVIDENCE));
		}
		String ours = java.path(SUGGESTION).stringValue("");
		String theirs = python.path(SUGGESTION).stringValue("");
		if ("fault_impact".equals(kind) && FALL_BACK.equals(theirs) && FALL_BACK_FORMS.matcher(ours).matches()) {
			java.put(SUGGESTION, theirs);
		}
		if ("cache-dependency".equals(java.path("rule").stringValue("")) && sameParts(ours, theirs)) {
			java.put(SUGGESTION, theirs);
		}
	}

	// the cache finding, its refused clause left out and its hit ratio Python's, is
	// Python's, its misses a second Python's
	private static boolean sameButRefused(JsonNode java, JsonNode python) {
		String percent = python.path("title").stringValue("").replaceAll(".* ", "");
		String title = PERCENT.matcher(java.path("title").stringValue("")).replaceAll(percent);
		String detail = REFUSED_CLAUSE.matcher(PERCENT.matcher(java.path(DETAIL).stringValue("")).replaceFirst(percent))
			.replaceAll("");
		boolean sameMisses = java.path(EVIDENCE).path("missRps").equals(python.path(EVIDENCE).path("missRps"));
		return title.equals(python.path("title").stringValue("")) && detail.equals(python.path(DETAIL).stringValue(""))
				&& sameMisses;
	}

	// the review's advice to a part that lacks half of the fallback, and Python's, name
	// one cache and one part
	private static boolean sameParts(String java, String python) {
		Matcher ours = REVIEW_HALF_ADVICE.matcher(java);
		Matcher theirs = REVIEW_ADVICE.matcher(python);
		if (!ours.matches() || !theirs.matches()) {
			return false;
		}
		String cache = (ours.group("cache") != null) ? ours.group("cache") : ours.group("cache2");
		String part = (ours.group("part") != null) ? ours.group("part") : ours.group("part2");
		return theirs.group("cache").equals(cache) && theirs.group("part").equals(part);
	}

}
