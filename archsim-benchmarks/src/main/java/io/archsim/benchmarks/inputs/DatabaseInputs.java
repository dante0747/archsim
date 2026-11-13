package io.archsim.benchmarks.inputs;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.model.text.PythonFormat;
import io.archsim.model.text.PythonText;

/**
 * PostgreSQL's inputs: the time a query spends in the server, the service-to-database
 * link, the WAL flush per commit and the CPU share of a query. On the Kafka stack the
 * queries are the consumer's INSERTs; on chain, the API's behind the gateway. A write
 * holds its row lock for its time in the server and its flush only; the rest of the round
 * trip the service measured (its driver, the network) is the link.
 */
final class DatabaseInputs {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final double LEAST_MEDIAN = 0.01;

	private static final double LEAST_P95 = 0.02;

	private static final int DECIMALS = 4;

	private static final String THREE = ".3f";

	/** The queries the model times, in the order the CPU share's words name them. */
	private static final List<QueryKind> QUERY_KINDS = List.of(new QueryKind("db_read", "readServiceTimeMs", "reads"),
			new QueryKind("db_write", "writeServiceTimeMs", "writes without their flush"));

	private final CommitFlush flushes;

	DatabaseInputs(CommitFlush flushes) {
		this.flushes = flushes;
	}

	/**
	 * PostgreSQL's inputs from the calibration's passes.
	 * @param experiment the experiment
	 * @param passes the calibration's passes
	 * @return the inputs
	 */
	ObjectNode of(Experiment experiment, JsonNode passes) {
		ObjectNode inputs = NODES.objectNode();
		if (experiment.stack().equals("kafka")) {
			KafkaWrites writes = KafkaWrites.of(passes);
			linkAndShare(inputs, kafka(passes, writes, inputs), passes);
			inputs.set("commitFlushMs", this.flushes.of(experiment, values(inputs),
					new CommitFlush.Passes(passes.get("high"), writes.syncPass(), writes.sync(), writes.async()), 0.0));
			return inputs;
		}
		linkAndShare(inputs, queries(experiment, passes, inputs), passes);
		serviceFlush(experiment, passes, values(inputs)).ifPresent((flush) -> inputs.set("commitFlushMs", flush));
		return inputs;
	}

	// the link, the CPU share, and PostgreSQL's executor times beside the model's
	private static void linkAndShare(ObjectNode inputs, Derived derived, JsonNode passes) {
		inputs.set("dbLinkLatencyMs",
				Input.of(derived.leg(),
						"each way: the round trip the service measured less "
								+ "PostgreSQL's time, in two legs that keep its mean and variance ("
								+ ServerTimes.describe(derived.leg()) + ")"));
		if (derived.share() != null) {
			inputs.set("dbCpuShare", derived.share());
		}
		executorTimes(passes, inputs);
	}

	// each input's value
	private static ObjectNode values(ObjectNode inputs) {
		ObjectNode values = NODES.objectNode();
		inputs.properties().forEach((input) -> values.set(input.getKey(), input.getValue().get("value")));
		return values;
	}

	// the top pass's flush, when the service writes
	private Optional<ObjectNode> serviceFlush(Experiment experiment, JsonNode passes, ObjectNode values) {
		JsonNode high = passes.get("high");
		JsonNode client = QueryPasses.client(experiment, high);
		if (!client.path("timings").has("db_write")) {
			return Optional.empty();
		}
		JsonNode write = PassReadings.timing(client, "db_write");
		JsonNode async = PassReadings.timing(QueryPasses.client(experiment, PassReadings.pass(passes, "write_async:1")),
				"db_write_async");
		return Optional.of(this.flushes.of(experiment, values, new CommitFlush.Passes(high, high, write, async),
				experiment.readRatio()));
	}

	// Kafka: the consumer's INSERT without its commit wait
	private static Derived kafka(JsonNode passes, KafkaWrites writes, ObjectNode inputs) {
		JsonNode high = passes.get("high");
		JsonNode low = passes.get("low");
		JsonNode consumer = high.get("consumer");
		JsonNode insert = PassReadings.timing(consumer, "db_insert");
		double waitMedian = writes.sync().get("median").doubleValue() - writes.async().get("median").doubleValue();
		ObjectNode roundTrip = NODES.objectNode()
			.put("median", Math.max(LEAST_MEDIAN, insert.get("median").doubleValue() - waitMedian))
			.put("p95", Math.max(LEAST_P95, insert.get("p95").doubleValue() - waitMedian));
		double consumed = consumer.get("consumed").doubleValue();
		double consumedLow = low.get("consumer").get("consumed").doubleValue();
		Double cpu = PostgresCpu.perQuery(passes, new PostgresCpu.Queries(high, consumed),
				new PostgresCpu.Queries(low, consumedLow));
		ObjectNode server = ServerTimes.serverTime(cpu, roundTrip);
		inputs.set("writeServiceTimeMs",
				Input.of(server,
						ServerTimes.how("INSERT", cpu, "the consumer's INSERT less its commit wait (median "
								+ three(insert.get("median").doubleValue()) + " - " + three(waitMedian) + " ms, p95 "
								+ three(insert.get("p95").doubleValue()) + " - " + three(waitMedian) + " ms)", "")));
		double insertMs = ModelDistributions.mean(server);
		ObjectNode share = PostgresCpu
			.share(passes, new PostgresCpu.QueryCounts(consumedLow, consumed),
					new PostgresCpu.ModelTime(insertMs, "the INSERT, " + three(insertMs) + " ms"))
			.orElse(null);
		return new Derived(ServerTimes.linkLeg(roundTrip, server), share);
	}

	// the service's (or chain's API's) reads and writes; most queries are reads, so they
	// set the link when there are any
	private static Derived queries(Experiment experiment, JsonNode passes, ObjectNode inputs) {
		QueryPasses queries = QueryPasses.of(experiment, passes);
		JsonNode leg = PythonValues.number(0.0);
		JsonNode asyncWrite = queries.asyncWrite();
		if (asyncWrite != null) {
			leg = serverAndLink(inputs, "writeServiceTimeMs",
					new Query("UPDATE with synchronous_commit off", queries.cpuWrites(), asyncWrite, ""));
		}
		JsonNode read = queries.read();
		if (read != null) {
			Double cpuReads = queries.cpuReads();
			String note = Objects.equals(cpuReads, queries.cpuAll()) ? "" : ", the pass's less its UPDATEs'";
			leg = serverAndLink(inputs, "readServiceTimeMs", new Query("SELECT", cpuReads, read, note));
		}
		return new Derived(leg, share(passes, queries, inputs));
	}

	// a query's time in PostgreSQL, set as its input, and the link the rest of its round
	// trip leaves
	private static JsonNode serverAndLink(ObjectNode inputs, String key, Query query) {
		JsonNode roundTrip = query.roundTrip();
		ObjectNode server = ServerTimes.serverTime(query.cpuMs(), roundTrip);
		inputs
			.set(key,
					Input
						.of(server,
								ServerTimes.how(query.what(), query.cpuMs(),
										"its round trip (median " + three(roundTrip.get("median").doubleValue())
												+ ", p95 " + three(roundTrip.get("p95").doubleValue()) + " ms)",
										query.note())));
		return ServerTimes.linkLeg(roundTrip, server);
	}

	// the CPU share against the model's mean time per query over the pass's mix
	private static @Nullable ObjectNode share(JsonNode passes, QueryPasses queries, ObjectNode inputs) {
		List<String> words = new ArrayList<>();
		List<JsonNode> times = new ArrayList<>();
		for (QueryKind kind : QUERY_KINDS) {
			JsonNode timing = queries.timings().path(kind.timing());
			if (!timing.isMissingNode()) {
				double ms = ModelDistributions.mean(inputs.get(kind.input()).get("value"));
				times.add(PythonValues.multiply(timing.get("count"), PythonValues.number(ms)));
				words.add(PythonText.str(timing.get("count")) + " " + kind.words() + " at " + three(ms) + " ms");
			}
		}
		double modelMs = PythonValues.sum(times).doubleValue() / Math.max(queries.high(), 1);
		return PostgresCpu
			.share(passes, new PostgresCpu.QueryCounts(queries.low(), queries.high()),
					new PostgresCpu.ModelTime(modelMs, ServerTimes.mix(words) + ": " + three(modelMs) + " ms"))
			.orElse(null);
	}

	// PostgreSQL's executor time next to the model's, a check
	private static void executorTimes(JsonNode passes, ObjectNode inputs) {
		executorTime(inputs, "readServiceTimeMs", passes.path("high"), "read");
		executorTime(inputs, "writeServiceTimeMs", passes.path("write_async:1"), "write");
		executorTime(inputs, "writeServiceTimeMs", passes.path("high"), "insert");
	}

	private static void executorTime(ObjectNode inputs, String key, JsonNode entry, String kind) {
		Double meanMs = PostgresCpu.statementMs(entry, kind);
		if (inputs.has(key) && meanMs != null) {
			((ObjectNode) inputs.get(key)).putObject("postgres").put("executorMs", PythonRound.round(meanMs, DECIMALS));
		}
	}

	private static String three(double value) {
		return PythonFormat.format(value, THREE);
	}

	/**
	 * Kafka's write passes: the producer's UPDATEs over the keys at its own rate, with
	 * synchronous_commit on and off.
	 *
	 * @param syncPass the pass with synchronous_commit on, whose rate a fit simulates
	 * @param sync the writes' round trip with it on
	 * @param async the writes' round trip with it off
	 */
	private record KafkaWrites(JsonNode syncPass, JsonNode sync, JsonNode async) {

		static KafkaWrites of(JsonNode passes) {
			JsonNode syncPass = PassReadings.pass(passes, "write:1");
			return new KafkaWrites(syncPass, PassReadings.timing(syncPass.get("service"), "db_write"),
					PassReadings.timing(PassReadings.pass(passes, "write_async:1").get("service"), "db_write_async"));
		}

	}

	/**
	 * A kind of query the model times.
	 *
	 * @param timing the service's timing of it
	 * @param input the input of its time in PostgreSQL
	 * @param words its name in a sentence
	 */
	private record QueryKind(String timing, String input, String words) {

	}

	/**
	 * A kind of query and what PostgreSQL spent on it.
	 *
	 * @param what the query, in words
	 * @param cpuMs PostgreSQL's CPU per query, or {@code null} without a usable figure
	 * @param roundTrip the round trip the service measured
	 * @param note what to add after the CPU figure
	 */
	private record Query(String what, @Nullable Double cpuMs, JsonNode roundTrip, String note) {

	}

	/**
	 * The link and the CPU share a stack's queries gave.
	 *
	 * @param leg the link's leg
	 * @param share the CPU share, or {@code null} without a usable figure
	 */
	private record Derived(JsonNode leg, @Nullable ObjectNode share) {

	}

}
