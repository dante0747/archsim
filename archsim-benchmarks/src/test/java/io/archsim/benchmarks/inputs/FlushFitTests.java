package io.archsim.benchmarks.inputs;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.designs.DatabaseAlone;
import io.archsim.benchmarks.designs.Simulations;
import io.archsim.benchmarks.experiments.Experiments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The WAL flush with which the model's writes wait as long for their row lock and their
 * flush as the service's did: shorter than that wait where writes queue for the lock.
 */
class FlushFitTests {

	// writes to one row at 550/s over a link that jitters their arrival: the
	// model's writes also queue for the row's lock, so the flush that gives
	// a 0.9 ms wait for lock and flush together is shorter than 0.9 ms
	@Test
	void fit_writesToOneRow_findTheFlushThatGivesTheMeasuredWait() {
		FlushFit.Result fit = new FlushFit(new DatabaseAlone(Simulations.onTheEngine()))
			.fit(Experiments.standard().get("pg-hot-row"), hotRow(), new FlushFit.Target(number(550), 0.0, 0.9, 1.5));

		JsonNode database = fit.database();
		assertThat(database.get("meanLockWaitMs").doubleValue()).isGreaterThan(0.05);
		assertThat(ModelDistributions.mean(ModelDistributions.lognormal(fit.medianMs(), fit.medianMs() * 1.5)))
			.isLessThan(0.9 - 0.05);
		assertThat(database.get("meanLockWaitMs").doubleValue() + database.get("meanCommitWaitMs").doubleValue())
			.isCloseTo(0.9, within(0.005 * 0.9));
	}

	// no write waits for another: the flush is the wait, in the mean (the model samples
	// about 750 flushes, whose mean is within a few percent of the distribution's)
	@Test
	void fit_writesThatNeverQueue_findTheFlushThatIsTheWait() {
		FlushFit.Result fit = new FlushFit(new DatabaseAlone(Simulations.onTheEngine()))
			.fit(Experiments.standard().get("pg-hot-row"), hotRow(), new FlushFit.Target(number(50), 0.0, 0.9, 1.5));

		JsonNode database = fit.database();
		assertThat(database.get("meanLockWaitMs").doubleValue()).isCloseTo(0.0, within(1e-3));
		assertThat(database.get("meanCommitWaitMs").doubleValue()).isCloseTo(0.9, within(0.005 * 0.9));
		assertThat(ModelDistributions.mean(ModelDistributions.lognormal(fit.medianMs(), fit.medianMs() * 1.5)))
			.isCloseTo(0.9, within(0.03 * 0.9));
	}

	private static ObjectNode hotRow() {
		ObjectNode values = JsonText.object("{\"dbCpuShare\": 1.0}");
		values.set("writeServiceTimeMs", ModelDistributions.lognormal(0.25, 0.6));
		values.set("dbLinkLatencyMs", ModelDistributions.lognormal(0.2, 1.5));
		return values;
	}

	private static JsonNode number(int value) {
		return JsonText.read(Integer.toString(value));
	}

}
