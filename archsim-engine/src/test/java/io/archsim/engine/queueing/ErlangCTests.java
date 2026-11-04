package io.archsim.engine.queueing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The chance to wait in an M/M/c queue. */
class ErlangCTests {

	@Test
	void waitProbability_oneServer_isItsUtilisation() {
		assertThat(ErlangC.waitProbability(1, 0.5)).hasValue(0.5);
	}

	@Test
	void waitProbability_twoServers_isTheErlangCFormula() {
		assertThat(ErlangC.waitProbability(2, 1.8)).hasValue(0.8526315789473683);
	}

	@Test
	void waitProbability_noLoad_isZero() {
		assertThat(ErlangC.waitProbability(4, 0)).hasValue(0.0);
	}

	@Test
	void waitProbability_asMuchLoadAsServers_isUnstable() {
		assertThat(ErlangC.waitProbability(4, 4)).isEmpty();
	}

	@Test
	void waitProbability_noServersForSomeLoad_isUnstable() {
		assertThat(ErlangC.waitProbability(0, 1)).isEmpty();
	}

}
