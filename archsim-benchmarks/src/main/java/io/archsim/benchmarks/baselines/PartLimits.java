package io.archsim.benchmarks.baselines;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.component.cache.CacheSpec;
import io.archsim.model.component.database.DatabaseSpec;
import io.archsim.model.component.queue.MessageQueueSpec;
import io.archsim.model.component.service.ServiceSpec;

/**
 * The hard limits of a design's parts, each from what the part has and the time one
 * client request takes of it: a service's cores and, when its workers wait for their
 * calls, its workers; a database's cores, its connections and, when every write updates
 * one row, that row's lock; a cache's cores; a queue's consumers.
 */
final class PartLimits {

	private static final String CONFIG = "config";

	private static final String CPU = "cpu";

	private static final String CORES = "cpuCores";

	private final RequestLoads loads;

	private final PartTimes times;

	private final boolean oneKey;

	private PartLimits(JsonNode design, double missShare) {
		this.loads = new RequestLoads(design, missShare);
		this.times = new PartTimes(this.loads, missShare);
		this.oneKey = design.at("/workload/keyCount").asInt() == 1;
	}

	/**
	 * A design's limits.
	 * @param design the design
	 * @param missShare the share of a cache's lookups that miss
	 * @return the limits of its parts, in the design's order
	 */
	static List<Limit> of(JsonNode design, double missShare) {
		PartLimits limits = new PartLimits(design, missShare);
		List<Limit> found = new ArrayList<>();
		limits.loads.parts().forEach((part) -> found.addAll(limits.of(part)));
		return found;
	}

	private List<Limit> of(JsonNode part) {
		return switch (part.get("type").stringValue()) {
			case ServiceSpec.TYPE -> service(part);
			case DatabaseSpec.TYPE -> database(part);
			case CacheSpec.TYPE -> new Found(part).add(CPU, number(part, CORES), this.times.cpu(part)).limits();
			case MessageQueueSpec.TYPE -> consumers(part);
			default -> List.of();
		};
	}

	private List<Limit> service(JsonNode part) {
		double replicas = orOne(part, "replicas");
		Found found = new Found(part).add(CPU, number(part, CORES) * replicas, this.times.cpu(part));
		if (part.get(CONFIG).path("blockingDownstream").asBoolean(false)) {
			found.add("workers", number(part, "workersPerReplica") * replicas, this.times.stay(part));
		}
		return found.limits();
	}

	private List<Limit> database(JsonNode part) {
		Found found = new Found(part).add(CPU, number(part, CORES), this.times.cpu(part))
			.add("connections", number(part, "maxConnections"), this.times.own(part));
		if (this.oneKey) {
			found.add("rowLock", 1, this.times.lockHold(part));
		}
		return found.limits();
	}

	// the consumers work in the background, whatever the queue's own place in the design;
	// one that does not set its concurrency handles a message at a time, as the model
	// reads it
	private List<Limit> consumers(JsonNode part) {
		double slots = orOne(part, "consumers") * orOne(part, "consumerConcurrency");
		double hold = this.times.consumerHold(part);
		return (slots > 0 && hold > 0) ? List.of(new Limit(id(part), "consumers", slots, hold, false)) : List.of();
	}

	private static double number(JsonNode part, String key) {
		return PythonValues.numberOrZero(part.get(CONFIG), key);
	}

	private static double orOne(JsonNode part, String key) {
		return PythonValues.isNone(PythonValues.get(part.get(CONFIG), key)) ? 1.0 : number(part, key);
	}

	private static String id(JsonNode part) {
		return part.get("id").stringValue();
	}

	/** The limits found for one part: those the design sets and a request uses. */
	private final class Found {

		private final JsonNode part;

		private final List<Limit> limits = new ArrayList<>();

		Found(JsonNode part) {
			this.part = part;
		}

		Found add(String name, double units, double msPerRequest) {
			if (units > 0 && msPerRequest > 0) {
				this.limits.add(new Limit(id(this.part), name, units, msPerRequest,
						!PartLimits.this.loads.sentTo(this.part).behindQueue()));
			}
			return this;
		}

		List<Limit> limits() {
			return List.copyOf(this.limits);
		}

	}

}
