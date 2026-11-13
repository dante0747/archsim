package io.archsim.benchmarks.inputs;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.designs.DatabaseAlone;
import io.archsim.benchmarks.designs.Simulations;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.results.ResultFiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * PostgreSQL's inputs: its CPU per query becomes its time in the model, the rest of each
 * round trip the service measured becomes the link, the CPU share is the CPU over the
 * model's time, and the WAL flush is PostgreSQL's own or fitted to the commit wait.
 */
class DatabaseInputsTests {

	private static final DatabaseInputs DATABASE = new DatabaseInputs(
			new CommitFlush(new DatabaseAlone(Simulations.onTheEngine())));

	// pg-mixed's last v5 top pass
	@Test
	void of_pgMixedsLastV5Pass_splitsTheReadsRoundTripIntoServerAndLink() {
		ObjectNode pass = replicate("pg-mixed/run-10");
		JsonNode read = pass.at("/high/service/timings/db_read");
		double roundTrip = ModelDistributions
			.mean(ModelDistributions.lognormal(read.get("median").doubleValue(), read.get("p95").doubleValue()));

		ObjectNode inputs = DATABASE.of(Experiments.standard().get("pg-mixed"), pass);

		double server = ModelDistributions.mean(inputs.at("/readServiceTimeMs/value"));
		double leg = ModelDistributions.mean(inputs.at("/dbLinkLatencyMs/value"));
		assertThat(server + 2 * leg).isCloseTo(roundTrip, within(1e-3 * roundTrip));
		assertThat(server).isLessThan(0.75 * roundTrip);
	}

	@Test
	void of_pgMixedsLastV5Pass_sharesACoreForEachQuerysTime() {
		ObjectNode inputs = DATABASE.of(Experiments.standard().get("pg-mixed"), replicate("pg-mixed/run-10"));

		assertThat(inputs.at("/dbCpuShare/value").doubleValue()).isCloseTo(1.0, within(0.02));
	}

	// no PostgreSQL statistics in v5: fitted in
	// the model to the wait the service measured
	@Test
	void of_pgMixedsLastV5Pass_fitsTheFlushToTheCommitWait() {
		ObjectNode inputs = DATABASE.of(Experiments.standard().get("pg-mixed"), replicate("pg-mixed/run-10"));

		assertThat(inputs.at("/commitFlushMs/how").stringValue()).contains("fitted so that the model's writes wait");
		assertThat(inputs.at("/commitFlushMs/model/commitsPerFlush").doubleValue()).isCloseTo(1.0, within(0.05));
	}

	// W22's trial: the top pass recorded PostgreSQL's statistics
	@Test
	void of_aPassWithPostgresqlsStatistics_takesItsOwnFlush() {
		ObjectNode pass = replicate("trials/2026-10-31-pg-hot-row-w22");

		ObjectNode inputs = DATABASE.of(Experiments.standard().get("pg-hot-row"), pass);

		JsonNode flush = inputs.get("commitFlushMs");
		double measured = PostgresCpu.flushes(pass.get("high")).orElseThrow().flushMs();
		assertThat(flush.get("how").stringValue()).contains("pg_stat_wal");
		assertThat(ModelDistributions.mean(flush.get("value"))).isCloseTo(measured, within(1e-3 * measured));
		assertThat(flush.at("/postgres/commitsPerFlush").doubleValue()).isCloseTo(1.0, within(0.05));
		assertThat(flush.at("/model/commitsPerFlush").doubleValue()).isCloseTo(1.0, within(0.01));
	}

	// the UPDATE in the executor: a small part of PostgreSQL's time per UPDATE
	@Test
	void of_aPassWithPostgresqlsStatistics_keepsItsExecutorTimeBesideTheModels() {
		ObjectNode inputs = DATABASE.of(Experiments.standard().get("pg-hot-row"),
				replicate("trials/2026-10-31-pg-hot-row-w22"));

		double executor = inputs.at("/writeServiceTimeMs/postgres/executorMs").doubleValue();
		assertThat(executor).isPositive()
			.isLessThan(0.5 * ModelDistributions.mean(inputs.at("/writeServiceTimeMs/value")));
	}

	// the first replicate's top pass with the calibration's low and idle passes
	private static ObjectNode replicate(String folder) {
		JsonNode calibration = ResultFiles
			.read(RepositoryLayout.current().results().resolve(folder).resolve("calibration.json"))
			.orElseThrow();
		ObjectNode pass = ((ObjectNode) calibration.get("replicates").get(0)).deepCopy();
		pass.set("low", calibration.get("passes").get("low"));
		pass.set("idle", calibration.get("idle"));
		return pass;
	}

}
