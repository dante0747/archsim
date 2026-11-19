package io.archsim.engine.estimate;

import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.load.NodeLoad;
import io.archsim.engine.queueing.Moments;
import io.archsim.engine.settings.LoadCurve;
import io.archsim.model.component.cache.CacheConfig;
import io.archsim.model.component.database.DatabaseConfig;
import io.archsim.model.component.external.ExternalApiConfig;
import io.archsim.model.component.loadbalancer.LoadBalancerConfig;
import io.archsim.model.component.queue.MessageQueueConfig;
import io.archsim.model.component.service.Platform;
import io.archsim.model.component.service.ServiceConfig;

/**
 * How each kind of part serves requests at its load: its own time, its pools and slots,
 * whether requests queue, and how much of its CPU and rate limits it uses.
 */
final class PartShapes {

	private static final double MILLIS_PER_SECOND = 1000.0;

	/** A uniform linger's variance is its length squared over this. */
	private static final double UNIFORM_VARIANCE_DIVISOR = 12.0;

	private PartShapes() {
	}

	/**
	 * The shape of a part.
	 * @param config its parsed config
	 * @param load the requests it carries at the peak
	 * @return its shape; empty for a kind of part without a capacity model
	 */
	static Optional<PartShape> of(Record config, NodeLoad load) {
		return switch (config) {
			case ServiceConfig service -> Optional.of(service(service, load));
			case DatabaseConfig database -> Optional.of(database(database, load));
			case CacheConfig cache -> Optional.of(cache(cache, load));
			case LoadBalancerConfig balancer -> Optional.of(balancer(balancer, load));
			case ExternalApiConfig api -> Optional.of(api(api, load));
			case MessageQueueConfig queue -> Optional.of(queue(queue));
			default -> Optional.empty();
		};
	}

	/**
	 * A service: a pool of handlers per replica, or a serverless function that turns
	 * requests away past its concurrency.
	 * @param config the service's config
	 * @param load its load
	 * @return its shape
	 */
	private static PartShape service(ServiceConfig config, NodeLoad load) {
		Moments own = Moments.of(config.serviceTimeMs());
		boolean serverless = config.platform() == Platform.SERVERLESS;
		int replicas = config.autoscaling() ? Math.max(config.maxReplicas(), config.replicas()) : config.replicas();
		LoadCurve curve = LoadCurve.of(config.serviceTimeByLoad());
		if (!curve.isEmpty()) {
			// its own time at the factor of the rate one replica gets (a function
			// instance: back to back, at most one request at a time)
			double perReplica = load.total() / Math.max(1, replicas);
			if (serverless && own.mean() > 0) {
				perReplica = Math.min(load.total(), MILLIS_PER_SECOND / own.mean());
			}
			own = own.scaledBy(curve.factorAt(perReplica));
		}
		if (serverless) {
			return new PartShape(own, own, Moments.ZERO, null, 1, config.concurrencyLimit(), false, true, null, null,
					false);
		}
		Double cores = config.cpuCores();
		Double cpu = (cores != null && cores != 0)
				? load.total() * own.mean() * config.cpuShare() / (MILLIS_PER_SECOND * cores * Math.max(1, replicas))
				: null;
		return new PartShape(own, own, Moments.ZERO, null, Math.max(1, replicas), config.handlersPerReplica(), true,
				config.holdsHandlerThroughCalls(), cpu, null, false);
	}

	/**
	 * A database: connections per instance, a write waiting for the log flush, and the
	 * throughput limits of a metered table. Its CPU is its primary's, busy with the CPU
	 * share of its queries: a write waits for the flush on its connection with no core at
	 * work, as a run models it.
	 * @param config the database's config
	 * @param load its load
	 * @return its shape
	 */
	private static PartShape database(DatabaseConfig config, NodeLoad load) {
		Moments read = Moments.of(config.readServiceTimeMs());
		Moments write = Moments.of(config.writeServiceTimeMs());
		Moments flush = config.flushesCommits() ? Moments.of(config.commitFlushMs()) : Moments.ZERO;
		int copies = 1 + config.readReplicas();
		Double cores = config.cpuCores();
		Double cpu = null;
		if (cores != null && cores != 0) {
			double primary = ((load.read() / copies) * read.mean() + load.write() * write.mean()) * config.cpuShare()
					/ MILLIS_PER_SECOND / Math.max(1, config.shards());
			cpu = primary / cores;
		}
		return new PartShape(read, write, flush, null, Math.max(1, config.partitions() * copies),
				config.maxConnections(), true, false, cpu, limit(config, load), false);
	}

	/**
	 * The share of a database's limits used: its throughput limit, and a metered table's
	 * read and write capacity.
	 * @param config the database's config
	 * @param load its load
	 * @return the largest share; {@code null} without a limit
	 */
	private static @Nullable Double limit(DatabaseConfig config, NodeLoad load) {
		Double throughput = config.throughputLimitRps();
		Double limit = (throughput != null && throughput != 0) ? load.total() / throughput : null;
		if (config.metered()) {
			double units = Math.max(load.read() * config.unitsPerRead() / config.readUnitsPerSecond(),
					load.write() * config.unitsPerWrite() / config.writeUnitsPerSecond());
			limit = (limit != null) ? Math.max(limit, units) : units;
		}
		return limit;
	}

	private static PartShape cache(CacheConfig config, NodeLoad load) {
		Moments own = Moments.of(config.serviceTimeMs());
		Double cores = config.cpuCores();
		Double cpu = (cores != null && cores != 0) ? load.total() * own.mean() / MILLIS_PER_SECOND / cores : null;
		return new PartShape(own, own, Moments.ZERO, null, Math.max(1, config.shards()), config.executors(), true,
				false, cpu, null, config.populateOnMiss());
	}

	private static PartShape balancer(LoadBalancerConfig config, NodeLoad load) {
		Moments own = Moments.of(config.overheadMs());
		return new PartShape(own, own, Moments.ZERO, null, 1, config.maxConnections(), false, true, null,
				share(load, config.rateLimitRps()), false);
	}

	private static PartShape api(ExternalApiConfig config, NodeLoad load) {
		Moments own = Moments.of(config.latencyMs());
		return new PartShape(own, own, Moments.ZERO, null, 1, config.concurrencyLimit(), false, false, null,
				share(load, config.rateLimitRps()), false);
	}

	/**
	 * A queue: its slots are the consumers, whose own time is their work per message; a
	 * producer waits only for the publish (its batching and, with acks from all, the
	 * replication).
	 * @param config the queue's config
	 * @return its shape
	 */
	private static PartShape queue(MessageQueueConfig config) {
		Moments publish = Moments.of(config.publishTimeMs());
		double linger = config.lingerMsInEffect();
		if (linger > 0) {
			publish = publish.plus(new Moments(linger / 2, linger * linger / UNIFORM_VARIANCE_DIVISOR));
		}
		if (config.acknowledgedByAll()) {
			publish = publish.plus(Moments.of(config.replicationMs()));
		}
		Moments consumer = Moments.of(config.consumerTimeMs());
		return new PartShape(consumer, consumer, Moments.ZERO, publish, 1, config.consumerSlots(), true, true, null,
				null, false);
	}

	private static @Nullable Double share(NodeLoad load, @Nullable Double rateLimit) {
		return (rateLimit != null && rateLimit != 0) ? load.total() / rateLimit : null;
	}

}
