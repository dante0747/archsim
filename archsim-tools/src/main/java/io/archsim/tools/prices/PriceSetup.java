package io.archsim.tools.prices;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

/**
 * What the price commands work with.
 *
 * @param downloads where the price lists come from
 * @param environment the environment: {@code GOOGLE_API_KEY}, and GitHub's
 * {@code GITHUB_OUTPUT} file under Actions
 * @param snapshots the folder of the snapshots archsim-model ships
 * @param clock the clock, whose day dates a snapshot
 */
record PriceSetup(PriceDownloads downloads, Map<String, String> environment, Path snapshots, Clock clock) {

	PriceSetup {
		environment = Map.copyOf(environment);
	}

	/**
	 * A writer of snapshots among the snapshots, dated by the clock.
	 * @param err where it says what it wrote
	 * @return the writer
	 */
	SnapshotWriter writer(PrintWriter err) {
		return new SnapshotWriter(this.snapshots, this.clock, err);
	}

}
