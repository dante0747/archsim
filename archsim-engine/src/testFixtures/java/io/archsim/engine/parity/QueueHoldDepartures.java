package io.archsim.engine.parity;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.explain.Formula;
import io.archsim.engine.explain.QueueingFormulas;
import io.archsim.engine.report.ReportNode;
import io.archsim.engine.text.NumberText;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.queue.MessageQueueSpec;

/**
 * Where the Java departs from the frozen engine on a queue (engine 1.6.2, ADR-0117), and
 * how it is read as the frozen engine's all the same, guided by what the frozen engine
 * recorded of the same call: its report, or its figures alone. A queue's mean holding
 * time is its consumers' alone: a delivery holds a consumer slot from its start to its
 * end, and a publish holds none, where Python also counted each publish from its start to
 * its acknowledgement. The capacity its slots give, the limit that names it, the formulas
 * that use the holding time and the sentence of a finding on what it can sustain follow
 * from it. They read as Python's once Python's holding time is, to the report's rounding,
 * the mean of the deliveries that ended in the window, processed or failed, each held for
 * the Java's holding time, and of the publishes acknowledged in it, each held for the
 * Java's mean publish time and the time the memory alarm blocked it: the mean block of
 * each of the window's buckets, weighted by the publishes acknowledged in it. A bucket's
 * block is that of the publishes released in it, which may be acknowledged in the next,
 * so where the alarm blocked publishers the deliveries' holding time Python's implies may
 * lie within 1% of the Java's. Python's holding time, capacity and limit, its formulas
 * that use the holding time and its sentence then stand where Python had them.
 */
public final class QueueHoldDepartures {

	/** The formulas that use a part's holding time. */
	private static final Set<String> HOLDING = Set.of("slot_capacity", "utilization", "mmc_wait", "drain_rate");

	/** A finding's sentence on what a part can sustain at its holding time. */
	private static final Pattern SUSTAINS = Pattern
		.compile("At the observed [\\d.,]+ ms per request it can sustain about [^;]+;");

	/** How far apart two figures the report rounds to 4 decimals may lie and agree. */
	private static final double ROUNDING = 2e-4;

	/**
	 * How far, as a share of the Java's, the deliveries' holding time Python's implies
	 * may lie from it where the memory alarm blocked publishers: the series give each
	 * block to the bucket its publish was released in, not always the one it was
	 * acknowledged in (0.62% at most over the simulation baselines' blocked runs).
	 */
	private static final double BLOCK_PLACEMENT = 0.01;

	/** A queue's consumer throughput, its one own formula that uses its holding time. */
	private static final String DRAIN_RATE = "drain_rate";

	/** Where a queue's consumer throughput divides by its holding time. */
	private static final String DIVIDED = " × 1000 ÷ ";

	private static final String NODES = "nodes";

	private static final String SUMMARY = "summary";

	private static final String EXPLAIN = "explain";

	private static final String SERIES = "timeseries";

	private static final String HOLD = "meanHoldMs";

	private static final String CAPACITY = "capacityRps";

	private static final String LIMIT = "capacityLimit";

	private static final String EVIDENCE = "evidence";

	/** The figures that follow from a queue's holding time in its summary. */
	private static final List<String> FIGURES = List.of(HOLD, CAPACITY, LIMIT);

	private QueueHoldDepartures() {
	}

	/**
	 * The Java's report with its queues' holding times read as the frozen engine's.
	 * @param java the Java's report, which is not changed
	 * @param python what the frozen engine recorded of the same call: its report, or a
	 * report of its figures whose parts, in the Java's order, name no id
	 * @return a copy of the Java's report
	 */
	public static JsonNode asRecorded(JsonNode java, JsonNode python) {
		JsonNode copy = java.deepCopy();
		JsonNode nodes = copy.path(NODES);
		for (int index = 0; index < nodes.size(); index++) {
			ObjectNode node = (ObjectNode) nodes.get(index);
			JsonNode recorded = python.path(NODES).path(index);
			if (isQueue(node) && isSamePart(node, recorded) && isPythonsMix(copy, node, recorded.path(SUMMARY))) {
				ObjectNode summary = (ObjectNode) node.path(SUMMARY);
				for (String figure : FIGURES) {
					if (recorded.path(SUMMARY).has(figure)) {
						summary.set(figure, recorded.path(SUMMARY).get(figure));
					}
				}
				node.set(EXPLAIN, explain(node.path(EXPLAIN), recorded.path(EXPLAIN)));
				putSentences(copy, python, node.path("id").stringValue(""));
			}
		}
		return copy;
	}

	/**
	 * A queue's formulas as the frozen engine wrote them, for a run whose baselines keep
	 * its figures and not its formulas: the queueing formulas of its slots, written again
	 * from the Java's figures with the frozen engine's in their place, then its own, its
	 * consumer throughput at the frozen engine's holding time.
	 * @param node the queue in the Java's report
	 * @param figures the frozen engine's figures of it
	 * @param windowS the run's measured window, in seconds
	 * @return its formulas
	 */
	public static ArrayNode frozenFormulas(JsonNode node, JsonNode figures, double windowS) {
		ObjectNode frozen = (ObjectNode) node.deepCopy();
		ObjectNode summary = (ObjectNode) frozen.path(SUMMARY);
		figures.properties().forEach((figure) -> summary.set(figure.getKey(), figure.getValue()));
		ArrayNode formulas = JsonNodeFactory.instance.arrayNode();
		Set<String> queueing = new HashSet<>();
		for (Formula formula : QueueingFormulas.ofSlots(ReportNode.read(frozen), windowS)) {
			formulas.add(RecordCodec.write(formula));
			queueing.add(formula.id());
		}
		int slots = node.path(SUMMARY).path("slots").asInt();
		for (JsonNode formula : node.path(EXPLAIN)) {
			String id = formula.path("id").stringValue("");
			if (DRAIN_RATE.equals(id)) {
				formulas.add(drainRateAt(formula, slots, figures.path(HOLD).asDouble()));
			}
			else if (!queueing.contains(id) && !HOLDING.contains(id)) {
				formulas.add(formula);
			}
		}
		return formulas;
	}

	private static boolean isQueue(JsonNode node) {
		return MessageQueueSpec.TYPE.equals(node.path("type").stringValue(""));
	}

	// the frozen engine's part at the same place, which names no part where it is a
	// baseline's figures
	private static boolean isSamePart(JsonNode java, JsonNode python) {
		return python.isObject() && (!python.has("id") || python.path("id").equals(java.path("id")));
	}

	// Python's holding time as the mean of the deliveries' and the publishes' that the
	// Java's figures make, each publish held for its publish time and its block; where
	// the alarm blocked publishers, the deliveries' within the blocks' placement
	private static boolean isPythonsMix(JsonNode report, JsonNode node, JsonNode python) {
		JsonNode java = node.path(SUMMARY);
		double publishes = java.path("completed").asDouble();
		JsonNode counters = java.path("counters");
		double deliveries = counters.path("processed").asDouble() + counters.path("failedDeliveries").asDouble();
		double all = publishes + deliveries;
		if (all == 0 || !python.path(HOLD).isNumber()) {
			return false;
		}
		double block = meanBlock(report, node);
		double published = publishes * (java.path("meanServiceMs").asDouble() + block);
		double delivered = deliveries * java.path(HOLD).asDouble();
		double placement = (block > 0) ? BLOCK_PLACEMENT * delivered / all : 0;
		return Math.abs((published + delivered) / all - python.path(HOLD).asDouble()) <= ROUNDING + placement;
	}

	// the mean time the memory alarm blocked the publishes acknowledged in the window:
	// each of the window's buckets' mean block, weighted by the publishes acknowledged in
	// it; 0 where it blocked none
	private static double meanBlock(JsonNode report, JsonNode node) {
		JsonNode blocked = node.path(SERIES).path("publishBlockedMs");
		JsonNode acknowledged = node.path(SERIES).path("completedRps");
		JsonNode times = report.path(SERIES).path("t");
		double start = report.path("window").path("warmupS").asDouble();
		double end = report.path("window").path("durationS").asDouble();
		double publishes = 0;
		double block = 0;
		for (int bucket = 0; bucket < blocked.size(); bucket++) {
			double time = times.path(bucket).asDouble(-1);
			if (start <= time && time < end) {
				publishes += acknowledged.path(bucket).asDouble();
				block += acknowledged.path(bucket).asDouble() * blocked.path(bucket).asDouble();
			}
		}
		return (publishes > 0) ? block / publishes : 0;
	}

	// Python's formulas that use the holding time where Python had them, and the Java's
	// others in the Java's order
	private static ArrayNode explain(JsonNode java, JsonNode python) {
		Deque<JsonNode> others = new ArrayDeque<>();
		for (JsonNode formula : java) {
			if (!usesHolding(formula)) {
				others.add(formula);
			}
		}
		ArrayNode read = JsonNodeFactory.instance.arrayNode();
		for (JsonNode formula : python) {
			if (usesHolding(formula)) {
				read.add(formula.deepCopy());
			}
			else if (!others.isEmpty()) {
				read.add(others.poll());
			}
		}
		others.forEach(read::add);
		return read;
	}

	private static boolean usesHolding(JsonNode formula) {
		return HOLDING.contains(formula.path("id").stringValue(""));
	}

	// a queue's consumer throughput written again at another holding time: its slots
	// over that time
	private static JsonNode drainRateAt(JsonNode formula, int slots, double holdMs) {
		Formula java = RecordCodec.read(formula, Formula.class);
		String substituted = slots + DIVIDED + NumberText.formula(holdMs, 2) + " ms";
		Formula frozen = new Formula(java.id(), java.title(), java.formula(), substituted, null, java.unit(),
				java.note(), java.source(), java.metric());
		return RecordCodec.write(frozen.withResult(slots * 1000.0 / holdMs, java.unit()));
	}

	// the queue's findings with Python's sentence on what it can sustain, and Python's
	// capacity in their evidence
	private static void putSentences(JsonNode java, JsonNode python, String queueId) {
		JsonNode findings = java.path("findings");
		for (int index = 0; index < findings.size(); index++) {
			ObjectNode finding = (ObjectNode) findings.get(index);
			JsonNode recorded = python.path("findings").path(index);
			Matcher javas = SUSTAINS.matcher(finding.path("detail").stringValue(""));
			Matcher pythons = SUSTAINS.matcher(recorded.path("detail").stringValue(""));
			if (queueId.equals(finding.path("nodeId").stringValue("")) && javas.find() && pythons.find()) {
				finding.put("detail", javas.replaceFirst(Matcher.quoteReplacement(pythons.group())));
				if (finding.path(EVIDENCE).isObject() && recorded.path(EVIDENCE).has(CAPACITY)) {
					((ObjectNode) finding.path(EVIDENCE)).set(CAPACITY, recorded.path(EVIDENCE).path(CAPACITY));
				}
			}
		}
	}

}
