package io.archsim.model.component.service;

import io.archsim.model.component.Resources;

/**
 * What each setting of a service means, as the editor's inspector and the component
 * catalog show it.
 */
final class Descriptions {

	static final String AUTOSCALING_GROUP = "Autoscaling";

	static final String CAPACITY_GROUP = "Capacity";

	static final String COST_GROUP = "Cost";

	static final String PERFORMANCE_GROUP = "Performance";

	static final String RESOURCES_GROUP = "Resources";

	static final String SERVERLESS_GROUP = "Serverless";

	static final String TECHNOLOGY_BEHAVIOUR_GROUP = "Technology behaviour";

	static final String TECHNOLOGY_GROUP = "Technology";

	static final String TECHNOLOGY = "Runtime or framework. It sets the concurrency model (event loop for Node.js), "
			+ "GC pauses, JIT warm-up and serverless cold starts, shown under Technology behaviour.";

	static final String PLATFORM = "Where it runs. VM and Docker behave the same (fixed replicas unless autoscaling "
			+ "is on); Kubernetes usually pairs with autoscaling (HPA); serverless runs one request per instance "
			+ "with cold starts and a concurrency limit.";

	static final String REPLICAS = "Number of identical instances. Each has its own workers and queue. With "
			+ "autoscaling: the starting count.";

	static final String WORKERS_PER_REPLICA = "Requests one replica processes concurrently (threads/worker "
			+ "processes). Event-loop runtimes (Node.js) use the processes per replica instead.";

	static final String QUEUE_CAPACITY = "Requests that may wait for a free worker per replica; more are rejected "
			+ "(HTTP 503).";

	static final String QUEUE_TIMEOUT_MS = "Requests waiting longer than this for a worker are rejected (ms). Empty = "
			+ "no limit.";

	static final String SERVICE_TIME_MS = "Handler time per request on a worker, excluding downstream calls (ms). "
			+ "This is an assumption unless calibrated from telemetry.";

	static final String SERVICE_TIME_BY_LOAD = "How the service time changes with load: points of (req/s one replica "
			+ "receives, factor). At that rate the service time is the one above times the factor; between points "
			+ "it is interpolated in log(req/s), and outside them it stays at the nearest point's factor (never "
			+ "extrapolated). The rate is the replica's arrivals over the last second. Calibration fits it from "
			+ "CPU per request at several loads. Empty = the same service time at every load.";

	static final String BLOCKING_DOWNSTREAM = "On: a worker stays occupied while waiting for downstream calls "
			+ "(thread-per-request). Off: the worker is released during downstream I/O (async/event-loop "
			+ "servers).";

	static final String CPU_CORES = Resources.CPU_PER + "replica" + Resources.NOT_MODELLED_WHEN_EMPTY
			+ Resources.CPU_CONTENTION
			+ " The CPU share of the service time is CPU work. Serverless: one request per instance, so only values "
			+ "below 1 matter.";

	static final String CPU_SHARE = "Share of service time that needs a CPU core; the rest waits (I/O inside the "
			+ "handler) on the worker without one. Only used when CPU is set. Calibration fits it from CPU per "
			+ "request (CPU seconds ÷ requests).";

	static final String MEMORY_GIB = Resources.MEMORY_PER + "replica" + Resources.IN_GIB
			+ Resources.NOT_MODELLED_WHEN_EMPTY
			+ "When set, every request a replica holds (queued, running or waiting on downstream calls) uses memory; "
			+ "a request that would exceed it is refused (out of memory).";

	static final String INSTANCE_TYPE = Resources.INSTANCE_TYPE_OF_EACH + "replica on a VM or Docker"
			+ Resources.INSTANCE_TYPE_USE;

	static final String MEMORY_PER_REQUEST_MB = "Memory one in-flight request uses (MiB). Only used when memory is "
			+ "set.";

	static final String MEMORY_BASELINE_MB = "Memory a replica uses before any request (runtime, heap, caches) (MiB). "
			+ "Only used when memory is set.";

	static final String MEMORY_PER_RPS_MB = "Memory that grows with throughput, per request per second a replica "
			+ "serves (MiB). Garbage-collected runtimes keep what requests allocate until the next collection, so "
			+ "their heap grows with the request rate rather than with requests held. It counts in the memory a "
			+ "replica uses and is sized for, not in the out-of-memory limit (a collector reclaims it under "
			+ "pressure). 0: not modelled. Only used when memory is set.";

	static final String AUTOSCALING = "Add and remove replicas to keep utilization near the target (like a Kubernetes "
			+ "Horizontal Pod Autoscaler). Ignored on the serverless platform.";

	static final String MIN_REPLICAS = "Fewest replicas autoscaling keeps running.";

	static final String MAX_REPLICAS = "Most replicas autoscaling may run.";

	static final String TARGET_UTILIZATION = "Utilization the autoscaler aims for: the share of busy workers, or of "
			+ "the CPU size when the replica has one (whichever is higher), like the Kubernetes HPA's CPU target.";

	static final String SCALE_INTERVAL_S = "How often the autoscaler re-evaluates (s). Scale-down waits for 3 "
			+ "consecutive low readings.";

	static final String STARTUP_S = "Time from the scale-up decision until a new replica takes traffic (pod "
			+ "scheduling, image pull, start-up, readiness).";

	static final String CONCURRENCY_LIMIT = "Most instances (= concurrent requests) the function may run; beyond it "
			+ "requests are throttled.";

	static final String COLD_START_MS = "Extra time for a request that has to start a new instance (ms). Empty = the "
			+ "runtime's typical cold start (Java about 1.2 s, Node.js and Python about 250 ms, Go about 100 ms; "
			+ "illustrative).";

	static final String CLUSTER = "The Kubernetes cluster (a cluster part in this design) whose nodes run this "
			+ "service's pods. Empty = the only cluster in the design, if there is exactly one.";

	static final String SCALE_OUT_RATE = "New instances the platform may start per 10 seconds (AWS Lambda: 1,000); "
			+ "requests that would need more are throttled.";

	static final String RUNTIME_MODEL = "thread_pool: each worker handles one request at a time. event_loop: each "
			+ "process runs every request's CPU work on one thread, one piece at a time, and waits for I/O "
			+ "without blocking (Node.js). Auto follows the technology.";

	static final String PROCESSES_PER_REPLICA = "Event-loop processes per replica (Node.js cluster mode, PM2, uvicorn "
			+ "workers).";

	static final String GC_PAUSE_MS = "Median stop-the-world pause per garbage collection (ms); every request on the "
			+ "replica stalls meanwhile. Empty = runtime default (Java about 12, .NET 6, Node.js 2, Go 0.1, Rust "
			+ "0; illustrative).";

	static final String GC_EVERY_REQUESTS = "Requests a replica serves between collections (allocation pressure). "
			+ "Empty = runtime default.";

	static final String WARMUP_FACTOR = "How much slower a freshly started instance runs before the JIT has optimised "
			+ "it (applies to replicas started during the run and to serverless instances). Empty = runtime "
			+ "default (Java 2.5x, .NET 1.5x, others 1x; illustrative).";

	static final String WARMUP_S = "Time for a new instance to reach full speed (s), fading linearly. Empty = runtime "
			+ "default.";

	static final String KEEP_WARM_S = "Idle time after which an instance is reclaimed (s); the next request needs a "
			+ "cold start.";

	static final String COST_PER_HOUR = "Price of one replica (or serverless instance) per hour, in USD. Illustrative "
			+ "default; use your provider's pricing.";

	private Descriptions() {
	}

}
