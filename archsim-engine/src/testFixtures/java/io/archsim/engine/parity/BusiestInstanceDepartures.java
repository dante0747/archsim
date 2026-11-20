package io.archsim.engine.parity;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Where the Java departs from the frozen engine on a database of several instances
 * (engine 1.3.0, ADR-0083), and how it is read as the frozen engine's all the same,
 * guided by the frozen engine's output of the same call.
 * <ul>
 * <li>Such a database reports its busiest instance's share of its cores busy
 * ({@code busiestInstanceCpuUtilization}), and its CPU ceiling is where that instance's
 * cores fill, where Python spread its requests' CPU time evenly over the instances that
 * serve them; the Java explains that ceiling among the database's own formulas. Its part
 * reads as Python's once its share lies between the mean over its instances (or below it,
 * where the database refused operations: its busiest instance is the one sent the most
 * work, which counts those it refused) and all their busy cores on one, its ceiling is no
 * higher than Python's but for chance, and Python's capacity, its limit and its ceiling
 * were it served alone follow from the Java's slots and Python's CPU ceiling by the rules
 * that set them; its new figure is then left out, those figures are Python's, and
 * Python's CPU formula stands where Python had it.</li>
 * <li>A finding that gives that ceiling names the busiest instance where Python named the
 * work per request: it reads as Python's once it is Python's but for that sentence and
 * received the same; its evidence's capacity is then Python's.</li>
 * </ul>
 */
public final class BusiestInstanceDepartures {

	private static final String BUSIEST = "busiestInstanceCpuUtilization";

	private static final String CPU_CAPACITY = "cpuCapacityRps";

	private static final String CAPACITY = "capacityRps";

	private static final String LIMIT = "capacityLimit";

	private static final String SERVICE_ONLY = "serviceOnlyCapacityRps";

	private static final String SUMMARY = "summary";

	private static final String EXPLAIN = "explain";

	private static final String CPU_FORMULA = "cpu_capacity";

	private static final String DETAIL = "detail";

	private static final String EVIDENCE = "evidence";

	/** The Java's sentence on a database's CPU ceiling. */
	private static final Pattern JAVA_CEILING = Pattern.compile("Its busiest instance's CPU cores, [0-9]+% busy, "
			+ "can do about [0-9,.]+ req/s; it received (?<received>[0-9,.]+ req/s)\\.");

	/** Python's. */
	private static final Pattern PYTHON_CEILING = Pattern.compile("Its CPU cores can do about [0-9,.]+ req/s at "
			+ "[0-9.]+ ms of work per request; it received (?<received>[0-9,.]+ req/s)\\.");

	/** How far a share rounded to 4 decimals may be from another. */
	private static final double SHARE_ROUNDING = 1e-4;

	/**
	 * How far one rate may be from another computed alike, relatively: floating-point
	 * noise.
	 */
	private static final double RATE_ROUNDING = 1e-9;

	/**
	 * How far the slots' ceiling the formula gives, from a hold time rounded to 4
	 * decimals, may be from the one the capacity uses, relatively.
	 */
	private static final double HOLD_ROUNDING = 1e-5;

	/** How far above Python's even spread the Java's ceiling may come by chance. */
	private static final double CHANCE = 1.02;

	/** A CPU ceiling within this share above the slots' is named as the limit. */
	private static final double NEAR = 1.03;

	private BusiestInstanceDepartures() {
	}

	/**
	 * The Java's output with its departures on databases of several instances read as the
	 * frozen engine's.
	 * @param java the Java's output, which is not changed
	 * @param python the frozen engine's output of the same call
	 * @return a copy of the Java's output
	 */
	public static JsonNode asRecorded(JsonNode java, JsonNode python) {
		JsonNode copy = java.deepCopy();
		JsonNode nodes = copy.path("nodes");
		for (int index = 0; index < Math.min(nodes.size(), python.path("nodes").size()); index++) {
			putPart((ObjectNode) nodes.get(index), python.path("nodes").get(index));
		}
		JsonNode findings = copy.path("findings");
		for (int index = 0; index < Math.min(findings.size(), python.path("findings").size()); index++) {
			putFinding((ObjectNode) findings.get(index), python.path("findings").get(index));
		}
		return copy;
	}

	private static void putPart(ObjectNode java, JsonNode python) {
		JsonNode ours = java.path(SUMMARY);
		JsonNode theirs = python.path(SUMMARY);
		int formula = indexOf(java.path(EXPLAIN), CPU_FORMULA);
		int pythons = indexOf(python.path(EXPLAIN), CPU_FORMULA);
		boolean departs = ours.has(BUSIEST) && formula >= 0 && pythons >= 0;
		if (!departs || !isPlausible(java, theirs) || !followsFromSlots(java, theirs)) {
			return;
		}
		ObjectNode summary = (ObjectNode) ours;
		summary.remove(BUSIEST);
		for (String name : new String[] { CAPACITY, LIMIT, CPU_CAPACITY, SERVICE_ONLY }) {
			summary.set(name, theirs.get(name));
		}
		ArrayNode explain = (ArrayNode) java.get(EXPLAIN);
		explain.remove(formula);
		explain.insert(Math.min(pythons, explain.size()), python.path(EXPLAIN).get(pythons));
	}

	// its share no higher than all their busy cores on one, nor lower than their mean
	// unless it refused operations: its busiest instance is the one sent the most work,
	// those it refused counted, which its cores did not run; its ceiling no higher than
	// Python's but for chance
	private static boolean isPlausible(JsonNode java, JsonNode theirs) {
		JsonNode summary = java.path(SUMMARY);
		double busiest = summary.path(BUSIEST).asDouble();
		double mean = summary.path("cpuCoreUtilization").asDouble(0);
		boolean refused = summary.path("rejectedTotal").asLong(0) > 0;
		boolean aboveMean = refused || busiest >= mean - SHARE_ROUNDING;
		boolean share = aboveMean && busiest <= Math.min(1, instances(java) * mean) + SHARE_ROUNDING;
		return share && summary.path(CPU_CAPACITY).asDouble() <= theirs.path(CPU_CAPACITY).asDouble(0) * CHANCE;
	}

	// its instances: its shards' primaries and replicas
	private static double instances(JsonNode java) {
		JsonNode config = java.path("config");
		double partitions = java.path(SUMMARY).path("partitions").asDouble(config.path("shards").asDouble(1));
		return partitions * (1 + config.path("readReplicas").asDouble(0));
	}

	// Python's capacity and limit: the lower of the Java's slots and Python's CPU
	// ceiling, naming the CPU within 3% above the slots'; and its ceiling served alone
	private static boolean followsFromSlots(JsonNode java, JsonNode theirs) {
		int slotFormula = indexOf(java.path(EXPLAIN), "slot_capacity");
		double cpu = theirs.path(CPU_CAPACITY).asDouble();
		if (slotFormula < 0) {
			return theirs.path(CAPACITY).asDouble() == cpu;
		}
		double slots = java.path(EXPLAIN).get(slotFormula).path("result").asDouble();
		boolean cpuLimits = cpu <= slots * NEAR;
		double capacity = cpuLimits ? cpu : slots;
		String limit = cpuLimits ? "cpu" : "slots";
		boolean capacityFollows = Math.abs(theirs.path(CAPACITY).asDouble() - capacity) <= capacity * HOLD_ROUNDING
				&& limit.equals(theirs.path(LIMIT).stringValue(""));
		return capacityFollows && serviceOnlyFollows(java.path(SUMMARY), theirs);
	}

	// the Java's ceiling were it served alone is its slots' at the service time when that
	// is below its CPU ceiling, and Python's is then the lower of it and Python's CPU
	// ceiling; else Python's lies between the lower CPU ceiling and Python's
	private static boolean serviceOnlyFollows(JsonNode ours, JsonNode theirs) {
		double javaAlone = ours.path(SERVICE_ONLY).asDouble();
		double javaCpu = ours.path(CPU_CAPACITY).asDouble();
		double pythonCpu = theirs.path(CPU_CAPACITY).asDouble();
		double pythonAlone = theirs.path(SERVICE_ONLY).asDouble();
		if (javaAlone < javaCpu) {
			return isNear(pythonAlone, Math.min(javaAlone, pythonCpu));
		}
		double lower = Math.min(javaCpu, pythonCpu);
		return pythonAlone >= lower * (1 - RATE_ROUNDING) && pythonAlone <= pythonCpu * (1 + RATE_ROUNDING);
	}

	private static boolean isNear(double value, double expected) {
		return Math.abs(value - expected) <= Math.abs(expected) * RATE_ROUNDING + RATE_ROUNDING;
	}

	private static int indexOf(JsonNode explain, String id) {
		for (int index = 0; index < explain.size(); index++) {
			if (id.equals(explain.get(index).path("id").stringValue(""))) {
				return index;
			}
		}
		return -1;
	}

	private static void putFinding(ObjectNode java, JsonNode python) {
		String ours = java.path(DETAIL).stringValue("");
		String theirs = python.path(DETAIL).stringValue("");
		Matcher javaCeiling = JAVA_CEILING.matcher(ours);
		Matcher pythonCeiling = PYTHON_CEILING.matcher(theirs);
		if (!javaCeiling.find() || !pythonCeiling.find()
				|| !javaCeiling.group("received").equals(pythonCeiling.group("received"))) {
			return;
		}
		String read = ours.substring(0, javaCeiling.start()) + pythonCeiling.group()
				+ ours.substring(javaCeiling.end());
		if (read.equals(theirs)) {
			java.put(DETAIL, theirs);
			if (java.path(EVIDENCE).has(CAPACITY)) {
				((ObjectNode) java.get(EVIDENCE)).set(CAPACITY, python.path(EVIDENCE).get(CAPACITY));
			}
		}
	}

}
