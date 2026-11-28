package io.archsim.engine.parity;

import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.explain.Formula;
import io.archsim.engine.text.NumberText;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.queue.MessageQueueSpec;
import io.archsim.parity.SchemaDepartures;

/**
 * Where a Java run of a template case departs from the frozen engine's run of the same
 * seed by a deliberate engine change, and how it is read as the frozen engine's all the
 * same, as the golden reports are read ({@link ReportComparison}), from what the frozen
 * engine recorded of it: the simulation baselines' figures of the run, and the report of
 * a run of the template's design that its golden file keeps.
 * <ul>
 * <li>Its summary's message figures are left out (engine 1.4.0, ADR-0084:
 * {@link MessageDepartures}), and so are a queue's idle consumers (engine 1.5.0,
 * ADR-0085: {@link IdleConsumerDepartures}).</li>
 * <li>A database of several instances has the frozen engine's CPU ceiling, capacity and
 * limit (engine 1.3.0, ADR-0083: {@link BusiestInstanceDepartures}), and the formula the
 * frozen engine wrote for that ceiling, which the baselines do not keep: its cores over
 * its instances at its work per request, from the frozen engine's figures.</li>
 * <li>A cache that refused lookups has the frozen engine's hit ratio, which left them out
 * (engine 1.2.1, ADR-0082: {@link CacheDepartures#hitRatiosAsRecorded}).</li>
 * <li>A queue has the frozen engine's holding time, which counted its publishes too, and
 * the capacity, the queueing formulas and the consumer throughput that follow from it,
 * written again from the frozen engine's figures (engine 1.6.2, ADR-0117:
 * {@link QueueHoldDepartures}).</li>
 * <li>Its design and parts' settings are as the frozen engine wrote them at schema 1.7
 * ({@link SchemaDepartures}), an API's price of 0, which the upgrade to 1.9 unsets
 * (ADR-0081), put back where the frozen engine's report of the design has it.</li>
 * </ul>
 * One departure cannot be read: a cache that refused lookups counts them in its hit ratio
 * by second and in its hit ratio's formula, and the baselines keep neither of the frozen
 * engine's, so the run's result still departs ({@link #REFUSED_LOOKUPS}). The report that
 * guides the reading has no findings, which are neither figures nor part of the result's
 * digest: findings are left as the departures leave them.
 */
final class RunDepartures {

	/** A cache refused lookups (engine 1.2.1, ADR-0082). */
	static final String REFUSED_LOOKUPS = "refusedLookups";

	/** The formula of a CPU ceiling, as the frozen engine wrote it for every part. */
	private static final String CPU_CEILING = "CPU ceiling = cores × instances × 1000 ÷ CPU time per request";

	private static final String CPU_FORMULA = "cpu_capacity";

	private static final String NODES = "nodes";

	private static final String SUMMARY = "summary";

	private static final String EXPLAIN = "explain";

	private static final String SERVICE_MS = "meanServiceMs";

	private static final String CPU_CAPACITY = "cpuCapacityRps";

	private RunDepartures() {
	}

	/**
	 * The Java's report of a run read as the frozen engine's.
	 * @param java the Java's report, which is not changed
	 * @param python the baselines' figures of the same run
	 * @param frozen the frozen engine's report of a run of the same design, or a missing
	 * node where there is none
	 * @return a copy of the Java's report
	 */
	static JsonNode asRecorded(JsonNode java, JsonNode python, JsonNode frozen) {
		JsonNode kept = keptReport(java, python.path(NODES), java.at("/window/measuredS").asDouble());
		JsonNode read = CacheDepartures.hitRatiosAsRecorded(java, python.path(NODES));
		read = QueueHoldDepartures.asRecorded(BusiestInstanceDepartures.asRecorded(read, kept), kept);
		read = IdleConsumerDepartures.asRecorded(MessageDepartures.asRecorded(read), kept);
		return SchemaDepartures.asRecorded(read, frozen);
	}

	/**
	 * The departures a Java run shows that the baselines keep too little of to read as
	 * the frozen engine's.
	 * @param java the Java's report of the run
	 * @return the departures, by name; none when the run can be read whole
	 */
	static Set<String> unread(JsonNode java) {
		boolean refused = java.path(NODES)
			.valueStream()
			.anyMatch((node) -> node.path(SUMMARY).path("counters").path(REFUSED_LOOKUPS).asLong(0) > 0);
		return refused ? Set.of(REFUSED_LOOKUPS) : Set.of();
	}

	// the frozen engine's report of the run as far as the baselines keep it: each part's
	// summary values, in the order of the Java's parts, and its formulas, the Java's but
	// for those the frozen engine wrote from those values: a CPU ceiling's, and a queue's
	// that use its holding time
	private static JsonNode keptReport(JsonNode java, JsonNode python, double windowS) {
		ObjectNode kept = JsonNodeFactory.instance.objectNode();
		ArrayNode nodes = kept.putArray(NODES);
		for (JsonNode node : java.path(NODES)) {
			JsonNode values = python.path(node.path("id").stringValue(""));
			ObjectNode part = nodes.addObject();
			part.set(SUMMARY, values.isObject() ? values : JsonNodeFactory.instance.objectNode());
			part.set(EXPLAIN,
					isQueueWithHoldingTime(node, values) ? QueueHoldDepartures.frozenFormulas(node, values, windowS)
							: frozenFormulas(node.path(EXPLAIN), values));
		}
		return kept;
	}

	// a queue whose holding time the baselines keep
	private static boolean isQueueWithHoldingTime(JsonNode node, JsonNode values) {
		return MessageQueueSpec.TYPE.equals(node.path("type").stringValue("")) && values.path("meanHoldMs").isNumber();
	}

	// a part's formulas with the frozen engine's CPU ceiling's, or none where its figures
	// give none
	private static ArrayNode frozenFormulas(JsonNode formulas, JsonNode values) {
		ArrayNode frozen = JsonNodeFactory.instance.arrayNode();
		for (JsonNode formula : formulas) {
			if (!CPU_FORMULA.equals(formula.path("id").stringValue(""))) {
				frozen.add(formula);
			}
			else if (givesCpuCeiling(values)) {
				frozen.add(RecordCodec.write(cpuCeiling(values)));
			}
		}
		return frozen;
	}

	// the frozen engine's formula of a CPU ceiling, from its work per request
	private static Formula cpuCeiling(JsonNode values) {
		String workPerRequest = NumberText.formula(values.get(SERVICE_MS).doubleValue(), 2);
		return Formula
			.of(CPU_FORMULA, "Capacity from CPU", CPU_CEILING,
					"from the CPU spec and " + workPerRequest + " ms of work per request")
			.withResult(values.get(CPU_CAPACITY).doubleValue(), "req/s")
			.withMetric("cpuUtilization");
	}

	// the figures a CPU ceiling's formula is written from: a work per request and a
	// ceiling, neither 0
	private static boolean givesCpuCeiling(JsonNode values) {
		JsonNode serviceMs = values.path(SERVICE_MS);
		JsonNode ceiling = values.path(CPU_CAPACITY);
		return serviceMs.isNumber() && serviceMs.doubleValue() != 0 && ceiling.isNumber() && ceiling.doubleValue() != 0;
	}

}
