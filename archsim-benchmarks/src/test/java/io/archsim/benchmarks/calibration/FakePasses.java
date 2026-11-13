package io.archsim.benchmarks.calibration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.model.text.PythonFormat;

/**
 * Calibration passes without Docker: a service whose CPU per request falls with load, as
 * on the laptop (0.2 ms plus 40 ms per second), 1 ms a second at rest; each pass named as
 * it runs ({@code read up 2 @650}).
 */
final class FakePasses implements CalibrationPasses {

	/**
	 * The idle pass: 1 ms a second of the service's and the API's CPU, 2 of PostgreSQL's.
	 */
	static final String IDLE = """
			{"seconds": 20, "containerCpuMsPerSecond": {"api": 1.0, "postgres": 2.0}, "serviceCpuMsPerSecond": 1.0}""";

	private final List<String> labels = new ArrayList<>();

	private final Predicate<String> disturbed;

	private final boolean memory;

	/**
	 * Passes, the ones a label names disturbed.
	 * @param disturbed whether a label's pass stalls, every time it runs
	 */
	FakePasses(Predicate<String> disturbed) {
		this(disturbed, false);
	}

	private FakePasses(Predicate<String> disturbed, boolean memory) {
		this.disturbed = disturbed;
		this.memory = memory;
	}

	/**
	 * Passes in which the service holds 40 MiB at rest and 0.05 MiB more for each request
	 * a second.
	 * @return the passes
	 */
	static FakePasses withMemory() {
		return new FakePasses((label) -> false, true);
	}

	/**
	 * The laptop's CPU per request at a rate.
	 * @param rate requests per second
	 * @return milliseconds
	 */
	static double laptopCpu(double rate) {
		return 0.2 + 40 / rate;
	}

	@Override
	public JsonNode settle(Experiment experiment) {
		return JsonText.read("{\"at\": \"t\"}");
	}

	@Override
	public JsonNode idle(Experiment experiment) {
		ObjectNode idle = JsonText.object(IDLE);
		if (this.memory) {
			idle.putObject("workingSetMiB").put("api", 40.0);
		}
		return idle;
	}

	@Override
	public JsonNode run(Experiment experiment, String mix, JsonNode rate, String label) {
		this.labels.add(
				mix.substring(0, mix.indexOf(':')) + " " + label + " @" + PythonFormat.format(rate.doubleValue(), "g"));
		ObjectNode entry = pass(mix, rate);
		if (this.disturbed.test(label)) {
			((ObjectNode) entry.get("k6")).put("p50", 3.3).put("p95", 46.0);
		}
		if (this.memory) {
			entry.putObject("resources")
				.putObject("containers")
				.putObject("api")
				.put("workingSetMiB", 40.0 + 0.05 * rate.doubleValue());
		}
		return entry;
	}

	/**
	 * The passes run so far, each named as it ran.
	 * @return the names
	 */
	List<String> labels() {
		return List.copyOf(this.labels);
	}

	// one pass of a mix at a rate, as run.py's tests' fake_pass made it
	private static ObjectNode pass(String mix, JsonNode rate) {
		long requests = (long) (rate.doubleValue() * 30);
		double cpu = laptopCpu(rate.doubleValue());
		ObjectNode entry = JsonText.object("""
				{"mix": "%s", "k6": {"p50": 1.0, "p95": 2.0, "p99": 3.0, "mean": 1.2, "sent": %d, "succeeded": %d},
				 "service": {"cpuMs": %s, "cpuMsPerRequest": %s, "requests": %d, "timings": {
				   "own": %s, "handler": %s, "db_read": %s, "db_write": %s, "db_write_async": %s}},
				 "containerCpuMs": {"api": %s, "postgres": %s}}""".formatted(mix, requests, requests,
				cpu * requests + 1.0 * 30, cpu, requests, timing(0.02, requests, 1.0), timing(0.8, requests, 1.0),
				timing(0.5, requests, 0.9), timing(2.0, requests, 0.1), timing(0.7, requests, 1.0), cpu * requests,
				0.15 * requests + 2.0 * 30));
		ObjectNode ordered = JsonText.object("{\"mix\": \"" + mix + "\"}");
		ordered.set("rateRps", rate);
		entry.remove("mix");
		ordered.setAll(entry);
		return ordered;
	}

	private static String timing(double mean, long requests, double share) {
		return "{\"count\": %d, \"mean\": %s, \"median\": %s, \"p95\": %s, \"p99\": %s}"
			.formatted((long) Math.rint(requests * share), mean, mean * 0.9, mean * 2, mean * 3);
	}

}
