package io.archsim.benchmarks.experiments;

import java.util.List;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * An accuracy experiment: a stack, the traffic sent to it, and how it is calibrated.
 *
 * @param id its name, which names its results' folder
 * @param stack the stack it runs on ({@code compose.yaml}'s profile)
 * @param title what the comparison and the page call it
 * @param mix k6's traffic mix
 * @param readRatio the design's share of reads
 * @param keys the keys the traffic is spread over
 * @param skew the Zipf skew of that spread (0 for uniform)
 * @param calibration the mixes of the calibration passes
 * @param runtime the service's runtime ({@link Experiments#runtime(Experiment)}); a
 * chain's gateway and API are both Node.js
 * @param ttlS the cache's TTL, on the cache stacks
 * @param heldOut its registration when it is held out; {@code null} for the four the
 * model was built with
 * @param serviceCpus the CPUs of its service's container when they are not its runtime's
 * (python-wide's four); {@code null} for the runtime's
 * ({@link Experiments#runtime(Experiment)})
 * @param pool the connections its service's pool holds when they are not
 * {@value #DEFAULT_POOL} (go-pool's two); {@code null} for {@value #DEFAULT_POOL}
 * ({@link #connections})
 */
public record Experiment(String id, String stack, String title, String mix, double readRatio, int keys, double skew,
		List<String> calibration, String runtime, int ttlS, @Nullable HeldOut heldOut, @Nullable Integer serviceCpus,
		@Nullable Integer pool) {

	/** The connections a service's pool holds unless its experiment says otherwise. */
	public static final int DEFAULT_POOL = 10;

	/**
	 * Copies the calibration's mixes, and checks the CPUs and the pool it names.
	 * @throws IllegalArgumentException when it names no CPU for its service or no
	 * connection for its pool
	 */
	public Experiment {
		calibration = List.copyOf(calibration);
		if (serviceCpus != null && serviceCpus < 1) {
			throw new IllegalArgumentException(
					id + "'s service has " + serviceCpus + " CPUs: a service needs at least 1");
		}
		if (pool != null && pool < 1) {
			throw new IllegalArgumentException(
					id + "'s pool holds " + pool + " connections: a service needs at least 1");
		}
	}

	/**
	 * The connections its service holds to PostgreSQL, which are all PostgreSQL serves at
	 * once: its own pool's, else {@value #DEFAULT_POOL}.
	 * @return the connections
	 */
	public int connections() {
		return (this.pool != null) ? this.pool : DEFAULT_POOL;
	}

	/**
	 * Whether every request updates the same row, so that the row's lock lets one through
	 * at a time: all writes, over one key (pg-hot-row and java-hot-row).
	 * @return whether its traffic is a hot row's
	 */
	public boolean updatesOneRow() {
		return this.keys == 1 && this.readRatio == 0;
	}

	/**
	 * Whether it is a held-out experiment, sealed until a frozen engine predicts it.
	 * @return whether it is held out
	 */
	public boolean isHeldOut() {
		return this.heldOut != null;
	}

	/**
	 * Whether its service runs on Node.js, whose garbage collection the service reports
	 * itself.
	 * @return whether it runs on Node.js
	 */
	public boolean runsOnNode() {
		return "nodejs".equals(this.runtime);
	}

	/**
	 * The {@code /stats} of the service that calls PostgreSQL: chain's API, which its
	 * gateway reports as {@code upstream}, else the service k6 calls.
	 * @param service the {@code /stats} of the service k6 calls
	 * @return the client's {@code /stats}
	 * @throws IllegalStateException when chain's gateway reported no API behind it
	 */
	public JsonNode postgresClient(JsonNode service) {
		if (!"chain".equals(this.stack)) {
			return service;
		}
		JsonNode upstream = service.get("upstream");
		if (upstream == null) {
			throw new IllegalStateException("chain's gateway reported no upstream");
		}
		return upstream;
	}

	/**
	 * Whether its stack has Redis in front of PostgreSQL: {@code cache} and
	 * {@code cache-ttl}.
	 * @return whether it has a cache
	 */
	public boolean hasCache() {
		return "cache".equals(this.stack) || "cache-ttl".equals(this.stack);
	}

}
