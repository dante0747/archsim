package io.archsim.benchmarks.inputs;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.experiments.Experiments;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The service's CPU per request, shaped like its handler's own time: on Node.js, less the
 * pauses the model adds as garbage collection, which were on the event loop, inside it.
 */
class ServiceInputsTests {

	/** Five mark-compact cycles of 100 ms in all over 50,000 requests. */
	private static final String MARK_COMPACTS = """
			{"requests": 50000, "cpuMs": 10000.0,
			 "gc": {"collections": 5, "pauseMs": 100.0,
			        "byKind": {"major": {"collections": 5, "pauseMs": 100.0, "median": 20.0}}}}""";

	private static final JsonNode OWN_TIME = JsonText.read("{\"median\": 0.25, \"p95\": 0.9}");

	@Test
	void of_markCompacts_takesThePausesOffTheCpuPerRequest() {
		ObjectNode inputs = ServiceInputs.of(Experiments.standard().get("pg-mixed"), passes(MARK_COMPACTS));

		assertThat(inputs.get("serviceTimeMs").get("value"))
			.isEqualTo(ModelDistributions.lognormalWithMean(0.2 - 100.0 / 50_000, OWN_TIME));
	}

	@Test
	void of_markCompacts_saysWhatItTookOff() {
		ObjectNode inputs = ServiceInputs.of(Experiments.standard().get("pg-mixed"), passes(MARK_COMPACTS));

		assertThat(inputs.get("serviceTimeMs").get("how").stringValue())
			.contains("less the 0.0020 ms per request of the pauses modelled as GC");
	}

	@Test
	void of_aServiceThatReportedNoGc_isTheCpuPerRequest() {
		ObjectNode inputs = ServiceInputs.of(Experiments.standard().get("pg-mixed"),
				passes("{\"requests\": 50000, \"cpuMs\": 10000.0}"));

		assertThat(inputs.get("serviceTimeMs").get("value"))
			.isEqualTo(ModelDistributions.lognormalWithMean(0.2, OWN_TIME));
	}

	@Test
	void of_aServiceThatReportedNoGc_modelsNoPause() {
		ObjectNode inputs = ServiceInputs.of(Experiments.standard().get("pg-mixed"),
				passes("{\"requests\": 50000, \"cpuMs\": 10000.0}"));

		assertThat(inputs.has("gcPauseMs")).isFalse();
	}

	// a top pass at 1,666 req/s, as both passes, whose service stats are these with the
	// timings the inputs need, and an idle pass at no CPU
	private static JsonNode passes(String service) {
		ObjectNode stats = JsonText.object(service);
		stats.put("cpuMsPerRequest", 0.2);
		ObjectNode timings = stats.putObject("timings");
		for (String name : new String[] { "own", "handler", "db_read", "db_write" }) {
			timings.set(name, JsonText.read("{\"count\": 1, \"mean\": 0.3, \"median\": 0.25, \"p95\": 0.9}"));
		}
		ObjectNode high = JsonText.object("{\"rateRps\": 1666, \"k6\": {\"p50\": 0.5, \"p95\": 1.5}}");
		high.set("service", stats);
		ObjectNode passes = JsonText.object("""
				{"idle": {"serviceCpuMsPerSecond": 0.0, "containerCpuMsPerSecond": {}}}""");
		passes.set("low", high);
		passes.set("high", high);
		return passes;
	}

}
