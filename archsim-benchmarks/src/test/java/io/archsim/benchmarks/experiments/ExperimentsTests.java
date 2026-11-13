package io.archsim.benchmarks.experiments;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class ExperimentsTests {

	private static final Experiments EXPERIMENTS = Experiments.standard();

	@Test
	void standard_theExperiments_areTheTunedOnFourThenTheFiveHeldOut() {
		List<String> ids = EXPERIMENTS.all().stream().map(Experiment::id).toList();

		assertThat(ids).containsExactly("pg-mixed", "pg-hot-row", "cache-aside", "kafka", "java-mixed", "go-mixed",
				"python-mixed", "chain", "cache-ttl");
	}

	@Test
	void standard_theHeldOutExperiments_wereRegisteredTogether() {
		List<HeldOut> registrations = EXPERIMENTS.all()
			.stream()
			.filter(Experiment::isHeldOut)
			.map(Experiment::heldOut)
			.distinct()
			.toList();

		assertThat(registrations)
			.containsExactly(new HeldOut("2026-10-30", "ce2d109d534b9a9cd18a4a42690b71dcf86342f4"));
	}

	@Test
	void get_cacheTtl_isItsOwnStackWithAFiveSecondTtl() {
		Experiment cacheTtl = EXPERIMENTS.get("cache-ttl");

		assertThat(cacheTtl)
			.extracting(Experiment::stack, Experiment::mix, Experiment::ttlS, Experiment::keys, Experiment::skew)
			.containsExactly("cache-ttl", "cached:1", 5, 1_000_000, 1.1);
	}

	@Test
	void stacks_postgres_runsBothPostgresExperiments() {
		Map<String, List<String>> stacks = EXPERIMENTS.stacks();

		assertThat(stacks).containsEntry("postgres", List.of("pg-mixed", "pg-hot-row"));
		assertThat(stacks.keySet()).containsExactly("postgres", "cache", "kafka", "java-mixed", "go-mixed",
				"python-mixed", "chain", "cache-ttl");
	}

	@Test
	void runtime_pythonMixed_isGunicornsTenSyncWorkers() {
		ServiceRuntime runtime = EXPERIMENTS.runtime(EXPERIMENTS.get("python-mixed"));

		assertThat(runtime).extracting(ServiceRuntime::technology, ServiceRuntime::cpuCores, ServiceRuntime::workers)
			.containsExactly("python", 2, 10);
	}

	@Test
	void read_anExperimentWithAnUnknownRuntime_fails() {
		String catalog = """
				{"runtimes": {}, "stacks": {}, "experiments": [{"id": "x", "stack": "s", "title": "t", "mix": "m",
				  "readRatio": 0.5, "keys": 1, "skew": 0.0, "calibration": [], "runtime": "cobol", "ttlS": 30}]}""";

		assertThatIllegalStateException().isThrownBy(() -> Experiments.read(text(catalog)))
			.withMessage("x's runtime cobol is not one of []");
	}

	@Test
	void read_aStackNamingNoExperiment_fails() {
		String catalog = """
				{"runtimes": {}, "experiments": [], "stacks": {"postgres": ["pg-mixed"]}}""";

		assertThatIllegalStateException().isThrownBy(() -> Experiments.read(text(catalog)))
			.withMessage("stack postgres runs pg-mixed, which is no experiment");
	}

	private static InputStream text(String json) {
		return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
	}

}
