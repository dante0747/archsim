package io.archsim.app.worker;

import tools.jackson.databind.JsonNode;

/**
 * How a run ended, in the columns the API reads.
 */
sealed interface RunOutcome {

	/**
	 * The run's status once this is written.
	 * @return {@code succeeded}, {@code failed} or {@code cancelled}
	 */
	String status();

	/**
	 * The run simulated: its report, its summary for the lists, both digests and its
	 * time.
	 *
	 * @param report the report
	 * @param summary the figures the run lists and seed groups show
	 * @param inputDigest the digest of what was simulated
	 * @param resultDigest the digest of the result
	 * @param wallSeconds how long the simulation took
	 */
	record Succeeded(JsonNode report, JsonNode summary, String inputDigest, String resultDigest,
			double wallSeconds) implements RunOutcome {

		@Override
		public String status() {
			return "succeeded";
		}

	}

	/**
	 * The run failed: its error, as the API answers it.
	 *
	 * @param error the message, and the design's issues when it was invalid
	 */
	record Failed(JsonNode error) implements RunOutcome {

		@Override
		public String status() {
			return "failed";
		}

	}

	/**
	 * The run stopped because someone asked.
	 */
	record Cancelled() implements RunOutcome {

		@Override
		public String status() {
			return "cancelled";
		}

	}

}
