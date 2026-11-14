package io.archsim.model.document;

/**
 * What each record of the design document and each of its settings mean, as the JSON
 * Schema, the editor's inspector and the component catalog show it, and the groups the
 * editor's forms put the settings in. Each record's texts are named after it, as in
 * {@code FAULT_START_S}, because about ten records share this class.
 */
final class Descriptions {

	/** What a link's request types must be, as its error says. */
	static final String EDGE_UNIQUE_REQUEST_TYPES = "request types must be unique";

	static final String WORKLOAD_CHAOS_GROUP = "Chaos";

	static final String WORKLOAD_KEYSPACE_GROUP = "Keyspace";

	static final String EDGE_NETWORK_GROUP = "Network";

	static final String EDGE_RELIABILITY_GROUP = "Reliability";

	static final String EDGE_ROUTING_GROUP = "Routing";

	static final String WORKLOAD_RUN_GROUP = "Run";

	static final String WORKLOAD_TRAFFIC_GROUP = "Traffic";

	static final String ARCHITECTURE_AUTO_SIZE = "Give parts with an empty CPU, memory or storage spec the size they "
			+ "need for the design's peak load (see sizing.py). false: an empty spec is not modelled.";

	static final String ARCHITECTURE_HOST = "The one machine every part with a CPU size runs on (docker compose on "
			+ "one VM, a benchmark on a laptop): their CPU work shares its physical cores. Empty: each part's "
			+ "vCPUs are its own (as before schema 1.7).";

	static final String DOCUMENT_PROVENANCE = "Where inputs came from, keyed by path: nodes/<id>/<config "
			+ "key>, edges/<id>/<config key> or workload/<field>.";

	static final String CALIBRATION_INPUTS = "Inputs set from telemetry";

	static final String EDGE_REQUEST_TYPES = "Request types carried by this link. Used for routing (e.g. reads "
			+ "to cache, writes to DB).";

	static final String EDGE_LATENCY_MS = "One-way network latency in ms, applied to the request and again to "
			+ "the response.";

	static final String EDGE_TIMEOUT_MS = "Per-attempt time the caller waits before giving up (ms). Empty = "
			+ "wait indefinitely.";

	static final String EDGE_MAX_ATTEMPTS = "Total attempts including retries (1 = no retries). Bounded to "
			+ "prevent retry storms from running away.";

	static final String EDGE_RETRY_BACKOFF_MS = "Base backoff before a retry (ms); doubles on each further retry.";

	static final String EDGE_CALL_ORDER = "Call order when a service calls several dependencies for the same "
			+ "request (lower first).";

	static final String EDGE_READS_ON_MISS = "Cache-aside: send reads over this link only after the service's "
			+ "cache lookup for the same request missed (or failed, when that link carries on after failures). "
			+ "The service then reads the data store itself and writes the value back to the cache. Writes on "
			+ "this link are always sent.";

	static final String EDGE_CONTINUE_ON_FAILURE = "If this call still fails after its attempts, the service "
			+ "carries on instead of failing the request. A failed cache lookup counts as a miss, so reads go to "
			+ "the links set to 'Reads only on a cache miss'; a failed cache invalidation is skipped.";

	static final String FAULT_TARGET = "Node id (outage, slowdown) or edge id (latency)";

	static final String FAULT_START_S = "When the fault starts (s, simulated time)";

	static final String FAULT_DURATION_S = "How long the fault lasts (s)";

	static final String FAULT_SLOWDOWN_FACTOR = "slowdown: service-time multiplier";

	static final String FAULT_EXTRA_MS = "latency: added one-way latency (ms)";

	static final String HOST_PHYSICAL_CORES = "Physical cores of the machine (not hardware threads).";

	static final String HOST_THREADS_PER_CORE = "Hardware threads per core: 2 with hyperthreading (SMT), 1 without.";

	static final String HOST_SIBLING_SPEED = "Speed of a thread while its core's other thread is busy too, "
			+ "relative to running alone: a busy pair does twice this much work (about 0.6 on Intel and AMD "
			+ "cores).";

	static final String HOST_ALL_CORE_CLOCK = "Clock with every thread busy, relative to one busy thread (turbo "
			+ "and power limits; about 0.75 on a laptop, close to 1 on servers). In between it falls in a "
			+ "straight line.";

	static final String HOST_OTHER_BUSY_THREADS = "Threads busy on the machine with work outside the design "
			+ "(operating system, other programs).";

	static final String HOST_CLIENT_CPU_MS = "CPU time each request of the clients' traffic costs on the machine "
			+ "outside the design's parts (ms), e.g. a load generator running on it. 0: the clients are elsewhere.";

	static final String PROVENANCE_FIT_ERROR = "Largest relative error of the fit at the checked quantiles";

	static final String PROVENANCE_VALUE = "The value calibration set (JSON, as stored at the path). The input counts "
			+ "as measured only while its current value still equals this; a scenario, sweep or edit that changes it "
			+ "makes it assumed again. Records without it (written by earlier builds) are taken at their word.";

	static final String NODE_ID = "Unique node id (letters, digits, _ . -)";

	static final String NODE_TYPE = "Registered component type";

	static final String OBJECTIVES_LATENCY_P95_MS = "p95 end-to-end latency target (ms)";

	static final String OBJECTIVES_LATENCY_P99_MS = "p99 end-to-end latency target (ms)";

	static final String OBJECTIVES_AVAILABILITY = "Minimum share of requests that succeed";

	static final String OBJECTIVES_MIN_THROUGHPUT_RPS = "Minimum successful requests/s";

	static final String OVERRIDE_TARGET_ID = "Node or edge id; omitted for workload";

	static final String PRICING_PURCHASE_OPTION = "On-demand prices. Savings plans and reserved terms are not "
			+ "modeled yet.";

	static final String PRICING_FAMILIES = "Instance families to snap to (e.g. m7g, r7g); empty: any in the snapshot. "
			+ "Naming a burstable family (t4g) allows it everywhere it fits, without restricting the lists";

	static final String PRICING_BURSTABLE = "auto: a light part may run on a burstable type (t4g) when its CPU at the "
			+ "base load stays under 80% of the type's baseline and its peak under 60% of its vCPUs. never: no "
			+ "burstable types (many teams ban them in production).";

	static final String PRICING_SNAPSHOT_DATE = "Use exactly the snapshot of this date (YYYY-MM-DD), never a newer "
			+ "one; a warning and generic rates when this machine does not have it. Empty: the newest.";

	static final String PRICING_SNAPSHOT_SHA256 = "With snapshotDate: the snapshot's content hash, so changed "
			+ "prices of the same date are not used either. A run's input records it.";

	static final String REPLAY_METRIC = "Latency quantiles (ms), throughput (req/s), CPU in use (mean cores "
			+ "over all replicas, schema 1.5) or the memory peak per replica (MiB, schema 1.5)";

	static final String REPLAY_ERROR = "(simulated - observed) / observed";

	static final String WORKLOAD_RPS = "Baseline offered load (requests/s)";

	static final String WORKLOAD_DURATION_S = "Arrival window length (s)";

	static final String WORKLOAD_WARMUP_S = "Initial period excluded from summary statistics (s)";

	static final String WORKLOAD_SEED = "Random seed for reproducibility";

	static final String WORKLOAD_READ_RATIO = "Fraction of requests that are reads";

	static final String WORKLOAD_ARRIVAL_PROCESS = "poisson: random (exponential) inter-arrivals; deterministic: "
			+ "evenly spaced";

	static final String WORKLOAD_PATTERN = "Shape of the offered load over time";

	static final String WORKLOAD_KEY_COUNT = "Number of distinct keys requests touch (used by TTL cache models)";

	static final String WORKLOAD_KEY_SKEW = "Zipf exponent of key popularity (0 = uniform, ~1 = typical web skew)";

	static final String WORKLOAD_DRAIN_S = "Time after arrivals stop to let in-flight requests finish (s). "
			+ "Empty = automatic.";

	static final String WORKLOAD_FAULTS = "Faults injected during the run (outages, slowdowns, network latency)";

	static final String WORKLOAD_PEAK_HOURS = "Hours a month the traffic runs at the pattern's peak (a burst's rate, "
			+ "a ramp's high end, a trace's highest point), for the monthly cost; the rest of the month runs at the "
			+ "base load. 0: the peak is billed for no hour.";

	private Descriptions() {
	}

}
