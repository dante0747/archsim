package io.archsim.engine.parity;

import tools.jackson.databind.JsonNode;

/**
 * The fields of an estimate's part as the estimate's departures read them, and what
 * follows from them in the estimate, for {@link EstimateDepartures} and its rules.
 */
final class EstimateParts {

	static final String PARTS = "parts";

	static final String ID = "id";

	static final String TYPE = "type";

	static final String POOLS = "pools";

	static final String SLOTS = "slotUtilization";

	static final String CPU = "cpuUtilization";

	static final String RATE_LIMIT = "rateLimitShare";

	static final String UTILIZATION = "utilization";

	static final String LOAD = "load";

	static final String LIMITED_BY = "limitedBy";

	private EstimateParts() {
	}

	/**
	 * Whether a part's utilization is the larger of its slot and CPU shares, and its load
	 * the largest of those and its rate limit's, as the estimate makes them.
	 * @param part an estimate's part
	 * @return whether its utilization and load follow from its shares
	 */
	static boolean sharesFollow(JsonNode part) {
		double utilization = Math.max(number(part.path(SLOTS)), number(part.path(CPU)));
		double load = Math.max(utilization, number(part.path(RATE_LIMIT)));
		return part.path(UTILIZATION).isNumber() && part.path(UTILIZATION).doubleValue() == utilization
				&& part.path(LOAD).isNumber() && part.path(LOAD).doubleValue() == load;
	}

	/**
	 * Whether two parts are the same part, of a kind.
	 * @param java the Java's part
	 * @param python the frozen engine's part at the same place
	 * @param type the kind of part
	 * @return whether both are that part
	 */
	static boolean isSame(JsonNode java, JsonNode python, String type) {
		return type.equals(java.path(TYPE).stringValue("")) && java.path(ID).equals(python.path(ID));
	}

	/**
	 * A share, an unknown one read as zero.
	 * @param value the share's JSON
	 * @return the share, or zero
	 */
	static double number(JsonNode value) {
		return value.isNumber() ? value.doubleValue() : 0.0;
	}

}
