package io.archsim.benchmarks.baselines;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.values.PythonValues;

/**
 * A hard limit of one part of a design: what the part has, and how much of it one client
 * request takes.
 *
 * @param part the part's id
 * @param name which limit: {@code cpu}, {@code workers}, {@code connections},
 * {@code rowLock} or {@code consumers}
 * @param units what the part has of it: cores, workers, connections, the one lock,
 * consumer slots
 * @param msPerRequest the milliseconds of one unit a client request takes
 * @param onRequestPath whether a request waits for it; work behind a queue does not hold
 * a request up
 */
record Limit(String part, String name, double units, double msPerRequest, boolean onRequestPath) {

	/**
	 * The load at which the limit is reached.
	 * @return its units over the time a request takes of one, in requests per second
	 */
	double capacityRps() {
		return this.units * Units.MS_PER_SECOND / this.msPerRequest;
	}

	/**
	 * The limit as {@code baselines.json} records it.
	 * @return {@code part}, {@code limit}, {@code units}, {@code msPerRequest},
	 * {@code capacityRps} and {@code onRequestPath}
	 */
	ObjectNode json() {
		ObjectNode json = JsonNodeFactory.instance.objectNode().put("part", this.part).put("limit", this.name);
		boolean whole = this.units == Math.rint(this.units);
		json.set("units", whole ? PythonValues.integer((long) this.units) : PythonValues.number(this.units));
		json.put("msPerRequest", Units.precise(this.msPerRequest));
		json.put("capacityRps", Units.rate(capacityRps()));
		return json.put("onRequestPath", this.onRequestPath);
	}

}
