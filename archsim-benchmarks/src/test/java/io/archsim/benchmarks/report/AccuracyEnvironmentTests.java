package io.archsim.benchmarks.report;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.docker.Cpusets;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.results.ResultFiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The machine {@code accuracy.json} publishes for an experiment: the harness's runtime
 * under the key its run recorded, Python's for the committed runs and Java's for the runs
 * the Java harness makes.
 */
class AccuracyEnvironmentTests {

	private static final RepositoryLayout LAYOUT = RepositoryLayout.current();

	private static final Experiment PG_MIXED = Experiments.standard().get("pg-mixed");

	@Test
	void environment_aRunTheJavaHarnessMade_publishesItsJava() {
		ObjectNode environment = recorded();
		environment.remove("python");
		environment.put("java", "25.0.3+9");

		ObjectNode published = AccuracyExperiment.environment(PG_MIXED, environment, cpusets());

		assertThat(published.get("java").stringValue()).isEqualTo("25.0.3+9");
		assertThat(published.has("python")).isFalse();
	}

	@Test
	void environment_aRunThePythonHarnessMade_publishesItsPython() {
		ObjectNode environment = recorded();

		ObjectNode published = AccuracyExperiment.environment(PG_MIXED, environment, cpusets());

		assertThat(published.get("python")).isEqualTo(environment.get("python"));
		assertThat(published.has("java")).isFalse();
	}

	// pg-mixed's latest run, which the Python harness made
	private static ObjectNode recorded() {
		return (ObjectNode) ResultFiles.read(LAYOUT.results().resolve("pg-mixed/environment.json")).orElseThrow();
	}

	private static Cpusets cpusets() {
		return Cpusets.declaredIn(LAYOUT.compose());
	}

}
