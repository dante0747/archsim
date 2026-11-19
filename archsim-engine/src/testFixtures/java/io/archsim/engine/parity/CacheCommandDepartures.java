package io.archsim.engine.parity;

import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.estimate.Limit;

/**
 * Where the Java's estimate of a cache departs from the frozen engine's (engine 1.5.2,
 * ADR-0092), and how {@link EstimateDepartures} reads it as the frozen engine's all the
 * same. Python's instant estimate put a sharded cache's whole load on one shard's cores,
 * and left out of a read-through cache's slots and CPU the command that fills a key it
 * missed; the Java spreads the commands over every shard's cores and counts each fill, as
 * a run does.
 * <p>
 * A cache with its slots as busy as Python's or busier, and its slot or CPU share
 * different from Python's, reads as Python's once its CPU has moved as its slots and
 * shards say, its utilization is the larger of its slot and CPU shares, its load the
 * largest of those and its rate limit's, and its limit one of its shares equal to its
 * load, as Python's are of Python's own: the four shares and its limit are then Python's.
 * A cache holds a slot for its command's time, so the CPU's move follows from the slots':
 * Python's CPU share times the Java's slot share is the shards times the Java's CPU share
 * times Python's slot share, to the rounding of the four shares, at any speed of its
 * threads. The limit may change with the shares, from its CPU to its slots, and where its
 * slots and CPU are equal (one executor on one core) the one it names is the rounding's
 * choice. How far the slots move, the rule cannot say: the engine's
 * {@code CacheCommandsTests} holds every cache of the designs {@code RecordedEstimates}
 * reads to it, its commands (requests and fills) over its requests.
 */
final class CacheCommandDepartures {

	/** How far a share rounded to four decimals may be from the unrounded one. */
	private static final double ROUNDING = 5e-5;

	/** Floating-point noise. */
	private static final double NOISE = 1e-9;

	/**
	 * A product of two shares each rounded to four decimals is off by up to the rounding
	 * times their sum, read from the rounded shares, and this many squared roundings.
	 */
	private static final int SQUARED_ROUNDINGS = 3;

	/** The shares of a cache that follow from its commands, and its limit. */
	private static final List<String> SHARES = List.of(EstimateParts.SLOTS, EstimateParts.CPU,
			EstimateParts.UTILIZATION, EstimateParts.LOAD, EstimateParts.LIMITED_BY);

	/** The share each limit names. */
	private static final Map<String, String> LIMITS = Map.of(Limit.SLOTS.json(), EstimateParts.SLOTS, Limit.CPU.json(),
			EstimateParts.CPU, Limit.RATE_LIMIT.json(), EstimateParts.RATE_LIMIT);

	private CacheCommandDepartures() {
	}

	/**
	 * Reads a cache that departs as the frozen engine's.
	 * @param java the Java's part, changed when it departs
	 * @param python the frozen engine's part at the same place
	 * @return whether it departs
	 */
	static boolean putCache(ObjectNode java, JsonNode python) {
		if (!EstimateParts.isSame(java, python, "cache") || !departs(java, python) || !isLimited(java)
				|| !isLimited(python)) {
			return false;
		}
		SHARES.forEach((share) -> java.set(share, python.get(share)));
		return true;
	}

	// its slots no less busy than Python's, a share that differs, and its CPU moved as
	// its slots and shards say
	private static boolean departs(JsonNode java, JsonNode python) {
		JsonNode ourSlots = java.path(EstimateParts.SLOTS);
		JsonNode theirSlots = python.path(EstimateParts.SLOTS);
		boolean slotsKnown = ourSlots.isNumber() && theirSlots.isNumber();
		boolean busier = slotsKnown && ourSlots.doubleValue() >= theirSlots.doubleValue();
		boolean differs = !ourSlots.equals(theirSlots)
				|| !java.path(EstimateParts.CPU).equals(python.path(EstimateParts.CPU));
		return busier && differs && cpuFollowsSlots(java, python);
	}

	// Python's CPU share times the Java's slot share is the shards times the Java's CPU
	// share times Python's slot share, to the rounding of each share; a cache without a
	// CPU size in both has nothing to check
	private static boolean cpuFollowsSlots(JsonNode java, JsonNode python) {
		JsonNode ourCpu = java.path(EstimateParts.CPU);
		JsonNode theirCpu = python.path(EstimateParts.CPU);
		if (!ourCpu.isNumber() && !theirCpu.isNumber()) {
			return true;
		}
		JsonNode pools = java.path(EstimateParts.POOLS);
		if (!ourCpu.isNumber() || !theirCpu.isNumber() || !pools.isNumber()
				|| !pools.equals(python.path(EstimateParts.POOLS))) {
			return false;
		}
		double shards = pools.doubleValue();
		double ourSlots = java.path(EstimateParts.SLOTS).doubleValue();
		double theirSlots = python.path(EstimateParts.SLOTS).doubleValue();
		double theirs = theirCpu.doubleValue() * ourSlots;
		double ours = shards * ourCpu.doubleValue() * theirSlots;
		double rounding = ROUNDING * (theirCpu.doubleValue() + ourSlots + shards * (ourCpu.doubleValue() + theirSlots))
				+ SQUARED_ROUNDINGS * ROUNDING * ROUNDING * (1 + shards) + NOISE;
		return Math.abs(theirs - ours) <= rounding;
	}

	// its utilization and load follow from its shares, and its limit names a share equal
	// to its load
	private static boolean isLimited(JsonNode part) {
		String share = LIMITS.get(part.path(EstimateParts.LIMITED_BY).stringValue(""));
		if (share == null) {
			return false;
		}
		JsonNode limiting = part.path(share);
		return EstimateParts.sharesFollow(part) && limiting.isNumber()
				&& limiting.doubleValue() == part.path(EstimateParts.LOAD).doubleValue();
	}

}
