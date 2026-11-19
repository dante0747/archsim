package io.archsim.engine.parity;

import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Where the Java's estimate of a database that flushes its commits departs from the
 * frozen engine's (engine 1.5.1, ADR-0089), and how {@link EstimateDepartures} reads it
 * as the frozen engine's all the same. Python's instant estimate counted the log flush a
 * write waits for as work on the database's cores; the Java counts its queries alone, as
 * a run does.
 * <p>
 * A database with less of its CPU in use than Python's reads as Python's once its
 * utilization is the larger of its slot and CPU shares and its load the largest of those
 * and its rate limit's, as Python's are of Python's own: the three shares are then
 * Python's. The rule is one-sided: an estimate does not say how many of a database's
 * requests are writes, so nothing in it gives how much lower the share must be. The
 * engine's {@code CommitFlushCpuTests} holds every recorded database to the amount:
 * Python's share over the Java's is its queries and flushes over its queries.
 */
final class CommitFlushDepartures {

	/** The shares of a database that follow from its CPU. */
	private static final List<String> SHARES = List.of(EstimateParts.CPU, EstimateParts.UTILIZATION,
			EstimateParts.LOAD);

	private CommitFlushDepartures() {
	}

	/**
	 * Reads a database that departs as the frozen engine's.
	 * @param java the Java's part, changed when it departs
	 * @param python the frozen engine's part at the same place
	 * @return whether it departs
	 */
	static boolean putDatabase(ObjectNode java, JsonNode python) {
		if (!EstimateParts.isSame(java, python, "database") || !usesLessCpu(java, python)
				|| !EstimateParts.sharesFollow(java) || !EstimateParts.sharesFollow(python)) {
			return false;
		}
		SHARES.forEach((share) -> java.set(share, python.get(share)));
		return true;
	}

	private static boolean usesLessCpu(JsonNode java, JsonNode python) {
		JsonNode ours = java.path(EstimateParts.CPU);
		JsonNode theirs = python.path(EstimateParts.CPU);
		return ours.isNumber() && theirs.isNumber() && ours.doubleValue() < theirs.doubleValue();
	}

}
