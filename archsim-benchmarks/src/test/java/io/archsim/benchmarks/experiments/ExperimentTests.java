package io.archsim.benchmarks.experiments;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * An experiment's own service CPUs and pool, where it names them: at least one of each.
 */
class ExperimentTests {

	@Test
	void connections_aPoolOfItsOwn_areThatPools() {
		assertThat(experiment(null, 2).connections()).isEqualTo(2);
	}

	@Test
	void connections_noPoolOfItsOwn_areTheDefaultTen() {
		assertThat(experiment(null, null).connections()).isEqualTo(Experiment.DEFAULT_POOL);
	}

	// PostgreSQL would serve nothing, and the staircase's pool bound would be none
	@Test
	void experiment_aPoolWithoutAConnection_isRefused() {
		assertThatIllegalArgumentException().isThrownBy(() -> experiment(null, 0))
			.withMessage("x's pool holds 0 connections: a service needs at least 1");
	}

	@Test
	void experiment_aServiceWithoutACpu_isRefused() {
		assertThatIllegalArgumentException().isThrownBy(() -> experiment(0, null))
			.withMessage("x's service has 0 CPUs: a service needs at least 1");
	}

	// an experiment that names these CPUs for its service and this pool, or neither
	private static Experiment experiment(@Nullable Integer serviceCpus, @Nullable Integer pool) {
		return new Experiment("x", "postgres", "an experiment", "read:1", 1.0, 100_000, 0.0, List.of("read:1"),
				"nodejs", 30, null, serviceCpus, pool);
	}

}
