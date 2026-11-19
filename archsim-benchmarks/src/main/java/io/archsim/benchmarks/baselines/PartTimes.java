package io.archsim.benchmarks.baselines;

import java.util.Comparator;

import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.inputs.ModelDistributions;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.component.cache.CacheSpec;
import io.archsim.model.component.database.DatabaseSpec;
import io.archsim.model.component.queue.MessageQueueSpec;
import io.archsim.model.component.service.ServiceSpec;

/**
 * The milliseconds one client request takes of each part of a design, from the means of
 * its calibrated times and what the request sends the part ({@link RequestLoads}): no
 * waiting, no spread, no garbage collection.
 */
final class PartTimes {

	private static final String CONFIG = "config";

	private static final String TYPE = "type";

	private static final String SERVICE_TIME = "serviceTimeMs";

	private static final String READ_TIME = "readServiceTimeMs";

	private static final String WRITE_TIME = "writeServiceTimeMs";

	private static final String CPU_SHARE = "cpuShare";

	private final RequestLoads loads;

	private final double missShare;

	/**
	 * The times of a design's parts.
	 * @param loads what one client request sends each part
	 * @param missShare the share of a cache's lookups that miss
	 */
	PartTimes(RequestLoads loads, double missShare) {
		this.loads = loads;
		this.missShare = missShare;
	}

	/**
	 * The milliseconds of a part's own work one client request takes: a service's time at
	 * the calibrated rate, a database's queries with a write's commit flush, a cache's
	 * lookup and the value it writes back after a miss, a queue's publish.
	 * @param part the part
	 * @return the time; zero for a part of another type
	 */
	double own(JsonNode part) {
		JsonNode config = part.get(CONFIG);
		RequestLoads.Load load = this.loads.sentTo(part);
		return switch (part.get(TYPE).stringValue()) {
			case ServiceSpec.TYPE -> load.total() * mean(config, SERVICE_TIME) * calibratedFactor(config);
			case DatabaseSpec.TYPE -> load.reads() * mean(config, READ_TIME) + load.writes() * write(part);
			case CacheSpec.TYPE -> load.total() * (1 + this.missShare) * mean(config, SERVICE_TIME);
			case MessageQueueSpec.TYPE -> load.total() * mean(config, "publishTimeMs");
			default -> 0.0;
		};
	}

	/**
	 * The milliseconds of a part's CPU one client request takes: the share of a service's
	 * or a database's time that needs a core (a write's flush needs none), all of a
	 * cache's.
	 * @param part the part
	 * @return the time; zero for a queue, which the design gives no cores
	 */
	double cpu(JsonNode part) {
		JsonNode config = part.get(CONFIG);
		RequestLoads.Load load = this.loads.sentTo(part);
		double share = config.has(CPU_SHARE) ? config.get(CPU_SHARE).doubleValue() : 1.0;
		return switch (part.get(TYPE).stringValue()) {
			case ServiceSpec.TYPE -> share * own(part);
			case DatabaseSpec.TYPE ->
				share * (load.reads() * mean(config, READ_TIME) + load.writes() * mean(config, WRITE_TIME));
			case CacheSpec.TYPE -> own(part);
			default -> 0.0;
		};
	}

	/**
	 * The milliseconds one client request holds a row's lock in a database: each of its
	 * writes, until its commit is flushed.
	 * @param database the database
	 * @return the time
	 */
	double lockHold(JsonNode database) {
		return this.loads.sentTo(database).writes() * write(database);
	}

	/**
	 * The milliseconds one client request keeps a queue's consumer busy: its own time per
	 * message, and the calls it waits for.
	 * @param queue the queue
	 * @return the time
	 */
	double consumerHold(JsonNode queue) {
		return this.loads.sentTo(queue).total() * mean(queue.get(CONFIG), "consumerTimeMs") + calls(queue);
	}

	/**
	 * The milliseconds one client request stays in a part and in what the part waits for:
	 * its own work, then each call it makes. A queue's producer waits for the publish
	 * alone.
	 * @param part the part
	 * @return the time
	 */
	double stay(JsonNode part) {
		boolean queue = MessageQueueSpec.TYPE.equals(part.get(TYPE).stringValue());
		return queue ? own(part) : own(part) + calls(part);
	}

	// the calls a part makes for one client request: each link both ways, and the part
	// called
	private double calls(JsonNode part) {
		double time = 0.0;
		for (JsonNode link : this.loads.calls(part)) {
			JsonNode called = this.loads.calledBy(link);
			time += this.loads.sentTo(called).total() * 2 * mean(link.get(CONFIG), "latencyMs") + stay(called);
		}
		return time;
	}

	// a write and its commit's flush
	private static double write(JsonNode database) {
		JsonNode config = database.get(CONFIG);
		return mean(config, WRITE_TIME) + mean(config, "commitFlushMs");
	}

	// the load curve's factor at its highest rate, the calibrated one; 1 without a curve
	private static double calibratedFactor(JsonNode config) {
		return config.path("serviceTimeByLoad")
			.valueStream()
			.max(Comparator.comparingDouble((JsonNode point) -> point.get("rps").doubleValue()))
			.map((point) -> point.get("factor").doubleValue())
			.orElse(1.0);
	}

	// a time's mean: a number, or a distribution's as the engine samples it; zero for a
	// time the design does not set
	private static double mean(JsonNode config, String key) {
		JsonNode time = PythonValues.get(config, key);
		if (PythonValues.isNone(time)) {
			return 0.0;
		}
		return time.isNumber() ? time.doubleValue() : ModelDistributions.mean(time);
	}

}
