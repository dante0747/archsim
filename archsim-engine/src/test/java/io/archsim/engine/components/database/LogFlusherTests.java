package io.archsim.engine.components.database;

import org.junit.jupiter.api.Test;

import io.archsim.engine.kernel.Simulation;

import static org.assertj.core.api.Assertions.assertThat;

/** Group commit: commits that arrive during a flush share the next one. */
class LogFlusherTests {

	private static final double FLUSH_MS = 10.0;

	private final Simulation simulation = new Simulation();

	private final LogFlusher log = new LogFlusher(this.simulation, () -> FLUSH_MS);

	@Test
	void commit_twoDuringAFlush_shareTheNextFlush() {
		this.log.commit();
		this.simulation.timeout(5).then(this.log::commit);
		this.simulation.timeout(6).then(this.log::commit);

		this.simulation.runUntil(100, 1000, () -> {
		});

		assertThat(this.log.flushes()).isEqualTo(2);
		assertThat(this.log.commits()).isEqualTo(3);
	}

	@Test
	void commit_aCommit_endsWithItsFlush() {
		double[] flushedAt = new double[1];
		this.log.commit().then(() -> flushedAt[0] = this.simulation.now());

		this.simulation.runUntil(100, 1000, () -> {
		});

		assertThat(flushedAt[0]).isEqualTo(FLUSH_MS);
	}

}
