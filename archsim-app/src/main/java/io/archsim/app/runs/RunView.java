package io.archsim.app.runs;

import java.time.Instant;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * A simulation run, as people read it: its status, progress and outcome. The worker that
 * runs it writes its progress and outcome, from whichever process or host it is on.
 *
 * @param id the run's id
 * @param projectId its project
 * @param label what it is called
 * @param scenarioId the scenario it simulates, if any
 * @param scenarioName that scenario's name
 * @param status {@code queued}, {@code running}, {@code succeeded}, {@code failed} or
 * {@code cancelled}
 * @param progress how far it has got, from 0 to 1
 * @param error why it failed, if it did
 * @param seed the seed it simulates with
 * @param engineVersion the engine that simulates it
 * @param inputDigest the digest of its input, once it ran
 * @param resultDigest the digest of its result, once it finished
 * @param summary its report's summary, once it finished
 * @param wallSeconds how long it took
 * @param createdAt when it was queued
 * @param startedAt when a worker took it
 * @param finishedAt when it finished
 * @param reproducesRunId the run a rerun reproduces, in the answer that starts it
 * @param groupId its seed group, if any
 * @param seedIndex its place in the group
 * @param mode {@code full}, or {@code sampled} for a quick check
 */
record RunView(String id, String projectId, @Nullable String label, @Nullable String scenarioId,
		@Nullable String scenarioName, String status, double progress, @Nullable JsonNode error, long seed,
		String engineVersion, @Nullable String inputDigest, @Nullable String resultDigest, @Nullable JsonNode summary,
		@Nullable Double wallSeconds, Instant createdAt, @Nullable Instant startedAt, @Nullable Instant finishedAt,
		@Nullable String reproducesRunId, @Nullable String groupId, @Nullable Integer seedIndex, String mode) {

	/** The characters of a run's id that name it when it has no label. */
	private static final int SHORT_ID = 8;

	private static final String SUCCEEDED = "succeeded";

	/**
	 * Whether a worker still has the run to do: queued or running.
	 * @return {@code true} for an active run
	 */
	boolean active() {
		return "queued".equals(this.status) || "running".equals(this.status);
	}

	/**
	 * Whether the run finished and has its report.
	 * @return {@code true} for a run that succeeded
	 */
	boolean succeeded() {
		return SUCCEEDED.equals(this.status);
	}

	/**
	 * What people call the run: its label, else the start of its id.
	 * @return the name
	 */
	String name() {
		return (this.label != null && !this.label.isEmpty()) ? this.label : shortId();
	}

	/**
	 * The start of the run's id, which names it in messages.
	 * @return its first eight characters
	 */
	String shortId() {
		return this.id.substring(0, SHORT_ID);
	}

	/**
	 * The run, as the answer that reruns another names it.
	 * @param originalId the run it reproduces
	 * @return the run, naming the original
	 */
	RunView reproducing(String originalId) {
		return new RunView(this.id, this.projectId, this.label, this.scenarioId, this.scenarioName, this.status,
				this.progress, this.error, this.seed, this.engineVersion, this.inputDigest, this.resultDigest,
				this.summary, this.wallSeconds, this.createdAt, this.startedAt, this.finishedAt, originalId,
				this.groupId, this.seedIndex, this.mode);
	}

}
