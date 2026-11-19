package io.archsim.benchmarks.designs;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.experiments.Experiments;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL alone, which the WAL flush is fitted on: the service's queries arrive
 * through the experiment's own pool of connections and over its own keys.
 */
class DatabaseAloneTests {

	private static final DatabaseAlone.Mix HALF_WRITES = new DatabaseAlone.Mix(0.5, 15);

	// go-pool's writes wait for one of two connections, every other experiment's for ten
	@ParameterizedTest
	@CsvSource({ "pg-mixed, 10", "java-hot-row, 10", "go-pool, 2" })
	void design_anExperiment_putsPostgresqlBehindItsServicesPool(String experiment, int connections) {
		JsonNode database = database(design(experiment));

		assertThat(database.at("/config/maxConnections").intValue()).isEqualTo(connections);
	}

	// a hot row's one key: the model's writes wait for its lock as the service's did
	@ParameterizedTest
	@CsvSource({ "pg-mixed, 100000", "pg-hot-row, 1", "java-hot-row, 1" })
	void design_anExperiment_spreadsTheQueriesOverItsKeys(String experiment, int keys) {
		assertThat(design(experiment).at("/workload/keyCount").intValue()).isEqualTo(keys);
	}

	private static ObjectNode design(String experiment) {
		return DatabaseAlone.design(Experiments.standard().get(experiment), JsonText.object("{}"), JsonText.read("400"),
				HALF_WRITES);
	}

	private static JsonNode database(ObjectNode design) {
		return design.at("/architecture/nodes/1");
	}

}
