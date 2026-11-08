package io.archsim.engine.runtime;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.report.FailureReason;
import io.archsim.model.codec.JsonEnum;

/**
 * How a request ended at a part: served, a cache's miss left to its caller, refused by a
 * part, or given up on by a caller, with where and why a failure started.
 *
 * @param status how it ended
 * @param reason why it failed; {@code null} when it did not
 * @param nodeId the part where the failure started
 * @param edgeId the link a caller gave up on
 */
public record Outcome(Status status, @Nullable FailureReason reason, @Nullable String nodeId, @Nullable String edgeId) {

	/** Served. */
	public static final Outcome OK = new Outcome(Status.OK, null, null, null);

	/**
	 * A cache lookup that did not find the key and leaves fetching it to the caller
	 * (cache-aside): served, as far as the cache goes.
	 */
	public static final Outcome MISS = new Outcome(Status.MISS, null, null, null);

	/**
	 * A request a part refused.
	 * @param reason why
	 * @param nodeId the part
	 * @return the outcome
	 */
	public static Outcome rejected(FailureReason reason, String nodeId) {
		return new Outcome(Status.REJECTED, reason, nodeId, null);
	}

	/**
	 * A call its caller gave up on.
	 * @param reason the caller's kind of timeout
	 * @param callerId the part that gave up
	 * @param edgeId the link it gave up on
	 * @return the outcome
	 */
	public static Outcome timedOut(FailureReason reason, String callerId, String edgeId) {
		return new Outcome(Status.TIMEOUT, reason, callerId, edgeId);
	}

	/**
	 * Whether the request was served.
	 * @return {@code true} unless it failed
	 */
	public boolean ok() {
		return this.status == Status.OK || this.status == Status.MISS;
	}

	/**
	 * Whether a cache left fetching the data to its caller.
	 * @return {@code true} for a cache-aside miss
	 */
	public boolean missed() {
		return this.status == Status.MISS;
	}

	/**
	 * Whether it failed for this reason.
	 * @param failure the reason
	 * @return {@code true} when it did
	 */
	public boolean failedFor(FailureReason failure) {
		return this.reason == failure;
	}

	/**
	 * How a request ended, as a report names it.
	 */
	public enum Status implements JsonEnum {

		/** Served. */
		OK("ok"),

		/** Not found in a cache, which leaves fetching it to the caller. */
		MISS("miss"),

		/** Refused by a part. */
		REJECTED("rejected"),

		/** Given up on by a caller. */
		TIMEOUT("timeout");

		private final String json;

		Status(String json) {
			this.json = json;
		}

		@Override
		public String json() {
			return this.json;
		}

	}

}
